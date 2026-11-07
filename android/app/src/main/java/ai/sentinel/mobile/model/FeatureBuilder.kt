package ai.sentinel.mobile.model

import org.json.JSONObject

/**
 * Maps computed CIC-style statistics onto the model's 70 input slots.
 *
 * The contract order is authoritative. Anything the tunnel could not compute is passed as
 * NaN so the exported graph's fitted median imputer handles it, exactly as the Python parity
 * test does. Values are never defaulted to 0, which would silently mean "measured as zero".
 */
object FeatureBuilder {

    fun build(contract: ModelContract, values: Map<String, Double>): FloatArray =
        FloatArray(contract.featureCount) { index ->
            values[contract.featureNames[index]]?.toFloat() ?: Float.NaN
        }

    /**
     * A factual summary of the flow that triggered an alert. This is deliberately NOT called
     * feature attribution: the random forest's per-decision contributions are not exposed, so
     * claiming to explain "why" would be invented. These are the measured inputs.
     */
    fun describe(values: Map<String, Double>): String {
        val keys = listOf(
            "Destination Port",
            "Flow Duration",
            "Total Fwd Packets",
            "Total Backward Packets",
            "Flow Bytes/s",
            "Flow Packets/s",
            "Flow IAT Mean",
            "Packet Length Mean",
            "SYN Flag Count",
            "RST Flag Count"
        )
        val json = JSONObject()
        keys.forEach { key ->
            values[key]?.let { json.put(key, round(it)) }
        }
        return json.toString()
    }

    private fun round(value: Double): Double =
        if (value.isNaN() || value.isInfinite()) 0.0 else Math.round(value * 1000.0) / 1000.0
}
