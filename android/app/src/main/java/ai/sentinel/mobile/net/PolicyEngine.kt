package ai.sentinel.mobile.net

/** A user firewall rule as stored in Room, reduced to what the tunnel needs. */
data class PolicyRule(
    val id: Long,
    val type: String,
    val value: String,
    val action: String,
    val enabled: Boolean,
    val expiresAt: Long?
)

sealed class Decision {
    data class Block(val rule: PolicyRule, val description: String) : Decision()
    data class Allow(val rule: PolicyRule) : Decision()
    data object None : Decision()
}

data class Route(val network: Long, val prefix: Int) {
    val address: String get() = PolicyEngine.ipToString(network)
    val description: String get() = "$address/$prefix"
}

/**
 * Firewall evaluation plus the safety checks that keep the tunnel from cutting the device off.
 *
 * Allow rules always win. Blocking a DNS server, the gateway, the tunnel address or a route
 * wider than /8 is refused with a reason rather than silently accepted.
 */
class PolicyEngine(
    private val dnsServers: Set<String> = emptySet(),
    private val gateway: String? = null
) {

    private var rules: List<PolicyRule> = emptyList()
    private val domainIps = HashMap<String, MutableSet<String>>()

    fun updateRules(newRules: List<PolicyRule>) {
        val now = System.currentTimeMillis()
        rules = newRules.filter { it.enabled && (it.expiresAt == null || it.expiresAt > now) }
    }

    fun learnDomain(domain: String, ips: Collection<String>) {
        val key = domain.lowercase().trim('.')
        if (key.isBlank() || ips.isEmpty()) return
        domainIps.getOrPut(key) { mutableSetOf() }.addAll(ips)
    }

    fun resolvedIps(domain: String): Set<String> =
        domainIps[domain.lowercase().trim('.')] ?: emptySet()

    fun knownDomains(): Set<String> = domainIps.keys

    fun activeRules(): List<PolicyRule> = rules

    fun evaluate(ip: String, port: Int, domain: String?): Decision {
        rules.firstOrNull { it.action == "allow" && matches(it, ip, port, domain) }
            ?.let { return Decision.Allow(it) }
        rules.firstOrNull { it.action == "block" && matches(it, ip, port, domain) }
            ?.let { return Decision.Block(it, describe(it)) }
        return Decision.None
    }

    private fun matches(rule: PolicyRule, ip: String, port: Int, domain: String?): Boolean =
        when (rule.type) {
            "ip" -> rule.value.trim() == ip
            "cidr" -> cidrContains(rule.value.trim(), ip)
            "port" -> rule.value.trim().toIntOrNull() == port
            "domain" -> {
                if (domain == null) false else {
                    val target = rule.value.trim().lowercase().trim('.')
                    val bare = target.removePrefix("www.")
                    val host = domain.lowercase().trim('.')
                    host == target || host == bare || host.endsWith(".$bare")
                }
            }
            else -> false
        }

    fun describe(rule: PolicyRule): String = when (rule.type) {
        "ip" -> "IP ${rule.value}"
        "cidr" -> "range ${rule.value}"
        "port" -> "port ${rule.value}"
        "domain" -> "domain ${rule.value}"
        else -> rule.value
    }

    /**
     * Routes the tunnel must claim for rules to actually see the traffic. Domains are expanded
     * to the IPs learned from observed DNS answers; ports cannot be expressed as routes and are
     * therefore matched only for traffic that is already inside the tunnel.
     */
    fun routesFor(rules: List<PolicyRule>): List<Route> {
        val routes = ArrayList<Route>()
        rules.forEach { rule ->
            when (rule.type) {
                "ip" -> parseIpv4(rule.value.trim())?.let { routes.add(Route(it, 32)) }
                "cidr" -> {
                    val base = parseIpv4(rule.value.substringBefore('/').trim())
                    val prefix = rule.value.substringAfter('/', "32").trim().toIntOrNull() ?: 32
                    if (base != null && prefix in 8..32) routes.add(Route(base, prefix))
                }
                "domain" -> resolvedIps(rule.value).forEach { ip ->
                    parseIpv4(ip)?.let { routes.add(Route(it, 32)) }
                }
            }
        }
        return routes.distinctBy { it.network to it.prefix }
    }

    /** Returns a human reason when a rule would lock the device out, otherwise null. */
    fun validate(type: String, value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "Value cannot be empty"
        return when (type) {
            "ip" -> {
                val parsed = parseIpv4(trimmed) ?: return "Not a valid IPv4 address"
                when {
                    parsed == parseIpv4(TUNNEL_ADDRESS) -> "That is SentinelAI's own tunnel address"
                    dnsServers.contains(trimmed) -> "That is a DNS server; blocking it would break name resolution"
                    trimmed == gateway -> "That is the network gateway"
                    else -> null
                }
            }
            "cidr" -> {
                val baseText = trimmed.substringBefore('/')
                val prefix = trimmed.substringAfter('/', "").toIntOrNull()
                    ?: return "Use CIDR form, for example 203.0.113.0/24"
                val base = parseIpv4(baseText) ?: return "Not a valid IPv4 network"
                when {
                    prefix !in 8..32 ->
                        "Prefix must be between /8 and /32; anything wider would cut off normal traffic"
                    base == 0L -> "0.0.0.0/8 would cut off normal traffic"
                    else -> null
                }
            }
            "port" -> {
                val port = trimmed.toIntOrNull() ?: return "Not a valid port number"
                when {
                    port !in 1..65535 -> "Port must be between 1 and 65535"
                    port == 53 -> "Port 53 carries DNS; blocking it would break name resolution"
                    else -> null
                }
            }
            "domain" -> if (trimmed.contains(' ')) "Domain cannot contain spaces" else null
            else -> "Unknown rule type"
        }
    }

    companion object {
        const val TUNNEL_ADDRESS = "10.111.222.1"
        private const val MASK = 0xFFFFFFFFL

        fun parseIpv4(value: String): Long? {
            val parts = value.split('.')
            if (parts.size != 4) return null
            var result = 0L
            parts.forEach { part ->
                val octet = part.toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                result = (result shl 8) or octet.toLong()
            }
            return result and MASK
        }

        fun cidrContains(cidr: String, ip: String): Boolean {
            val network = parseIpv4(cidr.substringBefore('/')) ?: return false
            val prefix = cidr.substringAfter('/', "32").toIntOrNull() ?: 32
            val address = parseIpv4(ip) ?: return false
            if (prefix <= 0) return true
            val mask = (MASK shl (32 - prefix)) and MASK
            return (network and mask) == (address and mask)
        }

        fun ipToString(value: Long): String =
            "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}." +
                "${(value shr 8) and 0xFF}.${value and 0xFF}"
    }
}
