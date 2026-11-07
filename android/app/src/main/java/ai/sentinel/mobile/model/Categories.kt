package ai.sentinel.mobile.model

import androidx.compose.ui.graphics.Color

/** The nine triage categories produced by the trained model. Order matches model_contract.json. */
enum class Category(
    val key: String,
    val label: String,
    val severity: String
) {
    BENIGN("benign", "Benign", "none"),
    BOT("bot", "Bot / C2", "high"),
    BRUTEFORCE("bruteforce", "Brute force", "high"),
    DDOS("ddos", "DDoS", "high"),
    DOS("dos", "Denial of service", "medium"),
    HEARTBLEED("heartbleed", "Heartbleed", "high"),
    INFILTRATION("infiltration", "Infiltration", "high"),
    PORTSCAN("portscan", "Port scan", "medium"),
    WEBATTACK("webattack", "Web attack", "high");

    val isThreat: Boolean get() = this != BENIGN

    val color: Color
        get() = when (this) {
            BENIGN -> Color(0xFF3FB950)
            PORTSCAN, DOS -> Color(0xFFD29922)
            else -> Color(0xFFE5534B)
        }

    companion object {
        fun fromKey(key: String?): Category? = entries.firstOrNull { it.key == key }
    }
}

/** Provenance labels shown on every row. A prediction is never shown without one. */
enum class DetectionSource(val key: String, val label: String) {
    AI("ai", "AI"),
    RULE("rule", "Rule"),
    UNCLASSIFIED("unclassified", "Unclassified"),
    NONE("none", "—");

    companion object {
        fun fromKey(key: String?): DetectionSource =
            entries.firstOrNull { it.key == key } ?: NONE
    }
}
