package ai.sentinel.mobile.net

/**
 * Minimal DNS reader: extracts the queried name and the A/AAAA answers from a plain UDP/53
 * message. Encrypted DNS (DoH/DoT) is invisible to this, which the app states explicitly.
 */
object DnsInspector {

    private const val MAX_NAME_LENGTH = 255

    fun queryName(payload: ByteArray?): String? {
        if (payload == null || payload.size < 12) return null
        val questionCount = u16(payload, 4)
        if (questionCount <= 0) return null
        return readName(payload, 12)?.first
    }

    /** Returns the IPv4 addresses in the answer section. */
    fun answerAddresses(payload: ByteArray?): List<String> {
        if (payload == null || payload.size < 12) return emptyList()
        val answerCount = u16(payload, 6)
        if (answerCount <= 0) return emptyList()

        var offset: Int
        val questionName = readName(payload, 12) ?: return emptyList()
        offset = questionName.second
        if (offset + 4 > payload.size) return emptyList()
        offset += 4

        val addresses = ArrayList<String>(answerCount)
        var index = 0
        while (index < answerCount && offset + 12 <= payload.size) {
            val name = readName(payload, offset) ?: break
            offset = name.second
            if (offset + 10 > payload.size) break
            val type = u16(payload, offset)
            val dataLength = u16(payload, offset + 8)
            val dataOffset = offset + 10
            if (dataOffset + dataLength > payload.size) break
            if (type == 1 && dataLength == 4) {
                addresses.add(
                    "${payload[dataOffset].toInt() and 0xFF}." +
                        "${payload[dataOffset + 1].toInt() and 0xFF}." +
                        "${payload[dataOffset + 2].toInt() and 0xFF}." +
                        "${payload[dataOffset + 3].toInt() and 0xFF}"
                )
            }
            offset = dataOffset + dataLength
            index++
        }
        return addresses
    }

    private fun readName(buffer: ByteArray, start: Int): Pair<String, Int>? {
        val labels = StringBuilder()
        var offset = start
        var nextOffset = -1
        var jumps = 0
        var totalLength = 0
        while (offset < buffer.size) {
            val length = buffer[offset].toInt() and 0xFF
            if (length == 0) {
                offset++
                break
            }
            if ((length and 0xC0) == 0xC0) {
                if (offset + 1 >= buffer.size) return null
                val pointer = ((length and 0x3F) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
                if (jumps++ > 8) return null
                if (nextOffset < 0) nextOffset = offset + 2
                offset = pointer
                continue
            }
            if (offset + 1 + length > buffer.size) return null
            if (labels.isNotEmpty()) labels.append('.')
            labels.append(String(buffer, offset + 1, length, Charsets.US_ASCII))
            totalLength += length + 1
            if (totalLength > MAX_NAME_LENGTH) return null
            offset += length + 1
        }
        val resolvedNext = if (nextOffset >= 0) nextOffset else offset
        val name = labels.toString().lowercase()
        return if (name.isEmpty()) null else name to resolvedNext
    }

    private fun u16(buffer: ByteArray, offset: Int): Int {
        if (offset + 1 >= buffer.size) return 0
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }

    /** Shorthand used by the UI and rules. */
    fun isSuspiciousName(domain: String): Boolean {
        val label = domain.substringBefore('.')
        if (label.length < 20) return false
        val digits = label.count { it.isDigit() }
        val letters = label.count { it.isLetter() }
        return digits >= 2 && letters >= 8
    }
}
