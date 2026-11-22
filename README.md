# NEXUS AI

**Your knowledge. Your intelligence. Your control.**

Privacy-first AI-powered personal knowledge management system built for the **Firebase #BuildHybridAI Hackathon** using **Firebase AI Logic Hybrid Inference**, **Firebase App Check**, **Cloud Firestore**, and **Google Sign-In**.

---

## Architecture Overview

NEXUS AI is engineered around a strict **zero-trust, truthful hybrid inference** architecture:

1. **Firebase AI Logic Hybrid Inference (`HybridAiService`)**
   - Uses official `Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(...)` with `OnDeviceConfig(mode = InferenceMode.*)` — zero client-side Gemini API keys.
   - Supports all four hybrid routing policies:
     - `PREFER_ON_DEVICE`: Used for private, short-form note tools (Summarization, Structured JSON Classification & Auto-Tagging, Keyword Extraction, Task Extraction, Rewriting) with automatic cloud fallback (`gemini-3.5-flash-lite`) on unsupported devices.
     - `ONLY_ON_DEVICE`: Strict local mode; transparently reports unavailability when Android AICore / Gemini Nano is not provisioned.
     - `PREFER_IN_CLOUD` & `ONLY_IN_CLOUD`: Used for complex multi-document Knowledge Vault Q&A, research reports, and streaming multi-turn synthesis (`gemini-3.8-flash`).
   - **Truthful Execution-Source Reporting**: Captures the actual `GenerateContentResponse.inferenceSource` (`InferenceSource.ON_DEVICE` vs `InferenceSource.IN_CLOUD`) reported by the Firebase AI Logic SDK at runtime — never inferred from network status.
2. **Firebase App Check (`AppCheckConfig`)**
   - Initialized in `MainActivity.onCreate()` before Compose UI, ViewModels, or Firebase calls.
   - Debug builds use `DebugAppCheckProviderFactory` with synchronous `SharedPreferences.commit()` persistence of `FIREBASE_APPCHECK_DEBUG_TOKEN`.
   - Release builds use `RecaptchaAppCheckProviderFactory` backed by `R.string.recaptcha_site_key`.
3. **Cloud Firestore & Offline-First Persistence (`VaultRepository`)**
   - Connects exclusively to the provisioned named database (`R.string.firestore_database_id`).
   - Enables `PersistentCacheSettings` with unlimited local disk cache so downloaded notes remain readable and editable offline and sync automatically on reconnect.
   - Zero-trust per-user data model under `/users/{userId}/...`:
     - `/users/{userId}`: Profile & persisted default `HybridInferenceMode`.
     - `/users/{userId}/vault_items/{itemId}`: Notes, SAF-imported documents, and saved web links.
     - `/users/{userId}/ai_logs/{logId}`: Immutable privacy audit trail recording every inference task, requested mode, actual SDK `inferenceSource`, latency, and whether data left the device.
4. **Google Sign-In Authentication (`AuthManager`)**
   - Implemented exclusively with Jetpack `CredentialManager` (`GetSignInWithGoogleOption` for interactive sign-in, `GetGoogleIdOption` for silent startup session restoration) and `NonCancellable` sign-out cleanup.
5. **Resilient Streaming Gemini-First Document Intelligence, OCR & Full-Document RAG (`OoxmlDocumentParsers`, `DocumentExtractionEngine`, `DocumentCheckpointStore`, `ResilientAiExecutor`, `DocumentIntelligenceService`)**
   - **Magic-Byte MIME Detection & Real Content Extraction (Never Filename-Based)**:
     - **DOCX**: Hardened SAX extraction of `word/document.xml` headings, paragraphs, Markdown tables, headers/footers/footnotes, **plus** embedded raster images (`word/media/`) routed through Gemini Vision OCR, with honest disclosure of legacy vector formats (EMF/WMF).
     - **PPTX**: Numeric slide ordering (`ppt/slides/slideN.xml`), tables, speaker notes (`ppt/notesSlides/notesSlideN.xml`), and embedded slide images (`ppt/media/`).
     - **XLSX**: All worksheets, shared strings, cached values, numeric statistics, **and** Excel formulas (`<f>`) annotated verbatim alongside evaluated values (`value [=FORMULA]`).
     - **PDF & Scanned PDFs**: Verified with Android `PdfRenderer` and extracted page by page via streaming Firebase AI Logic multimodal `inlineData` / batched page Vision OCR (`gemini-3.8-flash`, `ONLY_IN_CLOUD`) preserving `[Page N]` headings and Markdown tables.
     - **CSV, TXT, Markdown & Images**: Complete quote-aware CSV profiling, full UTF-8 sectioning, and streaming Gemini Vision OCR for images/charts/screenshots.
   - **Timeout-Proof Streaming Architecture & Per-Chunk Checkpoints**:
     - Bounded `6 KB` chunks + streaming `generateContentStream()` on every map, OCR, reduce, and final consolidation pass.
     - Instant atomic disk checkpointing (`<itemId>_checkpoint.json`) after every completed or failed chunk so timeouts, cancellations, or network drops **never** lose prior progress.
     - Exponential backoff retries (`429`, `5xx`, timeouts, network drops), automatic HTTP `413` chunk splitting, hierarchical digest reduction for very large files, duplicate-job protection, instant Cancel, Resume Analysis, and Retry Failed Sections.
     - **Strict Coverage Honesty**: Never reports `COMPLETE` if any chunk, page, or embedded visual failed or was skipped.
   - **Dedicated Analysis Workspace (`DocumentAnalysisScreen`)**: Displays live streaming output, upload/extraction/chunk progress metrics, Cancel/Resume/Retry Failed controls, Executive Summary, Detailed Content Explanation, Important Conclusions, Main Topics, Verified Source Facts (strictly separated from AI Interpretations), Definitions, Entities/Dates/Figures, Tables & Data Insights, Action Items, Source References, Ask This Document, Reanalyse, and Markdown Export.
   - **Google Account & Quota Honesty**: Google Sign-In isolates private Firestore data per user; cloud Gemini inference is authorized via Firebase AI Logic + Firebase App Check (ordinary Google Sign-In does not supply personal Gemini API quota, and the app never asks for personal API keys).
   - **Firestore Sync**: Structured analyses sync using native Firestore Maps & Lists under `/users/{userId}/document_analyses/{itemId}`.
6. **Adaptive Obsidian & Electric Violet UI**
   - Matches the NEXUS AI dark obsidian (`#070914`), charcoal surface (`#101427`), electric violet (`#7C5CFF`), and cyan (`#22D3EE`) design system.
   - Adaptive layout: Persistent left Sidebar on tablet/expanded widths (`>= 840dp`) and edge-to-edge bottom `NavigationBar` on phones.

---

## Setup & Configuration

### Prerequisites
- Android Studio Ladybug+ / JDK 17+
- Firebase project with **Firebase Authentication (Google Sign-In)**, **Cloud Firestore**, **Firebase App Check**, and **Firebase AI Logic** enabled.
- Firebase configuration files (`app/google-services.json` and `app/src/main/res/values/firebase_applet_config.xml`) generated by Firebase provisioning. These are ignored by Git and must not be committed to a public fork. Set up your own Firebase project and add its configuration files locally before building; without them, Firebase-backed features will not work.

### Build & Run Locally
```bash
# Compile Kotlin & Android resources
gradle :app:assembleDebug

# Run local JVM & Robolectric tests (25 automated tests)
gradle :app:testDebugUnitTest
```

### Verify Firestore Security Rules Locally
```bash
# Start single-database Firestore emulator on port 8085
firebase emulators:start --only firestore --project demo-no-project

# Execute Kotlin-mirrored security rule tests
FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firestore.test.js
```

### Enabling Optional Firebase Products (Manual Console Steps)
If you wish to extend this Android client with binary file blob hosting or remote feature flags:
- **Firebase Storage**: Enable Storage in the Firebase Console and deploy per-user storage rules matching `/users/{userId}/**`.
- **Firebase Remote Config**: Enable Remote Config in the Firebase Console to dynamically toggle default model names or prompts.
- **Physical Gemini Nano Testing**: Install the APK on an Android 14+ device with **Android AICore** and Gemini Nano provisioned (e.g., Pixel 9 / supported flagship) to observe live `InferenceSource.ON_DEVICE` executions.

---

## Testing Checklist

| Verification Area | Status | Notes |
|---|---|---|
| Kotlin & Android APK Build (`compileDebugKotlin` / `assembleDebug`) | Automated | Verified via Gradle build |
| Firestore Security Rules (`firestore.test.js` — 6/6 tests) | Automated | Verified against local Firestore Emulator (port 8085) & deployed via `DeployRules` |
| Resilient Document Pipeline Suite (`ResilientDocumentPipelineTest` — 10 scenarios) | Automated | Verified short/large DOCX, DOCX tables/headers/footnotes, DOCX embedded images + EMF disclosure, PPTX slides/notes/media, scanned & long PDF, XLSX formulas, corrupted/unsupported files, network drop + checkpoint resume, timeout/429/413/cancellation honesty |
| Core Unit & Robolectric Tests (`NexusAiUnitTest`, `ExampleRobolectricTest`, `ExampleUnitTest` — 15 tests) | Automated | Verified domain models, CSV/MD/DOCX/XLSX parsers, RAG chunk ranking, Markdown export, Firestore deserialization, capability probe |
| Live Google Sign-In & Cloud AI Logic Streaming | Device / Emulator | Requires running APK on Android emulator/device with Google Play Services & App Check debug token |
| Physical On-Device Gemini Nano (`InferenceSource.ON_DEVICE`) | Physical Device Pending | Requires physical AICore-enabled Android device (emulators transparently fall back to `IN_CLOUD`) |

---

## 3-Minute Hackathon Demo Script

1. **0:00 – 0:30 | Sign-In & Mission**: Launch NEXUS AI on Android. Show the obsidian & electric-violet welcome screen and sign in via Jetpack Credential Manager Google Sign-In. Highlight the tagline: *"Your knowledge. Your intelligence. Your control."*
2. **0:30 – 1:15 | Knowledge Vault & Zero-Permission Import**: On the Dashboard, tap **New Note** to capture hackathon architecture ideas, tap **Save Link** to bookmark Firebase AI Logic docs, and tap **Upload File** to import a local markdown/text document via Android SAF without broad storage permissions. Show real-time Firestore stats and sparklines updating immediately.
3. **1:15 – 2:00 | Local-Preferred Hybrid AI (`PREFER_ON_DEVICE`)**: Open a note and tap **Summarize**, **Classify & Tag**, and **Extract Tasks** (structured JSON via `Schema.obj`). Point out the truthful **Inference Source Badge** showing the exact execution source (`ON_DEVICE` on AICore devices or `IN_CLOUD` fallback on emulators) plus measured latency in milliseconds.
4. **2:00 – 2:35 | Cloud Intelligence & Multi-Doc Streaming**: Switch to **AI Assistant**, select multiple notes as Vault context, choose **Prefer Cloud (`gemini-3.8-flash`)**, and tap **Generate Research Report**. Show real-time streaming synthesis citing the user's notes.
5. **2:35 – 3:00 | Privacy Center & Offline Control**: Open **Privacy Center** to inspect the live Local vs. Cloud execution ratio, real Android AICore hardware probe, App Check attestation status, immutable Firestore AI processing audit log, and one-tap data deletion controls.

---

## Hackathon Submission Summary

**NEXUS AI** solves a core tension in personal AI tools: users want frontier intelligence across their private notes and documents without surrendering visibility or control over where their data runs. Built natively in Kotlin & Jetpack Compose with **Firebase AI Logic Hybrid Inference**, **Firebase App Check**, and zero-trust **Cloud Firestore**, NEXUS AI routes private short-form workflows (`PREFER_ON_DEVICE`) to on-device Gemini Nano whenever supported, seamlessly escalates complex multi-document synthesis to cloud Gemini models (`gemini-3.8-flash`), and logs the SDK's real `inferenceSource` into an interactive Privacy Center so users always know exactly what stayed on-device and what went to the cloud.

---

## License

Licensed under the **Apache License, Version 2.0**. See [LICENSE](./LICENSE) for full details.
