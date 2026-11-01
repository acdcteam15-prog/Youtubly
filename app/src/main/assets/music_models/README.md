# Music remover models

Converted from Deezer Spleeter 2stems (MIT, https://github.com/deezer/spleeter, release v1.4.0
`2stems.tar.gz`). Rebuilt layer by layer in Keras and checked against the original graph
(max mask difference 1.8e-5), then exported with `tools/music/convert.py`.

| File | Networks | Weights | Size |
|---|---|---|---|
| music_light.tflite | vocal U-Net only (sigmoid mask) | int8 dynamic range | 9.9 MB |
| music_balanced.tflite | vocal + accompaniment, ratio mask | int8 dynamic range | 19.7 MB |
| music_best.tflite | vocal + accompaniment, ratio mask | float16 | 39.3 MB |

All take input `[1, 192, 1024, 2]` float32 (STFT magnitude, 4096/1024 Hann, stereo) and return the
vocal mask with the same shape. Must stay uncompressed in the APK (`noCompress += "tflite"`).
