package ai.sentinel.mobile.net

import android.content.Context

/**
 * Offline indicator list loaded from assets/blocklist.txt.
 *
 * This is a user-editable starter list, not a threat feed and not a claim of reputation data.
 * Entries are either IPv4 addresses or domains; blank lines and lines starting with # are
 * ignored. Detection and blocking never depend on network access.
 */
class Blocklist private constructor(
    private val domains: Set<String>,
    private val addresses: Set<String>
) {

    val domainCount: Int get() = domains.size
    val addressCount: Int get() = addresses.size
    val isEmpty: Boolean get() = domains.isEmpty() && addresses.isEmpty()

    fun matchesDomain(domain: String?): String? {
        if (domain.isNullOrBlank()) return null
        val host = domain.lowercase().trim('.')
        if (host.isEmpty()) return null
        if (domains.contains(host)) return host
        var candidate = host
        while (candidate.contains('.')) {
            candidate = candidate.substringAfter('.')
            if (domains.contains(candidate)) return candidate
        }
        return null
    }

    fun matchesAddress(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        return addresses.contains(ip)
    }

    companion object {
        const val ASSET = "blocklist.txt"

        fun load(context: Context): Blocklist {
            val domains = HashSet<String>()
            val addresses = HashSet<String>()
            try {
                context.assets.open(ASSET).bufferedReader().useLines { lines ->
                    lines.forEach { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) return@forEach
                        val entry = line.substringBefore('#').trim().lowercase()
                        if (entry.isEmpty()) return@forEach
                        if (PolicyEngine.parseIpv4(entry) != null) addresses.add(entry)
                        else domains.add(entry)
                    }
                }
            } catch (_: Throwable) {
                // Missing asset means an empty list, which the UI states plainly.
            }
            return Blocklist(domains, addresses)
        }

        fun empty(): Blocklist = Blocklist(emptySet(), emptySet())
    }
}
