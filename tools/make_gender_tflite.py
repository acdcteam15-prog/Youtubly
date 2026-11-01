#!/usr/bin/env python3
"""
Builds gender.tflite for Youtubly from a public Hugging Face model, then tells you exactly what to
set in the app. Run it on a PC (not the phone). Takes a few minutes; installs are large (~1-2 GB).

    python -m venv gv && source gv/bin/activate        # Windows: gv\\Scripts\\activate
    pip install ultralytics huggingface_hub
    python tools/make_gender_tflite.py

Source model: DhanushSGowda/yolov8n-gender-classification (MIT, YOLOv8n-cls, 224x224, 2 classes,
97.7% top-1 on its validation set, 3 MB). It only ships PyTorch weights, hence this conversion.

SECURITY: a .pt file is a Python pickle, and loading one runs code. HF's scan of this file lists only
standard torch / torchvision / ultralytics classes, but use a throwaway virtualenv anyway.

NOTE: written without being able to run it end to end. If a step fails, the message says which.
"""
import hashlib
import shutil
import sys
from pathlib import Path

REPO = "DhanushSGowda/yolov8n-gender-classification"
FILE = "best_Gender_classification.pt"
OUT = Path("gender.tflite")


def main() -> None:
    try:
        from huggingface_hub import hf_hub_download
        from ultralytics import YOLO
    except ImportError:
        sys.exit("Missing packages. Run:  pip install ultralytics huggingface_hub")

    print(f"1/4  Downloading {REPO}/{FILE} ...")
    cached = hf_hub_download(REPO, FILE)
    local = Path(FILE)
    shutil.copy(cached, local)          # export writes next to the .pt, so work in this folder

    print("2/4  Loading and exporting to TensorFlow Lite (this installs extra packages the first time) ...")
    model = YOLO(str(local))
    names = dict(model.names)
    print("     Class order in the model:", names)
    model.export(format="tflite", imgsz=224, half=False, int8=False)

    produced = sorted(Path(f"{local.stem}_saved_model").glob("*float32.tflite"))
    if not produced:
        sys.exit("Export finished but no *_float32.tflite was found. Look inside "
                 f"{local.stem}_saved_model/ for the .tflite and rename it to {OUT}.")
    shutil.copy(produced[0], OUT)

    print("3/4  Checking the result ...")
    try:
        import numpy as np
        import tensorflow as tf
        interp = tf.lite.Interpreter(model_path=str(OUT))
        interp.allocate_tensors()
        inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
        print("     input :", inp["shape"], inp["dtype"].__name__)
        print("     output:", out["shape"], out["dtype"].__name__)
        interp.set_tensor(inp["index"], np.random.rand(*inp["shape"]).astype(inp["dtype"]))
        interp.invoke()
        probs = interp.get_tensor(out["index"])[0]
        kind = "probabilities (softmax)" if abs(float(probs.sum()) - 1.0) < 0.05 else "raw scores (the app handles both)"
        print(f"     output looks like: {kind}")
        if len(inp["shape"]) != 4 or inp["shape"][-1] != 3:
            print("     WARNING: input is not [1,H,W,3]; the app will reject this file.")
    except Exception as e:  # verification is a bonus; the file may still be fine
        print("     (could not verify with TensorFlow:", e, ")")

    digest = hashlib.sha256(OUT.read_bytes()).hexdigest()
    female = [i for i, n in names.items() if str(n).lower().startswith("f")]
    female_idx = female[0] if female else 0
    size = OUT.stat().st_size
    limit = 200 * 1024 * 1024           # the app rejects imports above this
    project_model = Path("app/src/main/assets/models")

    print("4/4  Done:", OUT.resolve(), f"({size // 1024} KB)")
    if size <= limit:
        if project_model.is_dir():
            shutil.copy(OUT, project_model / "custom_yolov8n.tflite")
            print("\nCopied to app/src/main/assets/models/custom_yolov8n.tflite. Add a BundledModels entry for it (see docs/CONTENT_BLUR.md).")
        else:
            print("\nCopy it to app/src/main/assets/models/ and add a BundledModels entry (see docs/CONTENT_BLUR.md).")
        print("Or skip packing and use it as a custom model: Content blur -> Detection & custom model -> Choose a file.")
    else:
        print("\nToo large for the app to accept (200 MB limit).")
        print("  1. huggingface.co -> New model -> Public.   2. Upload gender.tflite.")
        print("  3. In Youtubly: Content blur -> Model source: <your-username>/<repo>, then Import model.")
    print(f"""
Then, in Content blur -> Detection & custom model, set:
  Output index for 'female' = {female_idx}
  Input normalization       = 0...1
SHA-256 (optional, for GENDER_MODEL_SHA256): {digest}
""")

if __name__ == "__main__":
    main()
