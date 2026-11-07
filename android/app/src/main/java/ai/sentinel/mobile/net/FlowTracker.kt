package ai.sentinel.mobile.net

/**
 * Streaming statistics, so IAT and length features never require storing packet timestamps.
 */
class StreamStat {
    var count: Int = 0
        private set
    private var sum = 0.0
    private var sumSquares = 0.0
    var min: Double = Double.MAX_VALUE
        private set
    var max: Double = 0.0
        private set

    fun add(value: Double) {
        if (value.isNaN() || value.isInfinite()) return
        count++
        sum += value
        sumSquares += value * value
        if (value < min) min = value
        if (value > max) max = value
    }

    fun mean(): Double = if (count == 0) 0.0 else sum / count

    fun std(): Double {
        if (count == 0) return 0.0
        val mean = mean()
        val variance = (sumSquares / count) - mean * mean
        return if (variance <= 0) 0.0 else Math.sqrt(variance)
    }

    fun minOrZero(): Double = if (count == 0) 0.0 else min
}

data class FlowKey(
    val ipA: String,
    val portA: Int,
    val ipB: String,
    val portB: Int,
    val protocol: Int
) {
    companion object {
        fun of(packet: ParsedPacket): FlowKey {
            val forward = packet.srcIp < packet.dstIp ||
                (packet.srcIp == packet.dstIp && packet.srcPort <= packet.dstPort)
            return if (forward) {
                FlowKey(packet.srcIp, packet.srcPort, packet.dstIp, packet.dstPort, packet.protocol)
            } else {
                FlowKey(packet.dstIp, packet.dstPort, packet.srcIp, packet.srcPort, packet.protocol)
            }
        }
    }
}

/** A flow that has closed. Carries measured statistics, not estimates. */
data class ClosedFlow(
    val key: FlowKey,
    val serverIp: String,
    val serverPort: Int,
    val protocol: String,
    val startMs: Long,
    val endMs: Long,
    val packetsFwd: Int,
    val packetsBwd: Int,
    val bytesFwd: Long,
    val bytesBwd: Long,
    val metrics: Map<String, Double>,
    val domain: String? = null
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
    val totalPackets: Int get() = packetsFwd + packetsBwd
}

/**
 * Bounded bidirectional flow table.
 *
 * Capacity is capped so a flood cannot exhaust memory: when the table is full the oldest entry
 * is evicted and reported instead of the table growing.
 */
class FlowTracker(
    private val maxFlows: Int = 4096,
    private val idleTimeoutMs: Long = 120_000L,
    private val absoluteTimeoutMs: Long = 300_000L
) {

    private class Record(
        val key: FlowKey,
        val serverIp: String,
        val serverPort: Int,
        val startMs: Long
    ) {
        var lastMs: Long = startMs
        var packetsFwd = 0
        var packetsBwd = 0
        var bytesFwd = 0L
        var bytesBwd = 0L
        var headerBytesFwd = 0L
        var headerBytesBwd = 0L
        var initWindowFwd = 0
        var initWindowBwd = 0
        var minSegFwd = Int.MAX_VALUE
        var actDataPktFwd = 0
        var synCount = 0
        var finCount = 0
        var rstCount = 0
        var pshCount = 0
        var ackCount = 0
        var urgCount = 0
        var cwrCount = 0
        var eceCount = 0
        val fwdLength = StreamStat()
        val bwdLength = StreamStat()
        val flowLength = StreamStat()
        val fwdIat = StreamStat()
        val bwdIat = StreamStat()
        val flowIat = StreamStat()
        val activeDurations = StreamStat()
        val idleDurations = StreamStat()
        var activeStartMs = startMs
        var lastActivityMs = startMs
        var sawForward = false
        var sawBackward = false
        var domain: String? = null
    }

    private val flows = LinkedHashMap<FlowKey, Record>(maxFlows)
    private var evictions = 0

    val activeCount: Int get() = flows.size
    val evictionCount: Int get() = evictions

    fun noteDomain(key: FlowKey, domain: String?) {
        if (domain.isNullOrBlank()) return
        flows[key]?.domain = domain
    }

    fun onPacket(packet: ParsedPacket): List<ClosedFlow> {
        val key = FlowKey.of(packet)
        var record = flows[key]
        if (record == null) {
            var evicted: List<ClosedFlow> = emptyList()
            if (flows.size >= maxFlows) {
                val iterator = flows.entries.iterator()
                if (iterator.hasNext()) {
                    val eldest = iterator.next()
                    evicted = listOf(close(eldest.value, eldest.value.lastMs))
                    iterator.remove()
                    evictions++
                }
            }
            record = newRecord(key, packet)
            flows[key] = record
            applyPacket(record, packet)
            return evicted
        }
        applyPacket(record, packet)
        if (record.lastMs - record.startMs > absoluteTimeoutMs) {
            flows.remove(key)
            return listOf(close(record, record.lastMs))
        }
        return emptyList()
    }

    fun sweep(nowMs: Long): List<ClosedFlow> {
        if (flows.isEmpty()) return emptyList()
        val closed = ArrayList<ClosedFlow>()
        val iterator = flows.entries.iterator()
        while (iterator.hasNext()) {
            val record = iterator.next().value
            if (nowMs - record.lastMs > idleTimeoutMs || nowMs - record.startMs > absoluteTimeoutMs) {
                closed.add(close(record, record.lastMs))
                iterator.remove()
            }
        }
        return closed
    }

    fun drain(): List<ClosedFlow> {
        val closed = flows.values.map { close(it, it.lastMs) }
        flows.clear()
        return closed
    }

    private fun newRecord(key: FlowKey, packet: ParsedPacket): Record {
        val serverIsDestination = packet.isSyn || packet.dstPort <= 1024 ||
            packet.dstPort <= packet.srcPort
        val serverIp = if (serverIsDestination) packet.dstIp else packet.srcIp
        val serverPort = if (serverIsDestination) packet.dstPort else packet.srcPort
        return Record(key, serverIp, serverPort, packet.timestampMs)
    }

    private fun applyPacket(record: Record, packet: ParsedPacket) {
        val forward = packet.srcIp == record.key.ipA && packet.srcPort == record.key.portA
        val gapMicros = (packet.timestampMs - record.lastMs).toDouble() * 1000.0
        record.flowIat.add(gapMicros)

        if (forward) {
            if (record.sawForward) record.fwdIat.add(gapMicros)
            record.sawForward = true
            record.packetsFwd++
            record.bytesFwd += packet.payloadLength
            record.headerBytesFwd += (packet.totalLength - packet.payloadLength)
            if (record.initWindowFwd == 0 && packet.isTcp) record.initWindowFwd = packet.window
            if (packet.payloadLength > 0) {
                record.actDataPktFwd++
                if (packet.payloadLength < record.minSegFwd) record.minSegFwd = packet.payloadLength
            }
            record.fwdLength.add(packet.payloadLength.toDouble())
        } else {
            if (record.sawBackward) record.bwdIat.add(gapMicros)
            record.sawBackward = true
            record.packetsBwd++
            record.bytesBwd += packet.payloadLength
            record.headerBytesBwd += (packet.totalLength - packet.payloadLength)
            if (record.initWindowBwd == 0 && packet.isTcp) record.initWindowBwd = packet.window
            record.bwdLength.add(packet.payloadLength.toDouble())
        }

        if (packet.isTcp) {
            if ((packet.tcpFlags and ParsedPacket.FLAG_SYN) != 0) record.synCount++
            if ((packet.tcpFlags and ParsedPacket.FLAG_FIN) != 0) record.finCount++
            if ((packet.tcpFlags and ParsedPacket.FLAG_RST) != 0) record.rstCount++
            if ((packet.tcpFlags and ParsedPacket.FLAG_PSH) != 0) record.pshCount++
            if ((packet.tcpFlags and ParsedPacket.FLAG_ACK) != 0) record.ackCount++
            if ((packet.tcpFlags and ParsedPacket.FLAG_URG) != 0) record.urgCount++
            if ((packet.tcpFlags and ParsedPacket.FLAG_CWR) != 0) record.cwrCount++
            if ((packet.tcpFlags and ParsedPacket.FLAG_ECE) != 0) record.eceCount++
        }

        record.flowLength.add(packet.payloadLength.toDouble())

        // Activity segmentation approximates CICFlowMeter's active/idle statistics.
        val activityGap = packet.timestampMs - record.lastActivityMs
        if (activityGap > 5000) {
            record.activeDurations.add((record.lastActivityMs - record.activeStartMs).toDouble())
            record.idleDurations.add(activityGap.toDouble())
            record.activeStartMs = packet.timestampMs
        }
        record.lastActivityMs = packet.timestampMs
        record.lastMs = packet.timestampMs
    }

    private fun close(record: Record, endMs: Long): ClosedFlow {
        val durationMs = (endMs - record.startMs).coerceAtLeast(1)
        val durationSeconds = durationMs / 1000.0
        val totalBytes = record.bytesFwd + record.bytesBwd
        val totalPackets = record.packetsFwd + record.packetsBwd
        val metrics = LinkedHashMap<String, Double>(96)
        metrics["Destination Port"] = record.serverPort.toDouble()
        metrics["Flow Duration"] = durationMs * 1000.0
        metrics["Total Fwd Packets"] = record.packetsFwd.toDouble()
        metrics["Total Backward Packets"] = record.packetsBwd.toDouble()
        metrics["Total Length of Fwd Packets"] = record.bytesFwd.toDouble()
        metrics["Total Length of Bwd Packets"] = record.bytesBwd.toDouble()
        metrics["Fwd Packet Length Max"] = record.fwdLength.max
        metrics["Fwd Packet Length Min"] = record.fwdLength.minOrZero()
        metrics["Fwd Packet Length Mean"] = record.fwdLength.mean()
        metrics["Fwd Packet Length Std"] = record.fwdLength.std()
        metrics["Bwd Packet Length Max"] = record.bwdLength.max
        metrics["Bwd Packet Length Min"] = record.bwdLength.minOrZero()
        metrics["Bwd Packet Length Mean"] = record.bwdLength.mean()
        metrics["Bwd Packet Length Std"] = record.bwdLength.std()
        metrics["Flow Bytes/s"] = totalBytes / durationSeconds
        metrics["Flow Packets/s"] = totalPackets / durationSeconds
        metrics["Flow IAT Mean"] = record.flowIat.mean()
        metrics["Flow IAT Std"] = record.flowIat.std()
        metrics["Flow IAT Max"] = record.flowIat.max
        metrics["Flow IAT Min"] = record.flowIat.minOrZero()
        metrics["Fwd IAT Total"] = record.fwdIat.mean() * record.fwdIat.count
        metrics["Fwd IAT Mean"] = record.fwdIat.mean()
        metrics["Fwd IAT Std"] = record.fwdIat.std()
        metrics["Fwd IAT Max"] = record.fwdIat.max
        metrics["Fwd IAT Min"] = record.fwdIat.minOrZero()
        metrics["Bwd IAT Total"] = record.bwdIat.mean() * record.bwdIat.count
        metrics["Bwd IAT Mean"] = record.bwdIat.mean()
        metrics["Bwd IAT Std"] = record.bwdIat.std()
        metrics["Bwd IAT Max"] = record.bwdIat.max
        metrics["Bwd IAT Min"] = record.bwdIat.minOrZero()
        metrics["Fwd PSH Flags"] = if (record.pshCount > 0) 1.0 else 0.0
        metrics["Fwd URG Flags"] = if (record.urgCount > 0) 1.0 else 0.0
        metrics["Fwd Header Length"] = record.headerBytesFwd.toDouble()
        metrics["Bwd Header Length"] = record.headerBytesBwd.toDouble()
        metrics["Fwd Header Length.1"] = record.headerBytesFwd.toDouble()
        metrics["Fwd Packets/s"] = record.packetsFwd / durationSeconds
        metrics["Bwd Packets/s"] = record.packetsBwd / durationSeconds
        metrics["Min Packet Length"] = record.flowLength.minOrZero()
        metrics["Max Packet Length"] = record.flowLength.max
        metrics["Packet Length Mean"] = record.flowLength.mean()
        metrics["Packet Length Std"] = record.flowLength.std()
        metrics["Packet Length Variance"] = Math.pow(record.flowLength.std(), 2.0)
        metrics["FIN Flag Count"] = record.finCount.toDouble()
        metrics["SYN Flag Count"] = record.synCount.toDouble()
        metrics["RST Flag Count"] = record.rstCount.toDouble()
        metrics["PSH Flag Count"] = record.pshCount.toDouble()
        metrics["ACK Flag Count"] = record.ackCount.toDouble()
        metrics["URG Flag Count"] = record.urgCount.toDouble()
        metrics["CWE Flag Count"] = record.cwrCount.toDouble()
        metrics["ECE Flag Count"] = record.eceCount.toDouble()
        metrics["Down/Up Ratio"] =
            if (record.packetsFwd == 0) 0.0 else record.packetsBwd.toDouble() / record.packetsFwd
        metrics["Average Packet Size"] =
            if (totalPackets == 0) 0.0 else totalBytes.toDouble() / totalPackets
        metrics["Avg Fwd Segment Size"] =
            if (record.packetsFwd == 0) 0.0 else record.bytesFwd.toDouble() / record.packetsFwd
        metrics["Avg Bwd Segment Size"] =
            if (record.packetsBwd == 0) 0.0 else record.bytesBwd.toDouble() / record.packetsBwd
        metrics["Init_Win_bytes_forward"] = record.initWindowFwd.toDouble()
        metrics["Init_Win_bytes_backward"] = record.initWindowBwd.toDouble()
        metrics["act_data_pkt_fwd"] = record.actDataPktFwd.toDouble()
        metrics["min_seg_size_forward"] =
            if (record.minSegFwd == Int.MAX_VALUE) 0.0 else record.minSegFwd.toDouble()

        // The 12 approximated columns, marked as such in model_contract.json.
        metrics["Active Mean"] = record.activeDurations.mean()
        metrics["Active Std"] = record.activeDurations.std()
        metrics["Active Max"] = record.activeDurations.max
        metrics["Active Min"] = record.activeDurations.minOrZero()
        metrics["Idle Mean"] = record.idleDurations.mean()
        metrics["Idle Std"] = record.idleDurations.std()
        metrics["Idle Max"] = record.idleDurations.max
        metrics["Idle Min"] = record.idleDurations.minOrZero()
        metrics["Subflow Fwd Packets"] = record.packetsFwd.toDouble()
        metrics["Subflow Fwd Bytes"] = record.bytesFwd.toDouble()
        metrics["Subflow Bwd Packets"] = record.packetsBwd.toDouble()
        metrics["Subflow Bwd Bytes"] = record.bytesBwd.toDouble()

        val protocolName = when (record.key.protocol) {
            ParsedPacket.PROTO_TCP -> "TCP"
            ParsedPacket.PROTO_UDP -> "UDP"
            ParsedPacket.PROTO_ICMP -> "ICMP"
            else -> "IP"
        }
        return ClosedFlow(
            key = record.key,
            serverIp = record.serverIp,
            serverPort = record.serverPort,
            protocol = protocolName,
            startMs = record.startMs,
            endMs = endMs,
            packetsFwd = record.packetsFwd,
            packetsBwd = record.packetsBwd,
            bytesFwd = record.bytesFwd,
            bytesBwd = record.bytesBwd,
            metrics = metrics,
            domain = record.domain
        )
    }
}
