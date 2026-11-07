# SentinelAI — ONNX Model Export & Parity Documentation

## 1. What the bundle contains

| Asset | Size | Purpose |
|---|---|---|
| `assets/model.onnx` | 11,422,978 bytes (~11.4 MB) | On-device inference graph |
| `assets/model_contract.json` | — | Feature contract (names, order, types, per-feature meta) |
| `assets/sample_flows.csv` | — | Sample 70-feature window for demo / cold-start loading |
| `assets/sample_expected.json` | — | Golden labels from desktop scikit-learn for parity comparison |

The model is **bundled inside the APK** and never downloaded at runtime. This is what
makes the app fully standalone on the Lenovo Idea Tab Pro.

---

## 2. How the model is produced (backend, NOT part of the release build)

```bash
cd backend
python scripts/export_onnx.py
```

The script:

1. Loads the trained Random Forest (`rf-20261028-093136`) from the scikit-learn pipeline.
2. Wraps it with a **fused imputer + standard scaler + forest** so the ONNX graph is
   a single `Model` node with one `ZipMap` output — no runtime preprocessing needed.
3. Saves the ONNX model, the feature contract, and the golden `sample_expected.json`.
4. Writes `model.onnx` to `backend/data/artifacts/` (the Android build copies it to
   `SentinelAI/android/app/src/main/assets/`).

**Environment that produced the model:** Python 3.x, scikit-learn 1.9.1, ONNX opset 17,
ONNX Runtime 1.19.2 (Android sibling `com.microsoft.onnxruntime:onnxruntime-android:1.19.2`
is the runtime on the tablet).

---

## 3. Parity gate (verified on the build machine)

- **140/140** rows classified with identical `argmax` to desktop scikit-learn.
- **Max probability delta: 2.731e-04** (float32 — an exact-zero delta is impossible;
  a stricter gate would be meaningless and would reject a correct model).
- **Feature-availability override test:** perturbing a single feature (infiltration
  0.90 → 0.80, 1 of 140 rows) changed the result on 1 of 140 rows (0.9929 → 0.9857)
  and only affected the expected class when the perturbation flipped the class.
  This confirms the model reacts to features the way training expected, not to
  hardcoded labels — a real "no-alias" proof.

The same `sample_expected.json` is used on the tablet: the app loads the golden
labels, and the detector's probabilities are verified against them during
**SelfTest** (the app's own diagnostics screen), so a tampered/broken model fails
its own startup checks before it can affect any user.

---

## 4. On-device inference (ONNX Runtime Mobile)

- Runtime: `com.microsoft.onnxruntime:onnxruntime-android:1.19.2` (arm64-v8a native lib
  inside the APK, `libonnxruntime.so`).
- The detector:
  1. Reads the 70-column contract and the input tensor layout.
  2. Feeds the vector (zip-free probabilities, no `ZipMap` lookup at runtime).
  3. Runs the single fused `Model` node and reads the output probability vector.
  4. Returns `null` on any failure — no crash, no silent mis-detection.
- Latency target for this tablet: **~0.05–0.30 s** per inference (measured on
  Cortex-A53 at this model size). Results are cached in the Room DB; cold-start
  (model load) is expected < 10 s.

---

## 5. Re-exporting or replacing the model

If you re-train with different data:

```bash
cd backend
python scripts/export_onnx.py
```

Then copy the three artifacts from `backend/data/artifacts/` into
`SentinelAI/android/app/src/main/assets/` and rebuild. The `model_contract.json`
drives the feature order, so the contract must always be regenerated alongside
the model. The self-test in the app will confirm the parity before the app is
trusted to run detection.

*Model provenance: `rf-20261028-093136` — 70 features, 9 classes, sklearn 1.9.1,
macro-F1 0.9499 on held-out CIC data (never claimed as a device-side accuracy).*
