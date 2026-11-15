# PhotoRevive implementation contract

## User-approved product decisions
- Platform: Android.
- Inputs: both color and black-and-white photographs, including severely degraded images.
- Quality target: balanced — strong visible improvement while preserving facial identity as much as technically possible.
- First target device: Samsung Galaxy A24, 8 GB RAM, MediaTek Helio G99.
- Original files must never be overwritten.
- Batch selection and processing are required.
- Before/after comparison uses a draggable vertical divider.

## UX flow
1. Home screen: choose photos, open recent results, and view queued work.
2. Import: multi-select images; show thumbnails, count, and remove controls.
3. Restoration setup: automatic mode by default; advanced controls are optional.
4. Processing: queue-based, one image at a time, with stage/progress status and cancel support.
5. Review: draggable before/after comparison, zoom, and save/share.
6. Batch review: each image has an independent result state; failed items can be retried without reprocessing successful items.

## Modes
- Auto Restore: analyze the image and run only relevant stages.
- Colorize: available only for grayscale/black-and-white input.
- Enhance Color Photo: denoise, deblur where supported, and upscale.
- Face protection: default ON; avoid aggressive face synthesis. A separate face-enhancement control must explain that generated details can differ from the original.

## Quality rules
- Preserve original luminance during colorization where the selected model allows it.
- Preserve geometry and facial proportions; do not beautify, reshape, or change expression.
- Keep a conservative face-restoration default.
- Provide strength controls with a neutral/off setting.
- Never describe hallucinated details as recovered facts.
- When the source lacks enough information, prefer a natural, slightly soft result to a sharply invented face.

## Engineering milestones
- M0 — Existing runnable Android shell and conventional enhancement baseline.
- M1 — Robust image I/O, EXIF orientation, memory-safe decoding, queue state, cancellation, and export validation.
- M2 — Integrate and benchmark a general restoration/upscaling model on the A24 CPU.
- M3 — Integrate grayscale colorization and validate color plausibility.
- M4 — Add face detection/protection and evaluate identity drift before enabling optional face reconstruction.
- M5 — Full-resolution tiled processing, batch QA, performance tuning, and release build.

## Acceptance tests
- Portrait identity: compare eye spacing, nose width, mouth shape, jaw contour, hairline, and expression before/after; reject aggressive outputs.
- Colorization: no color applied to color originals in Auto mode; no strong color bleeding across edges.
- Detail: output dimensions and file integrity verified; no silent low-resolution export.
- Memory: large images and multi-image queues do not crash the app on the A24.
- Batch: a failed image does not stop the rest of the queue.
- Safety: original file checksum remains unchanged after processing.
