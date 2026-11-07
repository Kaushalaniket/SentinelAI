package ai.sentinel.mobile

import ai.sentinel.mobile.net.Blocklist
import ai.sentinel.mobile.net.DnsInspector
import ai.sentinel.mobile.net.FlowTracker
import ai.sentinel.mobile.net.PacketParser
import ai.sentinel.mobile.net.ParsedPacket
import ai.sentinel.mobile.net.PolicyEngine
import ai.sentinel.mobile.net.PolicyRule
import ai.sentinel.mobile.net.RuleEngine
import ai.sentinel.mobile.net.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the parts of the tunnel that must be provably correct without a device:
 * packet parsing, flow aggregation, firewall evaluation and DNS reading.
 */
class SentinelCoreTest {

    // ---------- packet builders ----------

    private fun ipv4(
        protocol: Int,
        src: String = "10.0.0.5",
        dst: String = "93.184.216.34",
        transport: ByteArray
    ): ByteArray {
        val total = 20 + transport.size
        val packet = ByteArray(total)
        packet[0] = 0x45
        packet[2] = ((total shr 8) and 0xFF).toByte()
        packet[3] = (total and 0xFF).toByte()
        packet[8] = 64
        packet[9] = protocol.toByte()
        writeIpv4(packet, 12, src)
        writeIpv4(packet, 16, dst)
        transport.copyInto(packet, 20)
        return packet
    }

    private fun tcp(
        srcPort: Int,
        dstPort: Int,
        flags: Int = ParsedPacket.FLAG_PSH or ParsedPacket.FLAG_ACK,
        window: Int = 64240,
        payloadLength: Int = 0
    ): ByteArray {
        val packet = ByteArray(20 + payloadLength)
        packet[0] = ((srcPort shr 8) and 0xFF).toByte()
        packet[1] = (srcPort and 0xFF).toByte()
        packet[2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[3] = (dstPort and 0xFF).toByte()
        packet[12] = 0x50
        packet[13] = flags.toByte()
        packet[14] = ((window shr 8) and 0xFF).toByte()
        packet[15] = (window and 0xFF).toByte()
        if (payloadLength > 0) {
            for (index in 0 until payloadLength) packet[20 + index] = 'a'.code.toByte()
        }
        return packet
    }

    private fun udp(srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(8 + payload.size)
        packet[0] = ((srcPort shr 8) and 0xFF).toByte()
        packet[1] = (srcPort and 0xFF).toByte()
        packet[2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[3] = (dstPort and 0xFF).toByte()
        val length = 8 + payload.size
        packet[4] = ((length shr 8) and 0xFF).toByte()
        packet[5] = (length and 0xFF).toByte()
        payload.copyInto(packet, 8)
        return packet
    }

    private fun ipv6(protocol: Int, transport: ByteArray): ByteArray {
        val packet = ByteArray(40 + transport.size)
        packet[0] = 0x60
        packet[4] = ((transport.size shr 8) and 0xFF).toByte()
        packet[5] = (transport.size and 0xFF).toByte()
        packet[6] = protocol.toByte()
        packet[7] = 64
        // source ::1, destination 2001:db8::1
        packet[39] = 1
        packet[24 + 0] = 0x20.toByte()
        packet[24 + 1] = 0x01.toByte()
        packet[24 + 2] = 0x0d.toByte()
        packet[24 + 3] = 0xb8.toByte()
        packet[24 + 15] = 1
        transport.copyInto(packet, 40)
        return packet
    }

    private fun writeIpv4(target: ByteArray, offset: Int, ip: String) {
        ip.split('.').forEachIndexed { index, part ->
            target[offset + index] = part.toInt().toByte()
        }
    }

    private fun dnsQuery(name: String): ByteArray {
        val out = ArrayList<Int>()
        out.add(0x12); out.add(0x34) // id
        out.add(0x01); out.add(0x00) // flags: standard query
        out.add(0x00); out.add(0x01) // qdcount
        repeat(6) { out.add(0x00) }
        name.split('.').forEach { label ->
            out.add(label.length)
            label.forEach { out.add(it.code) }
        }
        out.add(0x00)
        out.add(0x00); out.add(0x01) // type A
        out.add(0x00); out.add(0x01) // class IN
        return ByteArray(out.size) { out[it].toByte() }
    }

    // ---------- parser ----------

    @Test
    fun parsesIpv4TcpHeader() {
        val packet = ipv4(ParsedPacket.PROTO_TCP, transport = tcp(51000, 443, payloadLength = 100))
        val parsed = PacketParser.parse(packet, packet.size, 1_000L)
        assertNotNull(parsed)
        parsed!!
        assertEquals(ParsedPacket.PROTO_TCP, parsed.protocol)
        assertEquals(51000, parsed.srcPort)
        assertEquals(443, parsed.dstPort)
        assertEquals(100, parsed.payloadLength)
        assertEquals(64240, parsed.window)
        assertTrue(parsed.isTcp)
        assertFalse(parsed.isDns)
    }

    @Test
    fun parsesIpv6Tcp() {
        val packet = ipv6(ParsedPacket.PROTO_TCP, tcp(50000, 80))
        val parsed = PacketParser.parse(packet, packet.size, 1_000L)
        assertNotNull(parsed)
        assertEquals(6, parsed!!.ipVersion)
        assertEquals(80, parsed.dstPort)
        assertEquals("2001:db8:0:0:0:0:0:1", parsed.dstIp)
    }

    @Test
    fun detectsDnsUdp() {
        val payload = dnsQuery("example.com")
        val packet = ipv4(ParsedPacket.PROTO_UDP, transport = udp(40000, 53, payload))
        val parsed = PacketParser.parse(packet, packet.size, 1_000L)
        assertNotNull(parsed)
        assertTrue(parsed!!.isDns)
        assertEquals("example.com", DnsInspector.queryName(parsed.payload))
    }

    @Test
    fun rejectsTruncatedAndMalformedPackets() {
        val packet = ipv4(ParsedPacket.PROTO_TCP, transport = tcp(1, 2))
        assertNull(PacketParser.parse(packet, 10, 0L))
        assertNull(PacketParser.parse(ByteArray(64), 64, 0L))
        val badIhl = packet.copyOf()
        badIhl[0] = 0x40 // IHL 0
        assertNull(PacketParser.parse(badIhl, badIhl.size, 0L))
    }

    @Test
    fun countsFlagsAndBuildsValidUdpReply() {
        val syn = ipv4(ParsedPacket.PROTO_TCP, transport = tcp(5000, 443, flags = ParsedPacket.FLAG_SYN))
        val parsed = PacketParser.parse(syn, syn.size, 0L)!!
        assertTrue(parsed.isSyn)

        val reply = PacketParser.buildUdpIpv4("1.1.1.1", "10.0.0.5", 53, 40000, ByteArray(20))
        assertEquals(48, reply.size)
        val reparsed = PacketParser.parse(reply, reply.size, 0L)
        assertNotNull(reparsed)
        assertEquals(53, reparsed!!.srcPort)
        assertEquals(40000, reparsed.dstPort)
        assertEquals("1.1.1.1", reparsed.srcIp)
        // IPv4 header checksum must validate to zero when recomputed over the header.
        assertEquals(0, PacketParser.checksum(reply, 0, 20))
    }

    // ---------- flow tracker ----------

    @Test
    fun aggregatesBidirectionalFlowAndCloses() {
        val tracker = FlowTracker(maxFlows = 16, idleTimeoutMs = 1_000, absoluteTimeoutMs = 60_000)
        val out = ipv4(ParsedPacket.PROTO_TCP, src = "10.0.0.5", dst = "93.184.216.34",
            transport = tcp(51000, 443, flags = ParsedPacket.FLAG_SYN, payloadLength = 0))
        val back = ipv4(ParsedPacket.PROTO_TCP, src = "93.184.216.34", dst = "10.0.0.5",
            transport = tcp(443, 51000, flags = ParsedPacket.FLAG_SYN or ParsedPacket.FLAG_ACK))

        tracker.onPacket(PacketParser.parse(out, out.size, 1_000L)!!)
        tracker.onPacket(PacketParser.parse(back, back.size, 1_050L)!!)
        assertEquals(1, tracker.activeCount)

        val closed = tracker.sweep(5_000L)
        assertEquals(1, closed.size)
        val flow = closed.first()
        assertEquals("93.184.216.34", flow.serverIp)
        assertEquals(443, flow.serverPort)
        assertEquals(1, flow.packetsFwd)
        assertEquals(1, flow.packetsBwd)
        assertTrue(flow.metrics.containsKey("Flow IAT Mean"))
        assertEquals(443.0, flow.metrics["Destination Port"]!!, 0.0)
        assertEquals(0, tracker.activeCount)
    }

    @Test
    fun respectsCapacityWithoutGrowing() {
        val tracker = FlowTracker(maxFlows = 8)
        for (index in 0 until 40) {
            val packet = ipv4(
                ParsedPacket.PROTO_UDP,
                dst = "10.0.0.${index + 1}",
                transport = udp(1000 + index, 5353, ByteArray(4))
            )
            tracker.onPacket(PacketParser.parse(packet, packet.size, 1_000L + index)!!)
        }
        assertTrue("flow table must stay bounded", tracker.activeCount <= 8)
        assertTrue(tracker.evictionCount > 0)
    }

    // ---------- policy ----------

    private fun rule(
        id: Long,
        type: String,
        value: String,
        action: String = "block",
        enabled: Boolean = true,
        expiresAt: Long? = null
    ) = PolicyRule(id, type, value, action, enabled, expiresAt)

    @Test
    fun allowTakesPrecedenceOverBlock() {
        val engine = PolicyEngine(setOf("1.1.1.1"), null)
        engine.updateRules(
            listOf(
                rule(1, "cidr", "93.184.216.0/24"),
                rule(2, "ip", "93.184.216.34", action = "allow")
            )
        )
        val decision = engine.evaluate("93.184.216.34", 443, null)
        assertTrue(decision is Decision.Allow)

        val blocked = engine.evaluate("93.184.216.40", 443, null)
        assertTrue(blocked is Decision.Block)
    }

    @Test
    fun cidrBoundariesMatch() {
        assertTrue(PolicyEngine.cidrContains("10.0.0.0/8", "10.255.1.2"))
        assertFalse(PolicyEngine.cidrContains("10.0.0.0/8", "11.0.0.1"))
        assertTrue(PolicyEngine.cidrContains("203.0.113.0/24", "203.0.113.255"))
        assertFalse(PolicyEngine.cidrContains("203.0.113.0/24", "203.0.114.1"))
        assertTrue(PolicyEngine.cidrContains("203.0.113.10/32", "203.0.113.10"))
    }

    @Test
    fun expiredAndDisabledRulesAreIgnored() {
        val engine = PolicyEngine()
        engine.updateRules(
            listOf(
                rule(1, "ip", "1.2.3.4", expiresAt = System.currentTimeMillis() - 1000),
                rule(2, "ip", "5.6.7.8", enabled = false)
            )
        )
        assertTrue(engine.evaluate("1.2.3.4", 80, null) is Decision.None)
        assertTrue(engine.evaluate("5.6.7.8", 80, null) is Decision.None)
    }

    @Test
    fun rejectsSelfLockoutRules() {
        val engine = PolicyEngine(setOf("192.168.1.1"))
        assertNotNull(engine.validate("ip", PolicyEngine.TUNNEL_ADDRESS))
        assertNotNull(engine.validate("ip", "192.168.1.1"))
        assertNotNull(engine.validate("port", "53"))
        assertNotNull(engine.validate("cidr", "0.0.0.0/8"))
        assertNotNull(engine.validate("cidr", "203.0.113.0/2"))
        assertNull(engine.validate("ip", "203.0.113.10"))
        assertNull(engine.validate("domain", "ads.example.com"))
        assertNull(engine.validate("cidr", "203.0.113.0/24"))
    }

    @Test
    fun domainRulesMatchSubdomainsAndResolvedRoutes() {
        val engine = PolicyEngine()
        engine.updateRules(listOf(rule(1, "domain", "example.com")))
        assertTrue(engine.evaluate("93.184.216.34", 443, "ads.example.com") is Decision.Block)
        assertTrue(engine.evaluate("93.184.216.34", 443, "example.com") is Decision.Block)
        assertTrue(engine.evaluate("93.184.216.34", 443, "notexample.com") is Decision.None)

        engine.learnDomain("example.com", listOf("93.184.216.34"))
        val routes = engine.routesFor(engine.activeRules())
        assertEquals(1, routes.size)
        assertEquals("93.184.216.34/32", routes.first().description)
    }

    // ---------- DNS + rules ----------

    @Test
    fun readsAnswersFromDnsResponse() {
        val query = dnsQuery("example.com")
        val response = query.copyOf(query.size + 16)
        response[2] = 0x81.toByte() // response flag
        response[3] = 0x80.toByte()
        response[6] = 0x00; response[7] = 0x01 // one answer
        var offset = response.size - 16
        response[offset++] = 0xC0.toByte(); response[offset++] = 0x0C.toByte() // pointer to name
        response[offset++] = 0x00; response[offset++] = 0x01 // type A
        response[offset++] = 0x00; response[offset++] = 0x01 // class
        response[offset++] = 0x00; response[offset++] = 0x00
        response[offset++] = 0x00; response[offset++] = 0x3C // ttl
        response[offset++] = 0x00; response[offset++] = 0x04 // rdlength
        response[offset++] = 93; response[offset++] = 184.toByte()
        response[offset++] = 216.toByte(); response[offset] = 34
        assertEquals(listOf("93.184.216.34"), DnsInspector.answerAddresses(response))
    }

    @Test
    fun ruleEngineFiresOnlyOnMeasuredConditions() {
        val engine = RuleEngine(Blocklist.empty())
        assertTrue(engine.onQuery("example.com", 1_000L).isEmpty())
        assertTrue(DnsInspector.isSuspiciousName("a8f3k2m9q1z7x4b6c0d2.net"))
        assertFalse(DnsInspector.isSuspiciousName("example.com"))
        assertTrue(engine.onQuery("time.example.com", 2_000L).isEmpty())
    }

    @Test
    fun flowTrackerReportsNoFabricatedCategory() {
        val tracker = FlowTracker()
        assertEquals(0, tracker.activeCount)
        assertTrue(tracker.drain().isEmpty())
    }
}
