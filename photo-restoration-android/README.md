# PhotoRevive — Android

A dedicated Android photo-restoration app, developed on the isolated `photo-restoration-android` branch. The main repository project is not modified.

## Product goal

Restore both very low-quality color photographs and black-and-white photographs, with a balanced priority: maximize visible quality while preserving the subject's facial identity as much as the source image allows.

The app is targeted first at the Samsung Galaxy A24 (8 GB RAM, MediaTek Helio G99). Do not assume the phone's full advertised RAM is available to the app. Prefer on-device processing and never silently upload personal photos.

## Current implementation status

The current runnable foundation includes:
- Persian Android UI
- Multi-photo selection
- Sequential batch queue
- Offline contrast/saturation adjustment baseline
- Saving enhanced copies to `Pictures/PhotoRevive`
- Original images are never overwritten
- Draggable before/after comparison divider
- ONNX Runtime Android dependency

**Important:** the current processor is conventional pixel adjustment. It is not AI super-resolution, true black-and-white colorization, scratch repair, or face restoration. Those capabilities are not marked complete until model files, preprocessing/postprocessing, and real-device tests are integrated.

## Restoration design

The processing pipeline is separated into independent stages:
1. Input analysis: image dimensions, color/grayscale detection, orientation and memory-safe decode.
2. General restoration: denoise and compression-artifact reduction, only when needed.
3. Super-resolution: upscale and reconstruct plausible detail without claiming that unknown details are recovered exactly.
4. Colorization: infer chroma for grayscale images while retaining the source luminance as much as possible.
5. Face-aware protection: detect faces and avoid aggressive face synthesis by default. Any optional face reconstruction must be strength-controlled and compared against the source.
6. Export: preserve the original, write a new high-quality result, and keep a clear before/after comparison.

Face identity cannot be guaranteed by an AI model. Severe blur or missing pixels may leave the original facial details unknowable. The app must prefer a less dramatic but faithful result over an attractive, invented face.

## Model integration strategy

- ONNX Runtime is the current inference runtime foundation.
- Real-ESRGAN General x4v3 is a candidate for general upscaling/denoising; its mobile-optimized model is small, but Qualcomm benchmark figures do not predict performance on the A24's MediaTek chipset.
- DDColor-tiny is a candidate for black-and-white colorization. The model's published mobile support does not include the A24, so CPU execution, memory use, speed, and output quality must be benchmarked before enabling it by default.
- Face-restoration models are deferred until their licenses, model size, identity drift, and A24 performance are evaluated. CodeFormer is not assumed to be suitable for redistribution because its published model license is non-commercial.

No model is downloaded into the APK until its source, exact license, model checksum, input/output tensor contract, and attribution are recorded.

## Performance and safety requirements

- Process one full-resolution image at a time; batch mode queues images rather than loading all full-size bitmaps into memory.
- Use tiled inference for models that support it; never silently downscale the final export to fit memory.
- Show progress and allow cancellation between stages.
- Keep the original file untouched and save results as new files.
- If a model is unavailable or processing fails, show a clear error instead of silently substituting a fake AI result.
- Do not claim face identity preservation as guaranteed. Validate on a varied photo set and on the actual A24.

## Build

Open this folder in Android Studio with JDK 17 and Android SDK 35, or use the repository's `.github/workflows/photo-restoration-android.yml` workflow to build a debug APK.

Package: `com.ahmadreza.photorevive`
