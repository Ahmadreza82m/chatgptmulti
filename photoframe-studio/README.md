# PhotoFrame Studio

Android batch image layout tool for print preparation.

## Current implementation
- Native Android app built with Kotlin and Jetpack Compose.
- Local folder selection using Android Storage Access Framework.
- Recursive discovery of JPG, JPEG, PNG and WebP images.
- Independent top, bottom, left and right safe inset controls.
- Additional edge/background bleed control.
- Per-image processing with progress and success/failure counters.
- Original files are read-only; output is written as PNG copies.
- GitHub Actions workflow builds debug and release APK artifacts.

## Important processing notes
This initial implementation uses a 5:7 portrait output canvas (1400 x 1960 px) and places each source image proportionally inside the selected safe area. It creates a full-canvas background from a scaled crop of the source image, then draws the original image over it. It does not stretch the foreground subject.

The reference image is currently selected as a project reference input but automatic guide-line detection and calibration against the reference image are not yet implemented. Margin values are manually controlled as percentages. A visual review with real sample images is required before processing a full archive.

## Build
Open this directory as an Android Gradle project, or run:
`gradle --no-daemon assembleDebug`

APK output:
`app/build/outputs/apk/debug/app-debug.apk`
