#!/usr/bin/env python3
"""
Export a ModernBERT decision model to ONNX for the Jev Android app.

Produces three files to copy to the phone and import from Jev > Settings:
  model.onnx        int8-quantised encoder (~150 MB for ModernBERT-base)
  tokenizer.json    the model's Hugging Face tokenizer
  jev_config.json   how the app should prompt and score the model

The app scores every choice as a (premise, hypothesis) pair:
  premise    = the state (e.g. the notification text)
  hypothesis = "<question> <choice>", e.g. "This notification is urgent ..."
and uses the logit of the "entailment"-like class as that choice's score,
then a softmax across choices gives choice + confidence + probabilities.

Usage:
  pip install -r tools/requirements.txt
  python tools/export_jev_onnx.py --model tasksource/ModernBERT-base-nli --out jev-model
  # or try a JEV checkpoint that exposes a sequence-classification head:
  python tools/export_jev_onnx.py --model tasksource/modernbert-tasksource-jev --out jev-model --trust-remote-code

The Jev app normally downloads these automatically (Settings > Download model);
CI publishes them with .github/workflows/model.yml. Manual import also works.
"""
import argparse
import json
import os
import shutil

import torch
from transformers import AutoModelForSequenceClassification, AutoTokenizer


def pick_score_index(id2label):
    """Find the class that means 'this choice is right'."""
    names = {int(k): str(v).lower() for k, v in id2label.items()}
    for wanted in ("entailment", "entail", "yes", "true", "positive", "label_1"):
        for idx, name in names.items():
            if name == wanted or name.startswith(wanted):
                return idx
    return 0 if len(names) > 1 else -1


def pick_contradiction_index(id2label):
    names = {int(k): str(v).lower() for k, v in id2label.items()}
    for idx, name in names.items():
        if name.startswith("contradict") or name in ("no", "false", "negative"):
            return idx
    return -1


# Mixed-language, emoji, numbers and URLs: anything a notification can contain.
REFERENCE_PAIRS = [
    ("App: WhatsApp. From: Mom. Message: call me now", "This notification is urgent and needs attention right now."),
    ("Message: DHL: Your parcel is on hold. Pay QAR 9.50 within 24h: dhl-parcel-fee.xyz/pay", "This message asks the reader to pay or send money."),
    ("Card payment of 24.00 QAR at STARBUCKS VILLAGGIO.", "This purchase is for restaurants, cafes and food delivery."),
    ("Message: 🎉 Congratulations!!! You've WON an iPhone 16 — claim now ➡️ bit.ly/3xYz", "This message promises a prize, refund or free money."),
    ("Message: مرحبا، تم خصم 120.50 ر.ق من بطاقتك المنتهية بـ 4421", "This message asks the reader to pay or send money."),
    ("App: Gmail.   Title: Re: Q3 budget   (draft v2)\nMessage: can we move it to 3:30pm?", "This notification can wait until later."),
    ("Café Müller — naïve résumé coöperate 1,234,567.89 USD", "This purchase is for something else."),
]


def build_quantized_variants(fp32, out_dir):
    """Candidate smaller models. Which one ships is decided by measured agreement, not by hope."""
    import onnx
    from onnxruntime.quantization import QuantType, quantize_dynamic
    variants = {}
    try:
        # Weight-only 4-bit blocks (MatMulNBits); activations stay fp32, so results
        # don't depend on input statistics.
        from onnxruntime.quantization.matmul_4bits_quantizer import MatMul4BitsQuantizer
        q = MatMul4BitsQuantizer(onnx.load(fp32), block_size=32, is_symmetric=True)
        q.process()
        path = os.path.join(out_dir, "model_int4.onnx")
        q.model.save_model_to_file(path, use_external_data_format=False)
        variants["int4-weights"] = path
    except Exception as e:  # keep going with the other variants
        print("int4 variant failed:", repr(e))
    path = os.path.join(out_dir, "model_int8.onnx")
    quantize_dynamic(fp32, path, weight_type=QuantType.QInt8)
    variants["int8-dynamic"] = path

    # int8 only for MatMuls against weights: attention score/value MatMuls (two
    # activations) and the embedding table stay fp32. Dynamic quantisation of
    # attention is a common cause of accuracy collapse in encoders.
    m = onnx.load(fp32)
    weights = {i.name for i in m.graph.initializer}
    activation_matmuls = [n.name for n in m.graph.node
                          if n.op_type == "MatMul" and not any(i in weights for i in n.input)]
    print(f"excluding {len(activation_matmuls)} activation x activation MatMuls from int8")
    for per_channel in (False, True):
        path = os.path.join(out_dir, f"model_int8w{'_pc' if per_channel else ''}.onnx")
        quantize_dynamic(fp32, path, weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"],
                         nodes_to_exclude=activation_matmuls, per_channel=per_channel)
        variants["int8-weights" + ("-perchannel" if per_channel else "")] = path

    try:
        from onnxconverter_common import float16
        path = os.path.join(out_dir, "model_fp16.onnx")
        onnx.save(float16.convert_float_to_float16(onnx.load(fp32), keep_io_types=True), path)
        variants["fp16"] = path
    except Exception as e:
        print("fp16 variant failed:", repr(e))
    return variants


def decide(sess, tok, premise, hypotheses, kind, score_index, contradiction_index):
    import numpy as np
    rows = []
    for h in hypotheses:
        e = tok(premise, h, return_tensors="np")
        rows.append(sess.run(None, {"input_ids": e["input_ids"].astype(np.int64),
                                    "attention_mask": e["attention_mask"].astype(np.int64)})[0][0])
    if kind == "binary":
        return 0 if rows[0][score_index] >= rows[0][contradiction_index] else 1
    logp = [r[score_index] - np.log(np.exp(r - r.max()).sum()) - r.max() for r in rows]
    return int(np.argmax(logp))


def choose_variant(variants, tok, min_agreement, score_index, contradiction_index):
    """Smallest variant whose decisions on the app's self-test match fp32 often enough."""
    import onnxruntime as ort
    eval_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "eval_decisions.json")
    decisions = json.load(open(eval_path))
    reference = None
    results = {}
    for name in ["fp32"] + [n for n in variants if n != "fp32"]:
        try:
            sess = ort.InferenceSession(variants[name])
        except Exception as e:
            print(f"variant {name:13s} cannot run in ONNX Runtime CPU: {type(e).__name__}")
            continue
        got = [decide(sess, tok, d["premise"], d["hypotheses"], d["kind"], score_index, contradiction_index) for d in decisions]
        if reference is None:
            reference = got
        agree = sum(a == b for a, b in zip(got, reference)) / len(reference)
        size = os.path.getsize(variants[name]) / 1e6
        results[name] = (agree, size)
        print(f"variant {name:13s} size {size:7.1f} MB  agrees with fp32 on {agree * 100:5.1f}% of {len(reference)} self-test decisions")
    ok = [n for n, (agree, _) in results.items() if agree >= min_agreement]
    chosen = min(ok, key=lambda n: results[n][1])
    print(f"CHOSEN VARIANT: {chosen}")
    return chosen


def consistency_report(tok, model, fp32_path, final_path):
    """Does padding (batching several choices) or int8 quantisation change the answer?"""
    import numpy as np
    import onnxruntime as ort
    pairs = REFERENCE_PAIRS
    single_t = []
    with torch.no_grad():
        for a, b in pairs:
            e = tok(a, b, return_tensors="pt")
            single_t.append(model(**e).logits[0].numpy())
        eb = tok([p[0] for p in pairs], [p[1] for p in pairs], return_tensors="pt", padding=True)
        batched_t = model(**eb).logits.numpy()
    print("torch fp32  batched-vs-single max |diff|:", float(np.abs(batched_t - np.stack(single_t)).max()))
    for name, path in (("onnx fp32", fp32_path), ("onnx int8", final_path)):
        s = ort.InferenceSession(path)
        single = []
        for a, b in pairs:
            e = tok(a, b, return_tensors="np")
            single.append(s.run(None, {"input_ids": e["input_ids"].astype(np.int64), "attention_mask": e["attention_mask"].astype(np.int64)})[0][0])
        single = np.stack(single)
        for pad_name, pad_id in (("tokenizer pad", None), ("pad id 0", 0)):
            ids = eb["input_ids"].numpy().astype(np.int64).copy()
            mask = eb["attention_mask"].numpy().astype(np.int64)
            if pad_id is not None:
                ids[mask == 0] = pad_id
            batched = s.run(None, {"input_ids": ids, "attention_mask": mask})[0]
            print(f"{name}  batched({pad_name})-vs-single max |diff|:", float(np.abs(batched - single).max()))
        print(f"{name}  vs torch single max |diff|:", float(np.abs(single - np.stack(single_t)).max()))
        print(f"{name}  argmax agrees with torch on", int((single.argmax(1) == np.stack(single_t).argmax(1)).sum()), "/", len(pairs))


def write_reference(out_dir, tok, model, max_length):
    """Token ids + logits from the Python stack, for the app's JVM tests to match exactly."""
    import numpy as np
    import onnxruntime as ort
    sess = ort.InferenceSession(os.path.join(out_dir, "model.onnx"))
    rows = []
    for a, b in REFERENCE_PAIRS:
        enc = tok(a, b, truncation=True, max_length=512)
        ids = np.array([enc["input_ids"]], dtype=np.int64)
        mask = np.array([enc["attention_mask"]], dtype=np.int64)
        logits = sess.run(None, {"input_ids": ids, "attention_mask": mask})[0][0]
        rows.append({"a": a, "b": b, "ids": enc["input_ids"], "logits": [float(x) for x in logits]})
    with open(os.path.join(out_dir, "reference.json"), "w") as f:
        json.dump({"tokenization": rows}, f, ensure_ascii=False, indent=1)


def write_manifest(out_dir, model_name, version, variant):
    import hashlib
    files = []
    for name in ("model.onnx", "tokenizer.json", "jev_config.json"):
        path = os.path.join(out_dir, name)
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        files.append({"name": name, "size": os.path.getsize(path), "sha256": h.hexdigest()})
    manifest = {"id": version, "source_model": model_name, "variant": variant, "files": files}
    with open(os.path.join(out_dir, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    return manifest


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="tasksource/ModernBERT-base-nli")
    ap.add_argument("--out", default="jev-model")
    ap.add_argument("--max-length", type=int, default=256)
    ap.add_argument("--no-quantize", action="store_true")
    ap.add_argument("--trust-remote-code", action="store_true")
    ap.add_argument("--version", default="model-v1", help="Release tag the app downloads from")
    ap.add_argument("--min-agreement", type=float, default=0.97,
                    help="A smaller variant ships only if it makes the same decision as fp32 this often")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    tok = AutoTokenizer.from_pretrained(args.model, trust_remote_code=args.trust_remote_code)
    model = AutoModelForSequenceClassification.from_pretrained(
        args.model, trust_remote_code=args.trust_remote_code, reference_compile=False, attn_implementation="eager"
    ).eval()

    sample = tok(
        ["App: WhatsApp. From: Mom. Message: call me now"] * 2,
        ["This notification is urgent", "This notification is noise"],
        return_tensors="pt", padding=True,
    )
    inputs = (sample["input_ids"], sample["attention_mask"])
    fp32 = os.path.join(args.out, "model_fp32.onnx")
    torch.onnx.export(
        model, inputs, fp32,
        input_names=["input_ids", "attention_mask"],
        output_names=["logits"],
        dynamic_axes={"input_ids": {0: "batch", 1: "seq"}, "attention_mask": {0: "batch", 1: "seq"}, "logits": {0: "batch"}},
        opset_version=17,
        dynamo=False,
    )

    final = os.path.join(args.out, "model.onnx")
    variants = {"fp32": fp32}
    if not args.no_quantize:
        variants.update(build_quantized_variants(fp32, args.out))
    labels = getattr(model.config, "id2label", {0: "label_0"})
    chosen = choose_variant(variants, tok, args.min_agreement, pick_score_index(labels), pick_contradiction_index(labels))
    shutil.copy(variants[chosen], final)
    consistency_report(tok, model, fp32, final)
    for path in variants.values():
        if os.path.exists(path):
            os.remove(path)

    tok.save_pretrained(args.out)  # writes tokenizer.json
    for extra in os.listdir(args.out):
        if extra not in ("model.onnx", "tokenizer.json"):
            os.remove(os.path.join(args.out, extra))

    id2label = getattr(model.config, "id2label", {0: "label_0"})
    config = {
        "mode": "pair",
        "premise_template": "{state}",
        "hypothesis_template": "{question} {choice}.",
        "score_index": pick_score_index(id2label),
        "contradiction_index": pick_contradiction_index(id2label),
        "use_log_prob": True,
        "max_length": args.max_length,
        "temperature": 1.0,
        "labels": {str(k): v for k, v in id2label.items()},
        "source_model": args.model,
    }
    with open(os.path.join(args.out, "jev_config.json"), "w") as f:
        json.dump(config, f, indent=2)

    # Sanity check with onnxruntime, exactly like the phone will run it.
    import numpy as np
    import onnxruntime as ort
    sess = ort.InferenceSession(final)
    out = sess.run(None, {"input_ids": sample["input_ids"].numpy().astype(np.int64),
                          "attention_mask": sample["attention_mask"].numpy().astype(np.int64)})[0]
    print("labels:", id2label, "score_index:", config["score_index"], "contradiction_index:", config["contradiction_index"])
    print("logits for [urgent, noise]:", out.tolist())
    write_reference(args.out, tok, model, args.max_length)
    manifest = write_manifest(args.out, args.model, args.version, chosen)
    print(json.dumps(manifest, indent=2))
    print("Wrote", args.out, "->", sorted(os.listdir(args.out)))
    print("model.onnx size:", os.path.getsize(final) / 1e6, "MB")


if __name__ == "__main__":
    main()
