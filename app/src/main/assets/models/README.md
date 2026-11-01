# Gender models packed with the app

These files are shipped inside the APK and installed by the app itself. Nothing to download or import.
The catalog (names, descriptions, per-model settings) lives in `blur/BundledModels.kt`.

| File | Size | Output | Source |
|---|---|---|---|
| `utk_lite_q.tflite` | 0.6 MB | [1,2] | shubham0204/Age-Gender_Estimation_TF-Android (MIT), lite, float16 weights |
| `utk_lite.tflite` | 1.3 MB | [1,2] | same repo, lite, float32 |
| `utk_q.tflite` | 1.3 MB | [1,2] | same repo, standard, float16 weights |
| `utk_full.tflite` | 2.6 MB | [1,2] | same repo, standard, float32 |
| `mobilenet_sigmoid.tflite` | 5 MB | [1,1] | supplied by the project owner (MobileNetV2-style, sigmoid output) |

All take `[1,128,128,3]` float32. The four `utk_*` models were trained on UTKFace and scale pixels by 1/255;
output index 1 is female (from that repo's own demo app).

To add a model: drop the `.tflite` here and add one entry to `BundledModels.all`. Files in this folder are
stored uncompressed in the APK (`noCompress "tflite"` in app/build.gradle.kts).
