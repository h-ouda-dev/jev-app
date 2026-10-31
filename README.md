# Jev: on-device decisions for your notifications

Jev is an Android app that makes small, repeated decisions about everything your phone receives, entirely on the phone. It ships three features that share one data source, Android's notification listener, and one on-device decision model:

| Feature | Decision | Choices |
|---|---|---|
| **Notification triage** | "This notification is …" | urgent · can wait · digest · silence |
| **Scam shield** | 8 yes/no red-flag questions per message | yes · no, plus offline link analysis |
| **Private money tracker** | "This purchase is for …" | 11 budget categories, plus unusual-charge and subscription detection |

The app has **no `INTERNET` permission**, so nothing can leave the device.

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

## Adding the on-device model

Jev works with built-in rules out of the box. To switch on the model:

```bash
pip install -r tools/requirements.txt
# A ModernBERT-base NLI cross-encoder (149M params) works as a zero-shot decision model today:
python tools/export_jev_onnx.py --model tasksource/ModernBERT-base-nli --out jev-model
# Or try a JEV checkpoint, if it exposes a sequence-classification head:
python tools/export_jev_onnx.py --model tasksource/modernbert-tasksource-jev --out jev-model --trust-remote-code
```

Copy `jev-model/model.onnx`, `tokenizer.json` and `jev_config.json` to the phone. Then use **Settings → Import model files** and select all three. The int8-quantised model is about 150 MB. Settings shows the measured milliseconds per decision once it loads.

`jev_config.json` controls how the model is prompted:

```json
{
  "mode": "pair",
  "premise_template": "{state}",
  "hypothesis_template": "{question} {choice}.",
  "score_index": 0,
  "use_log_prob": true,
  "max_length": 256,
  "temperature": 1.0
}
```

Each choice is scored as a (premise, hypothesis) pair. The logit at `score_index` (for example "entailment") is that choice's score, and a softmax across choices gives the decision. If a future JEV export uses a different head, only this config and `PairScoringDecisionModel` need to change.

## Google Play notes

- The app uses only the notification listener. It doesn't need `READ_SMS` or Accessibility, which are the permissions Play restricts most heavily. SMS bank alerts still arrive as notifications from your messaging app, so they're read the same way.
- Play's review requires a prominent disclosure for notification access. The Inbox onboarding card covers this, and it should be expanded into a full privacy policy before publishing.

## Development

```bash
./gradlew :core:test          # fast JVM tests for all decision logic
./gradlew :app:assembleDebug  # needs the Android SDK
```
