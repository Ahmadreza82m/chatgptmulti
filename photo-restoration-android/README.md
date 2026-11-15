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

## Target device: Samsung Galaxy A24 (8 GB RAM)

The implementation target is the Galaxy A24 with MediaTek Helio G99 and Mali-G57 MC2. The app must not assume that the phone's advertised 8 GB RAM is fully available to one process. Neural processing will start with the ONNX Runtime Android CPU path and conservative thread/memory settings; NNAPI acceleration will only be enabled after a real-device compatibility test. Large photos must be processed in tiles or downscaled only for preview, not silently reduced in the final saved output.

The Android ONNX Runtime dependency is now included as the inference runtime foundation. This alone does not provide AI enhancement: a compatible, licensed model file and its exact input/output preprocessing are still required. The next implementation milestone is to integrate and benchmark a general-photo super-resolution model on the A24, then add a separate colorization model. Face-restoration models will not be enabled by default because they can invent facial details and alter identity. No face-identity guarantee will be claimed without real-device and image-set validation.

## Build

Open this folder in Android Studio with JDK 17 and Android SDK 35, then run the `app` configuration. The GitHub Actions workflow also attempts a debug APK build when this folder changes.

Package: `com.ahmadreza.photorevive`
