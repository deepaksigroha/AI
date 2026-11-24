# Test documentation – ImageClean Pro

## Automated tests

### JVM unit tests (`app/src/test`) – `./gradlew testDebugUnitTest`
`CoreEngineTest` builds synthetic files byte-by-byte and checks:

| Test | Verifies |
|---|---|
| detectsFormats | JPEG/PNG/WebP/HEIF magic-byte detection |
| scansJpegSegments | EXIF/XMP/IPTC/COM discovery; IPTC creator & copyright |
| parsesXmp | creator tool, DigitalSourceType, dc:creator, dc:rights |
| matchesSourceTypes | IPTC vocabulary matching; generator names; no false positives for Photoshop/Canon |
| cborRoundTrip | CBOR encoder/decoder |
| aiScannerUsesXmpDisclosure | AI-generated verdict from XMP, evidence classed as metadata |
| aiScannerUndeterminedWithoutMetadata | metadata-free image → "Unable to determine" |
| aiScannerReadsDiffusionPngParameters | Stable Diffusion PNG `parameters` detection |
| c2paDataHashValidatesAndDetectsTampering | JUMBF parsing, assertion hashes, data-hash PASS, tamper → FAIL, missing certificate → INVALID, AI action extraction |
| jpegRewriterRemovesSegmentsLosslessly | segment removal keeps entropy-coded data byte-identical |
| jpegRewriterNeverDropsC2pa | C2PA APP11 can never be removed |
| disclosureSurvivesXmpReplacement | AI disclosure written/merged into XMP |
| pngRewriterDropsTextAndKeepsValidCrc | PNG text removal with valid CRCs |
| instagramCheckerNeverClaimsCertainty | STRONG/LOW categories, disclaimer present |
| iccDescriptionReadsDescTag | ICC profile name |

### Instrumentation tests (`app/src/androidTest`) – `./gradlew connectedDebugAndroidTest`
- `LaunchTest` – app launches; branding and "Developed by Deepak Sigroha" are displayed.
- `CleanerDeviceTest` – creates a JPEG with GPS/make/model/date/orientation via ExifInterface, cleans it, and verifies GPS & device data are gone, orientation kept, no re-encoding.

CI runs the unit tests, builds debug + signed release APKs, verifies the signature with `apksigner`, and runs the instrumentation tests on an API 30 emulator.

## Manual test matrix

| Sample | Expected |
|---|---|
| Phone photo with location | GPS shown in red; cleaner removes it; report "GPS: Present → Not present" |
| Adobe Firefly / ChatGPT image with Content Credentials | C2PA present; AI-generated; state verified if signer trusted, otherwise unverifiable |
| Same image after cropping elsewhere | C2PA invalid (binding fails) or none |
| Midjourney / SD PNG with parameters | AI-generated (metadata-based) |
| Photoshop-edited JPEG | XMP history shown; no AI verdict unless generative fill recorded |
| Image with visible corner logo | Region highlighted |
| Screenshot without metadata | "Unable to determine" |
| HEIC from iPhone/Samsung | Inspection works; cleaned copy is JPEG with warning |
