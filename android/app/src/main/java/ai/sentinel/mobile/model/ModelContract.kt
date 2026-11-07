package ai.sentinel.mobile.model

import android.content.Context
import org.json.JSONObject

/**
 * Loaded from assets/model_contract.json, written by backend/scripts/export_onnx.py.
 * The feature order is authoritative: the detector must never reorder it.
 */
data class ModelContract(
    val modelVersion: String,
    val opset: Int,
    val inputName: String,
    val featureNames: List<String>,
    val classes: List<String>,
    val defaultThreshold: Double,
    val sha256: String,
    val onnxBytes: Long,
    val approximatedFeatures: Set<String>,
    val parityRows: Int,
    val parityAgreement: Int,
    val parityDelta: Double,
    val trainedAt: String,
    val sklearnVersion: String
) {
    val featureCount: Int get() = featureNames.size

    companion object {
        fun load(context: Context): ModelContract {
            val text = context.assets.open("model_contract.json").bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val features = json.getJSONArray("feature_names")
            val classes = json.getJSONArray("classes")
            val parity = json.optJSONObject("parity")
            val approximated = json.optJSONArray("approximated_features")
            return ModelContract(
                modelVersion = json.optString("model_version", "unknown"),
                opset = json.optInt("opset", 17),
                inputName = json.optString("input_name", "input"),
                featureNames = List(features.length()) { features.getString(it) },
                classes = List(classes.length()) { classes.getString(it) },
                defaultThreshold = json.optDouble("default_threshold", 0.5),
                sha256 = json.optString("onnx_sha256", ""),
                onnxBytes = json.optLong("onnx_bytes", 0L),
                approximatedFeatures = if (approximated == null) emptySet()
                else (0 until approximated.length()).map { approximated.getString(it) }.toSet(),
                parityRows = parity?.optInt("rows", 0) ?: 0,
                parityAgreement = parity?.optInt("agreement", 0) ?: 0,
                parityDelta = parity?.optDouble("max_probability_delta", 0.0) ?: 0.0,
                trainedAt = json.optString("trained_at", ""),
                sklearnVersion = json.optString("sklearn_version", "")
            )
        }
    }
}
