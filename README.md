# ImageClean Pro – AI & Metadata Inspector

**Developed by Deepak Sigroha** · Android 9+ · Kotlin · Jetpack Compose · Material 3 · GPL-3.0

ImageClean Pro is an open-source Android app that shows you everything hidden inside a photo — EXIF, GPS, IPTC, XMP, ICC and C2PA Content Credentials — and lets you export privacy-cleaned copies without ever touching the original. It also explains AI-provenance information and why platforms such as Instagram may show an "AI info" label.

> ImageClean Pro **does not** and **cannot** guarantee removal of AI labels on Instagram or any other platform. Platforms use their own detectors and invisible watermarks. The app never forges, removes or falsifies signed provenance, and it keeps AI disclosures in every file it writes.

## Features

| Module | What it does |
|---|---|
| A · Metadata Inspector | EXIF (camera, date, GPS, exposure, aperture, ISO, focal length, orientation, software), IPTC (creator, copyright, description, keywords), XMP (creator tool, editing history, AI properties, provenance references), ICC profile name, embedded thumbnail, file structure. JPEG, PNG, WebP, HEIC/HEIF (AVIF read-only). |
| B · EXIF Privacy Manager | Select individual fields, groups or all privacy-sensitive fields; preview changes; export a new copy. Orientation/dimension tags are protected. |
| C · C2PA Inspector | Parses the JUMBF manifest store; validates the COSE_Sign1 claim signature (ES256/384/512, PS256/384/512, Ed25519), certificate-chain integrity and validity, every assertion hash, and the `c2pa.hash.data` hard binding against the image bytes. Four states: verified / invalid / unverifiable / none. |
| D · AI Provenance Scanner | Combines C2PA actions, IPTC Digital Source Type, XMP properties, EXIF Software, known generator signatures (DALL·E, Midjourney, Stable Diffusion, Firefly, Imagen/Gemini, FLUX, ComfyUI, A1111…) and PNG generation parameters. Separates cryptographic, signed-but-unverified and plain-metadata evidence. |
| E · Visible Watermark Detection | Offline heuristic scan for corner/edge logos and stamped text, the DALL·E colour bar and sparkle-style AI marks; highlighted overlay and magnifier. For images you own: save a cropped copy without an edge band. |
| F · Privacy Cleaning workflow | Select → inspect → choose → preview → export copy → report. JPEG/PNG edits are lossless (pixels untouched). |
| G · Instagram AI Compatibility Checker | Explains indicators Meta is known to read (C2PA, IPTC DST), privacy concerns, format issues and recommended settings. Three non-predictive categories. |
| H · Authenticity & Modification Report | Before/after report for every operation; export as PDF or JSON; local modification history. |
| I · Social Media Export | Presets for Instagram Feed/Stories/Reels, Facebook, WhatsApp, LinkedIn, X + custom presets; crop position, resolution, JPEG/PNG, quality. Creator/copyright and AI disclosures are carried over. |
| J · Batch Analysis | Up to 50 images in the background (WorkManager) with progress and cancel; optional batch cleaning; individual and consolidated reports. |
| K · Transparency Dashboard | Local statistics of everything inspected and processed. |

## Privacy

No `INTERNET` permission, no ads, no analytics, no tracking. All processing is on-device. History is stored in private app storage and excluded from backups. See [PRIVACY.md](PRIVACY.md).

## Installing the APK

1. Download `ImageCleanPro-release.apk` (from the GitHub Actions artifact or a Release).
2. On your phone, open the file and allow "Install unknown apps" for your browser/file manager when prompted.
3. Tap **Install**. The debug APK (`ImageCleanPro-debug.apk`) can be installed side-by-side (package `com.deepaksigroha.imageclean.debug`).

## Building

Requirements: JDK 17, Android SDK 35.

```bash
./gradlew testDebugUnitTest          # JVM unit tests of the metadata engine
./gradlew assembleDebug              # app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedDebugAndroidTest  # instrumentation tests on a device/emulator
```

Signed release: set `ICP_KEYSTORE_FILE`, `ICP_KEYSTORE_PASSWORD`, `ICP_KEY_ALIAS`, `ICP_KEY_PASSWORD`, then `./gradlew assembleRelease`. The CI workflow (`.github/workflows/android.yml`) reads these from repository secrets (`ICP_KEYSTORE_B64` etc.), builds both APKs, verifies the signature and runs the instrumentation tests on an emulator.

## Architecture

```
core/        pure Kotlin, unit-tested on the JVM
  container/   JPEG segments, PNG chunks, RIFF/WebP, ISO-BMFF scanner
  meta/        IPTC-IIM and XMP parsers, AI vocabulary
  c2pa/        CBOR, JUMBF, C2PA reader + COSE/X.509 verification
  ai/          AI provenance scanner, Instagram checker
  clean/       lossless JPEG/PNG rewriters, XMP disclosure builder
data/        Android layer: ExifInterface, ImageDecoder, cleaner, exporter, watermark detector,
             reports (PdfDocument/org.json), Room database, WorkManager batch worker
ui/          Jetpack Compose screens (MVVM, single activity, Navigation Compose)
```

Dependencies (all open source, Apache-2.0): AndroidX Core/Activity/Lifecycle/Navigation, Jetpack Compose + Material 3, ExifInterface, Room, WorkManager, Core SplashScreen, Kotlin coroutines. Dependency injection is a small manual container instead of Hilt to keep the build simple.

## Known limitations (documented, not simulated)

- **C2PA trust**: signers are trusted only if they chain to the Android CA store or to roots you import in Settings (e.g. the published C2PA trust list). No network lookups; RFC 3161 timestamps and OCSP revocation are not checked; BMFF/collection hash bindings (HEIF/video) are reported as *unverifiable*. Only the active manifest is validated; ingredient manifests are shown as history.
- **Any edit invalidates C2PA binding**: changing bytes of a signed file makes its content-binding fail. The manifest is kept so its claims stay visible, and the report says so.
- **HEIC/AVIF** cannot be rewritten in place on Android, so cleaned copies are high-quality JPEGs (q95); C2PA cannot be carried over and the AI disclosure is written as IPTC metadata instead.
- **WebP**: EXIF tags can be removed; XMP and thumbnail removal are not supported.
- **Photo Picker** may redact GPS; "Open file" reads the original.
- **Watermark detection** is heuristic (no ML model): faint, tiled or invisible watermarks (e.g. SynthID) are not detected.
- Absence of AI metadata never proves an image is human-made.

## Licence

GNU General Public License v3.0 — see [LICENSE](LICENSE). © 2026 Deepak Sigroha.
