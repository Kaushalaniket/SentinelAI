package ai.sentinel.mobile.net

/**
 * Heuristic layer that runs on observed DNS activity.
 *
 * These are rules, not model output, and every alert they raise carries kind = "rule" so the
 * UI can never present them as an AI verdict.
 */
class RuleEngine(private val blocklist: Blocklist) {

    data class Finding(
        val category: String,
        val severity: String,
        val reason: String,
        val domain: String?
    )

    private val queryTimestamps = ArrayDeque<Long>()
    private var lastFloodAlertMs = 0L

    fun onQuery(domain: String, timestampMs: Long): List<Finding> {
        val findings = ArrayList<Finding>(2)

        val matchedDomain = blocklist.matchesDomain(domain)
        if (matchedDomain != null) {
            findings.add(
                Finding(
                    category = "blocklist",
                    severity = "high",
                    reason = "$domain matches the offline list entry \"$matchedDomain\"",
                    domain = domain
                )
            )
        }

        if (DnsInspector.isSuspiciousName(domain)) {
            findings.add(
                Finding(
                    category = "dns-anomaly",
                    severity = "medium",
                    reason = "$domain has a long random-looking label, a common beacon/DGA shape",
                    domain = domain
                )
            )
        }

        queryTimestamps.addLast(timestampMs)
        while (queryTimestamps.isNotEmpty() && timestampMs - queryTimestamps.first() > 60_000) {
            queryTimestamps.removeFirst()
        }
        if (queryTimestamps.size > 120 && queryTimestamps.size > 60 &&
            timestampMs - lastFloodAlertMs > 120_000
        ) {
            lastFloodAlertMs = timestampMs
            findings.add(
                Finding(
                    category = "dns-flood",
                    severity = "medium",
                    reason = "${queryTimestamps.size} DNS queries in the last 60 seconds",
                    domain = null
                )
            )
        }
        return findings
    }

    fun reset() {
        queryTimestamps.clear()
        lastFloodAlertMs = 0L
    }
}
