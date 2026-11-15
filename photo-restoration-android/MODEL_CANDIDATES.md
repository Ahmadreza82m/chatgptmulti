# Model candidates and licensing notes

This file records candidate sources only. A model is not integrated until its actual file, checksum, tensor contract, preprocessing, postprocessing, and device benchmark are verified.

| Task | Candidate | Published model/license information | Decision |
|---|---|---|---|
| General upscaling / denoise | Real-ESRGAN General x4v3 | Upstream Real-ESRGAN repository uses BSD-3-Clause. Qualcomm publishes a mobile-oriented General x4v3 model of about 4.66 MB, but its benchmark devices are Snapdragon devices. | Candidate for A24 CPU benchmarking; do not assume Qualcomm acceleration applies to MediaTek Helio G99. |
| Black-and-white colorization | DDColor-tiny | Qualcomm AI Hub lists Apache-2.0 and a 215 MB float checkpoint / 54.8 MB w8a8 variant; the page currently says the model is not supported on any mobile chipset. | Candidate only; test CPU speed and memory on the A24 before enabling. |
| Face restoration | CodeFormer | The model repository lists S-Lab License 1.0, non-commercial use only. | Not selected for app redistribution until licensing and identity drift are resolved. |

## Source references
- Real-ESRGAN upstream: https://github.com/xinntao/Real-ESRGAN
- Qualcomm Real-ESRGAN General x4v3: https://huggingface.co/qualcomm/Real-ESRGAN-General-x4v3
- Qualcomm DDColor: https://aihub.qualcomm.com/mobile/models/ddcolor
- DDColor Android ONNX conversion example: https://huggingface.co/pelixpng/pastvu-colorizer
- Model collection and per-model license notes: https://huggingface.co/RekluzLabs/MakoKolor

## Important
Published benchmarks on Snapdragon phones cannot be extrapolated to the Samsung Galaxy A24. The A24 uses a MediaTek Helio G99; initial inference must be measured on CPU. A model may be accurate but too slow or memory-heavy for practical batch use.
