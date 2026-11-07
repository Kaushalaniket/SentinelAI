package ai.sentinel.mobile.net

/**
 * One parsed IP packet. Payload bytes are only retained for UDP, because the DNS relay needs
 * them; TCP payloads are measured and discarded.
 */
data class ParsedPacket(
    val timestampMs: Long,
    val ipVersion: Int,
    val protocol: Int,
    val srcIp: String,
    val dstIp: String,
    val srcPort: Int,
    val dstPort: Int,
    val tcpFlags: Int,
    val window: Int,
    val payloadLength: Int,
    val totalLength: Int,
    val payloadOffset: Int,
    val payload: ByteArray?
) {
    val isTcp: Boolean get() = protocol == PROTO_TCP
    val isUdp: Boolean get() = protocol == PROTO_UDP
    val isDns: Boolean get() = isUdp && (srcPort == 53 || dstPort == 53)
    val isSyn: Boolean get() = isTcp && (tcpFlags and FLAG_SYN) != 0
    val isFin: Boolean get() = isTcp && (tcpFlags and FLAG_FIN) != 0
    val isRst: Boolean get() = isTcp && (tcpFlags and FLAG_RST) != 0

    companion object {
        const val PROTO_ICMP = 1
        const val PROTO_TCP = 6
        const val PROTO_UDP = 17
        const val FLAG_FIN = 0x01
        const val FLAG_SYN = 0x02
        const val FLAG_RST = 0x04
        const val FLAG_PSH = 0x08
        const val FLAG_ACK = 0x10
        const val FLAG_URG = 0x20
        const val FLAG_ECE = 0x40
        const val FLAG_CWR = 0x80
    }
}

/**
 * Parses IPv4/IPv6 packets carrying TCP, UDP or ICMP.
 *
 * Nothing here throws: a malformed or truncated packet returns null and the caller counts it.
 * IPv6 extension headers are walked (bounded) so IPv6 TCP is not silently dropped.
 */
object PacketParser {

    private const val MAX_EXTENSION_HEADERS = 4

    fun parse(buffer: ByteArray, length: Int, timestampMs: Long): ParsedPacket? {
        if (length < 20) return null
        return when ((buffer[0].toInt() and 0xF0) ushr 4) {
            4 -> parseIpv4(buffer, length, timestampMs)
            6 -> parseIpv6(buffer, length, timestampMs)
            else -> null
        }
    }

    private fun parseIpv4(buffer: ByteArray, length: Int, timestampMs: Long): ParsedPacket? {
        val headerLength = (buffer[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || headerLength > length) return null
        val totalLength = u16(buffer, 2)
        if (totalLength < headerLength || totalLength > length) return null
        val protocol = buffer[9].toInt() and 0xFF
        return parseTransport(
            buffer, totalLength, headerLength, protocol,
            ipv4(buffer, 12), ipv4(buffer, 16), timestampMs, 4
        )
    }

    private fun parseIpv6(buffer: ByteArray, length: Int, timestampMs: Long): ParsedPacket? {
        if (length < 40) return null
        val totalLength = 40 + u16(buffer, 4)
        if (totalLength > length) return null
        var nextHeader = buffer[6].toInt() and 0xFF
        var offset = 40
        var extensions = 0
        while (nextHeader == 0 || nextHeader == 43 || nextHeader == 60 || nextHeader == 44) {
            if (extensions++ > MAX_EXTENSION_HEADERS || offset + 8 > totalLength) return null
            val extensionLength = if (nextHeader == 44) 8 else (buffer[offset + 1].toInt() and 0xFF) * 8 + 8
            if (extensionLength <= 0 || offset + extensionLength > totalLength) return null
            nextHeader = buffer[offset].toInt() and 0xFF
            offset += extensionLength
        }
        return parseTransport(
            buffer, totalLength, offset, nextHeader,
            ipv6(buffer, 8), ipv6(buffer, 24), timestampMs, 6
        )
    }

    private fun parseTransport(
        buffer: ByteArray,
        totalLength: Int,
        transportOffset: Int,
        protocol: Int,
        srcIp: String,
        dstIp: String,
        timestampMs: Long,
        version: Int
    ): ParsedPacket? {
        when (protocol) {
            ParsedPacket.PROTO_TCP -> {
                if (transportOffset + 20 > totalLength) return null
                val dataOffset = ((buffer[transportOffset + 12].toInt() ushr 4) and 0x0F) * 4
                if (dataOffset < 20 || transportOffset + dataOffset > totalLength) return null
                val payloadOffset = transportOffset + dataOffset
                return ParsedPacket(
                    timestampMs = timestampMs,
                    ipVersion = version,
                    protocol = protocol,
                    srcIp = srcIp,
                    dstIp = dstIp,
                    srcPort = u16(buffer, transportOffset),
                    dstPort = u16(buffer, transportOffset + 2),
                    tcpFlags = buffer[transportOffset + 13].toInt() and 0xFF,
                    window = u16(buffer, transportOffset + 14),
                    payloadLength = (totalLength - payloadOffset).coerceAtLeast(0),
                    totalLength = totalLength,
                    payloadOffset = payloadOffset,
                    payload = null
                )
            }
            ParsedPacket.PROTO_UDP -> {
                if (transportOffset + 8 > totalLength) return null
                val payloadOffset = transportOffset + 8
                val payloadLength = (totalLength - payloadOffset).coerceAtLeast(0)
                return ParsedPacket(
                    timestampMs = timestampMs,
                    ipVersion = version,
                    protocol = protocol,
                    srcIp = srcIp,
                    dstIp = dstIp,
                    srcPort = u16(buffer, transportOffset),
                    dstPort = u16(buffer, transportOffset + 2),
                    tcpFlags = 0,
                    window = 0,
                    payloadLength = payloadLength,
                    totalLength = totalLength,
                    payloadOffset = payloadOffset,
                    payload = if (payloadLength in 1..1472) {
                        buffer.copyOfRange(payloadOffset, payloadOffset + payloadLength)
                    } else null
                )
            }
            ParsedPacket.PROTO_ICMP -> return ParsedPacket(
                timestampMs = timestampMs,
                ipVersion = version,
                protocol = protocol,
                srcIp = srcIp,
                dstIp = dstIp,
                srcPort = 0,
                dstPort = 0,
                tcpFlags = 0,
                window = 0,
                payloadLength = (totalLength - transportOffset - 8).coerceAtLeast(0),
                totalLength = totalLength,
                payloadOffset = (transportOffset + 8).coerceAtMost(totalLength),
                payload = null
            )
            else -> return null
        }
    }

    /** Builds an IPv4/UDP datagram. The UDP checksum is omitted, which IPv4 permits. */
    fun buildUdpIpv4(
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLength = 20 + 8 + payload.size
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        packet[2] = ((totalLength ushr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[8] = 64
        packet[9] = ParsedPacket.PROTO_UDP.toByte()
        writeIpv4(packet, 12, srcIp)
        writeIpv4(packet, 16, dstIp)
        val headerChecksum = checksum(packet, 0, 20)
        packet[10] = ((headerChecksum ushr 8) and 0xFF).toByte()
        packet[11] = (headerChecksum and 0xFF).toByte()
        packet[20] = ((srcPort ushr 8) and 0xFF).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort ushr 8) and 0xFF).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        val udpLength = 8 + payload.size
        packet[24] = ((udpLength ushr 8) and 0xFF).toByte()
        packet[25] = (udpLength and 0xFF).toByte()
        payload.copyInto(packet, 28)
        return packet
    }

    fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < end) sum += (data[index].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun u16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    private fun ipv4(buffer: ByteArray, offset: Int): String =
        "${buffer[offset].toInt() and 0xFF}.${buffer[offset + 1].toInt() and 0xFF}." +
            "${buffer[offset + 2].toInt() and 0xFF}.${buffer[offset + 3].toInt() and 0xFF}"

    private fun ipv6(buffer: ByteArray, offset: Int): String {
        val parts = StringBuilder()
        for (index in 0 until 8) {
            if (index > 0) parts.append(':')
            parts.append(Integer.toHexString(u16(buffer, offset + index * 2)))
        }
        return parts.toString()
    }

    private fun writeIpv4(target: ByteArray, offset: Int, ip: String) {
        val parts = ip.split('.')
        for (index in 0 until 4) {
            target[offset + index] = (parts.getOrNull(index)?.toIntOrNull() ?: 0).toByte()
        }
    }
}
