# Content blur

## Where things live
| Path | What |
|---|---|
| `blur/` | Engine: settings store, ML Kit face detector, optional TFLite gender model, `ModelDownloader` (Hugging Face), renderer, Coil transformation, channel keys |
| `blur/playback/` | Live video blurring: `PlaybackBlur` (ExoPlayer effect), `FaceBlurShaderProgram` (GPU), `FaceTracker` (CPU detection + hold) |
| `presentation/blur/` | `BlurredAsyncImage`, settings, excluded channels, first-launch model setup, channel toggle chip / sheet item |
| `di/BlurModule.kt` | Hilt wiring (subscriptions → `AccountSubscriptionsProvider`) |

## Gender models: built in
Five models ship inside the APK (`app/src/main/assets/models/`, see the README there for sources). The recommended one
installs by itself on first launch, so there is no import step. Choose another in **You → Content blur → Gender model**.

| Id | Size | Output | Notes |
|---|---|---|---|
| `utk_lite_q` (default) | 0.6 MB | [1,2] | fastest |
| `utk_lite` | 1.3 MB | [1,2] | same network, float32 |
| `utk_q` | 1.3 MB | [1,2] | larger network, float16 weights |
| `utk_full` | 2.6 MB | [1,2] | most accurate of the four, slowest |
| `mobilenet_sigmoid` | 5 MB | [1,1] | single score; its value is P(**male**) (measured) |

**Each model has its own profile** (`BundledModel.femaleIndex`, `.normalization`). Choosing a model applies it, because
models disagree on which output means "female" and applying one global setting would silently swap men and women. A
"Swap male / female" switch corrects a model that reads backwards on your footage; it resets when you change model.

**Adding a model:** drop the `.tflite` in `assets/models/` and add one entry to `BundledModels.all`. Before trusting it,
run it on a few labelled faces to find which output is female. Do not guess: the sigmoid model above was the opposite
of what its shape suggested.

Start-up rules (`ModelOrigin.onStart`, unit-tested): first launch installs the default; a model you imported yourself is
never overwritten; a removal is respected; your chosen built-in model is kept across launches.

## Custom model: downloaded from Hugging Face (optional)
Only needed if you want a model that isn't built in. Tapping **Import model** (You → Content blur → Detection & custom model) downloads the
`.tflite` instead of opening a file picker.

**Where from:** type `username/repo` in **Model source** (a `huggingface.co/.../blob/...` link also works; it is
converted to the raw-file link). The repo must be public and hold a `gender.tflite` (or give a path to another
`.tflite`). No rebuild is needed to change it. If left empty, `GENDER_MODEL_REPO` in `core/Constants.kt` is used.

**Most gender models on Hugging Face are PyTorch, not `.tflite`.** The best open one found is
`DhanushSGowda/yolov8n-gender-classification` (MIT, 224x224, 2 classes, 97.7% top-1, 3 MB) but it only ships
PyTorch weights. Convert it once with `tools/make_gender_tflite.py` (instructions in the file), upload the
resulting `gender.tflite` to your own public repo, and put that repo in **Model source**.

What the app does:
1. Builds `https://huggingface.co/<repo>/resolve/main/<file>`, streams it to the cache with progress, and rejects
   HTML (a `/blob/` page) or tiny files. Files that aren't TFLite (e.g. `.pt`, `.onnx`) are rejected by their header.
2. If the built-in default source is used and `GENDER_MODEL_SHA256` is set, the file must match it. The hash is
   never applied to a repo you typed in the app.
3. `GenderClassifier.importFromFile()` validates the tensor shapes (input `[1,H,W,3]`, 1–2 outputs) **before**
   replacing the active model, so a bad download can never break a working one.
4. It's stored in `filesDir/blur/gender.tflite`, so the download only happens once.

After the first import check *Output index for 'female'* and *Input normalization* in Advanced; they depend on how
the model was trained (the converter script prints the right values for the model above).

## Wiring done in existing files
- **MainActivity:** splash held until setup state is known; `LocalBlurEngine`; app wrapped in `ModelSetupGate`.
- **PlayerController:** `setVideoEffects(...)` before each prepare (streams, local files; cleared for the audio library).
- **All images:** every `AsyncImage` in `presentation/` → `BlurredAsyncImage`, with the channel passed wherever it's known.
  The user's own profile picture is left untouched.
- **VideoCard:** long-press sheet gets "Don't blur this channel"; the raw DeArrow fallback painter is skipped while blurring.
- **ChannelScreen:** "Faces blurred / Not blurred" chip under the subscriber count.
- **You tab → Settings:** "Content blur" row → `settings/blur` → `settings/blur/excluded`.

## Playback: how it works and its limits
1. Every ~140 ms the shader copies a 384-px-wide frame to the CPU.
2. Faces are detected and classified there, off the GL thread.
3. Every frame is drawn with feathered ellipses over the tracked faces.

Detection runs about 150–300 ms behind the picture. To compensate:
- Playback uses extra padding around each face.
- A face stays hidden for 700 ms after it was last seen.
- If detection errors, the whole frame is hidden (fail-closed).

A brand-new face can still be visible for a moment right after a scene cut. Toggles apply live mid-video, except turning blur **on** while a video is already playing: that applies from the next video.
