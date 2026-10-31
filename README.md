# Jev: on-device decisions for your notifications

Jev is an Android app that makes small, repeated decisions about everything your phone receives, entirely on the phone. It ships three features that share one data source, Android's notification listener, and one on-device decision model:

| Feature | Decision | Choices |
|---|---|---|
| **Notification triage** | "This notification is …" | urgent · can wait · digest · silence |
| **Scam shield** | 8 yes/no red-flag questions per message | yes · no, plus offline link analysis |
| **Private money tracker** | "This purchase is for …" | 11 budget categories, plus unusual-charge and subscription detection |

Notifications, messages and transactions are processed only on the phone and are never uploaded. The only network use is the one-time model download.

## How it decides

A Jev-style decision model takes a **state**, a **question** and a fixed set of **choices**. It returns the **chosen option**, a **confidence** and the **probability of every choice**. It doesn't write free text.

```
state:    "App: WhatsApp. From: Mom. Message: call me when you land. Time: Friday 22:10."
question: "This notification is"
choices:  [urgent…, can wait…, digest…, silence…]
→ choice = "urgent", confidence = 0.81, probabilities = {...}
```

Decisions run through `DecisionRouter`, which works in this order:
1. **Your own rules come first.** These are learned from your corrections: tap any inbox item to fix it, and that sender is remembered.
2. **The on-device model is used next**, if one is installed. It's a ModernBERT encoder run with ONNX Runtime.
3. **Built-in rules are the fallback.** The app is fully usable before any model is installed. If the model is missing, errors, or isn't confident enough, the rules answer instead.

The scam shield asks each red-flag question separately. That's how it gives an explainable checklist even though the model can't write explanations. Link checks (look-alike domains, shorteners, raw IPs, punycode, risky TLDs) are deterministic and run without any model.

## Project layout

```
core/   Pure Kotlin. No Android dependency; unit-tested on the JVM.
        model/      Decision, DecisionRouter, PairScoringDecisionModel, JevModelConfig
        tokenizer/  Byte-level BPE tokenizer that reads Hugging Face tokenizer.json (ModernBERT)
        triage/     TriageEngine (+ VIP and learned-rule handling)
        scam/       ScamShield (red-flag checklist), LinkInspector
        money/      TransactionParser, Categorizer, SpendingInsights (anomalies, subscriptions)
        JevPipeline.kt  One notification in → triage + scam report + transaction out
app/    Android (Kotlin, Jetpack Compose, Room, WorkManager, ONNX Runtime)
        service/JevNotificationListener  The notification listener that feeds the pipeline
        ml/          ONNX Runtime encoder + model import/loading
        work/        Digest delivery at the times you choose
        ui/          Inbox · Shield · Money · Settings
tools/export_jev_onnx.py  Exports a ModernBERT decision model to ONNX for the app
```

## Get it on your Galaxy S25 Ultra

**Option A: download the APK from CI (no Android Studio needed)**
1. Every push runs the **Build APK** GitHub Action. Open the latest run under *Actions* and download the `jev-debug-apk` artifact.
2. Copy `app-debug.apk` to the phone and open it. Allow "Install unknown apps" for your file manager when asked.

**Option B: build it yourself**
1. Install Android Studio and open this folder.
2. Enable *Developer options → USB debugging* on the phone, plug it in, and press **Run**.
   Or from the command line: `./gradlew :app:installDebug`.

**First run**
1. Grant **Notification access** (Inbox tab → *Open settings* → Jev → Allow).
2. Allow Jev to post notifications. It needs this for scam warnings, digests and spending flags.
3. Jev starts in **shadow mode**: it labels notifications but hides nothing. Correct a few labels and add your important people in Settings. When you trust it, turn on **Hide digest & silenced**.
4. On Samsung phones, set Jev's battery usage to **Unrestricted** (Settings → Apps → Jev → Battery). This stops One UI from killing the listener.

## The on-device model

Jev works with built-in rules out of the box. To use the model, tap **Download model** on the Inbox card or under Settings → On-device model. The app then:

1. Fetches `manifest.json` from the `model-v1` GitHub release. This is the only thing Jev downloads.
2. Downloads `model.onnx` (the smallest variant that passes the agreement gate; currently fp32, about 600 MB), `tokenizer.json` and `jev_config.json` in the background, with a progress notification. Wi-Fi only is on by default, interrupted downloads resume where they stopped, and every file is checked against its SHA-256 from the manifest before it's used.
3. Installs and loads the model with ONNX Runtime, then **runs the self-test automatically**. The self-test is 23 labelled examples across triage, the scam checklist and budget categories. You see the score and ms per decision on your phone, and can open the result of every individual test.

**Settings → Diagnostics → Check that everything works** tests the live path end to end. Jev sends itself three test notifications (a parcel scam, a promo and an emergency). It then checks that the notification listener received them, that the scam shield flagged the scam, and that triage sorted the other two correctly. It also confirms notification access, alert permission and model status.

### How the model is built and tested

`.github/workflows/model.yml` builds the release:
1. **Export.** `tools/export_jev_onnx.py` exports [`tasksource/ModernBERT-base-nli`](https://huggingface.co/tasksource/ModernBERT-base-nli) (ModernBERT-base, 149M params) to ONNX and quantises it to int8. It also reports whether batching or quantisation changes the outputs.
2. **Verify.** `RealModelTest` runs the exported model through the app's own Kotlin code: the tokenizer must match Hugging Face's token IDs exactly, the logits must match Python's ONNX Runtime, and the self-test must pass.
3. **Publish.** Only if all of that passes are the files published as the `model-v1` release.

`.github/workflows/android.yml` builds the APK, then runs `OnDeviceTest` on an Android 15 emulator. The test downloads the published model, checks resume and checksum protection, runs ONNX Runtime on Android, runs the self-test, and runs the live notification pipeline check.

The official ModernBERT-JEV checkpoint isn't publicly downloadable, so CI can't fetch it. The app is built so it can be swapped in: point `--model` at any sequence-classification checkpoint and adjust `jev_config.json`.

To build a model by hand and import it instead:

```bash
pip install -r tools/requirements.txt
python tools/export_jev_onnx.py --model tasksource/ModernBERT-base-nli --out jev-model
```

Then use **Settings → Import files** and pick `model.onnx`, `tokenizer.json` and `jev_config.json`.

`jev_config.json` controls how the model is prompted:

```json
{
  "mode": "pair",
  "premise_template": "{state}",
  "hypothesis_template": "{question} {choice}.",
  "score_index": 0,
  "contradiction_index": 2,
  "use_log_prob": true,
  "max_length": 256,
  "temperature": 1.0
}
```

How the config is used:
- **Multi-choice questions** (triage, budget category): each choice is scored as a (premise, hypothesis) pair. The logit at `score_index` ("entailment") is that choice's score, and a softmax across choices gives the decision.
- **Yes/no red flags:** a single statement is judged once, using entailment vs `contradiction_index`.
- **Blending with rules:** triage and budget categories combine the model with the built-in rules (`Ensemble`). The zero-shot model is very good at the scam checklist but weaker at those two tasks on its own.
- **Other heads:** if a future JEV export uses a different head, only this config and `PairScoringDecisionModel` need to change.

## Google Play notes

- The app uses only the notification listener. It doesn't need `READ_SMS` or Accessibility, which are the permissions Play restricts most heavily. SMS bank alerts still arrive as notifications from your messaging app, so they're read the same way.
- Play's review requires a prominent disclosure for notification access. The Inbox onboarding card covers this, and it should be expanded into a full privacy policy before publishing.

## Development

```bash
./gradlew :core:test                       # fast JVM tests for all decision logic
JEV_MODEL_DIR=jev-model ./gradlew :core:test # + real-model tests against an exported model
./gradlew :app:assembleDebug               # needs the Android SDK
./gradlew :app:connectedDebugAndroidTest   # on-device tests (phone or emulator)
```
