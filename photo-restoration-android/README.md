# PhotoRevive — Android

An isolated Android project in the `photo-restoration-android` folder.

## Current status

This commit establishes a runnable app foundation with:
- Persian UI
- Android system photo picker (multi-select)
- Batch processing queue
- Offline contrast/saturation enhancement baseline
- Saving enhanced copies to `Pictures/PhotoRevive`
- Original images are never overwritten

## Important limitation

The current processor is conventional pixel adjustment. It is **not** AI super-resolution, face restoration, scratch removal, or black-and-white colorization. No claim of Gemini-level restoration is made.

The next engineering milestone is to add and validate properly licensed on-device models (for example, a super-resolution model and a separate colorization model), then benchmark memory, speed, and face-identity preservation on the target mid-range Android phone.

## Build

Open this folder in Android Studio with JDK 17 and Android SDK 35, then run the `app` configuration. The GitHub Actions workflow also attempts a debug APK build when this folder changes.

Package: `com.ahmadreza.photorevive`
