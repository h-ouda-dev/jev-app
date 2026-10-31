# Heron

**Calm phone. Sharp instincts.**

Heron is an Android app that makes the small, repeated decisions your phone faces all day. It does all of this on the phone:

| | What it does |
|---|---|
| **Inbox** | Sorts every notification into **Urgent · Later · Digest · Muted**. Digest items arrive together at times you choose, and muted ones never buzz. |
| **Shield** | Gives every message an instant red-flag checklist: pressure, payment or code requests, fake banks and couriers, dodgy links. It warns you about likely scams. |
| **Money** | Reads your bank's alerts, sorts spending into categories, and flags odd charges, duplicates and subscription price rises. It can **import your history** from past bank SMS or a CSV statement. |

Notifications, messages and transactions are processed only on the phone and are never uploaded. The only network use is the one-time AI model download.

## Brand

- **Name:** Heron. A heron stands perfectly still and strikes only at what matters.
- **Colours:**
  - Lagoon teal `#17605F` (primary), deepening to `#0E3B43`
  - Saffron "beak" `#F2A93B` as the single accent
  - Mist `#F6F4EE` and Night-water `#0B1A1F` neutrals
  - Semantic tones for urgent, later, digest, muted, safe, caution and danger
- **Type:** Bricolage Grotesque for display, Plus Jakarta Sans for text. Both are bundled under the SIL OFL, and the licences are in `app/src/main/assets/licenses`.
- **Icon:** a heron mark standing in still water. It comes as an adaptive launcher icon, a themed monochrome icon, a notification icon and the splash screen.

## Get it on your phone

1. Open the latest **Build APK** run under *Actions* and download `heron-debug-apk`.
2. Install `app-full-debug.apk` on the phone. Allow "Install unknown apps" when asked.
3. The onboarding walks you through the rest: notification access, alerts, and the optional on-device AI.

On Samsung phones, set Heron's battery usage to **Unrestricted** (Settings → Apps → Heron → Battery) so One UI doesn't stop it in the background.

## Importing past transactions

Money → the history icon (or **Import past transactions** when Money is empty):

- **Bank text messages, last 1, 3, 6 or 12 months.** Heron reads the SMS inbox on the phone, keeps only bank transactions (purchases, debits, credits), and ignores every other message. OTPs and promos are rejected.
- **Bank statement (Excel .xlsx or CSV).** Download an Excel or CSV statement from online banking or a statement email and pick it. Heron detects the layout by meaning:
  - Columns: a date; a description; and either one signed amount or separate debit and credit columns. An optional type (DR/CR) or currency column is used if present.
  - Formats: most date formats, `1,234.56` and `1.234,56`, `(123.45)`, and `DR`/`CR` suffixes.

Either way:
- Each distinct merchant is categorised once, so hundreds of transactions import quickly even with the AI.
- Anomalies and subscriptions are computed in time order.
- Anything already in Heron, for example seen live as a notification, is skipped.
- When Heron connects, bank alerts already sitting in your notification shade are added too.

Email isn't read directly. Gmail access requires a restricted Google scope with a paid annual security audit. Statement CSVs cover the same need.

### Two build flavours

| Flavour | SMS import | For |
|---|---|---|
| `full` (default) | ✅ (`READ_SMS`) | Sideloading and testing, and Google Play once the SMS declaration is approved |
| `play` | ❌ | Google Play before that approval. CSV import still works. |

Google Play only grants `READ_SMS` through its Permissions Declaration Form, and "SMS-based money management" (budget tracking) is a listed exception. Submit the `play` flavour first, apply for the exception, and switch to `full` once it's approved.

## How decisions are made

Each decision is Jev-style: a **state**, a **question** and fixed **choices** go in, and a **choice**, **confidence** and **probability of every choice** come out. They go through `DecisionRouter`:

1. **Your corrections win.** Teach Heron from any inbox item, or recategorise a merchant once.
2. **The on-device model**, ModernBERT running with ONNX Runtime, blended with the built-in rules (`Ensemble`) so it never does worse than the rules alone.
3. **Built-in rules** whenever there's no model, it errors, or it isn't confident.

The scam shield asks each red-flag question separately, which is why it can explain itself even though the model can't write text. Link checks (look-alike domains, shorteners, raw IPs, punycode, risky TLDs) are deterministic.

## The on-device model

**Settings → Heron AI → Download** fetches the model from the `model-v1` GitHub release:
- It downloads in the background with progress, Wi-Fi only by default.
- Interrupted downloads resume.
- Every file is checked against its SHA-256 before use.
- After installing, Heron runs a 23-case self-test and shows the score and the speed on your phone.

**Settings → Diagnostics → Check that everything works** sends Heron three test notifications (a parcel scam, a promo and an emergency) and checks that each is handled correctly.

`.github/workflows/model.yml`:
1. Exports `tasksource/ModernBERT-base-nli` (Apache-2.0).
2. Builds fp32, fp16, int8 and int4 variants and ships the smallest one that makes the same decisions as fp32 on the self-test. That's currently fp16, about 300 MB.
3. Checks that the app's Kotlin tokenizer matches Hugging Face exactly and that the logits match Python.
4. Publishes the release only if all of that passes.

Licences, since the app is commercial (ads plus a paid tier): every shipped model and font is Apache-2.0, MIT or OFL. Non-commercial models such as jevhome-B are deliberately excluded.

## Project layout

```
core/   Pure Kotlin, unit-tested on the JVM
        model/      Decision, DecisionRouter, Ensemble, PairScoringDecisionModel, ModelConfig
        tokenizer/  Byte-level BPE tokenizer for Hugging Face tokenizer.json (ModernBERT)
        triage/     TriageEngine        scam/  ScamShield, LinkInspector
        money/      TransactionParser, Categorizer, SpendingInsights, StatementCsv, HistoryImporter
        Diagnostics.kt (self-test)      HeronPipeline.kt (one notification → all three features)
app/    Android: Kotlin, Jetpack Compose, Room, WorkManager, ONNX Runtime
        ui/theme       Heron palette, typography, shapes
        ui/components  cards, tags, rings, meters, app icons, empty states
        ui/onboarding · today · inbox · shield · money · settings
        service/       notification listener and Heron's own notifications
        ml/            model download, verification, loading, self-test
        data/          Room, settings, history import, demo data
tools/  export_model.py, eval_decisions.json
```

## Development

```bash
./gradlew :core:test                            # fast JVM tests for all decision logic
JEV_MODEL_DIR=heron-model ./gradlew :core:test  # + real-model tests against an exported model
./gradlew :app:assembleFullDebug                # APK (needs the Android SDK)
./gradlew :app:connectedFullDebugAndroidTest    # on-device tests + screenshots (phone or emulator)
```

CI runs the on-device tests on an Android 15 emulator. It also pushes screenshots of every screen, in light and dark mode with demo data, to the `ci-screenshots` branch for design review.
