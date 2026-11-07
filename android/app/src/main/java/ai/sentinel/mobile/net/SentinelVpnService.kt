package ai.sentinel.mobile.net

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import ai.sentinel.mobile.AppContainer
import ai.sentinel.mobile.SentinelApp
import ai.sentinel.mobile.data.db.AlertEntity
import ai.sentinel.mobile.data.db.BlockEventEntity
import ai.sentinel.mobile.data.db.FlowEntity
import ai.sentinel.mobile.data.db.RuleEntity
import ai.sentinel.mobile.model.Category
import ai.sentinel.mobile.model.DetectionSource
import ai.sentinel.mobile.model.FeatureBuilder
import ai.sentinel.mobile.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The tunnel.
 *
 * Two honest operating modes, both of which keep the device online:
 *
 *  - DNS_ONLY (protection): only DNS and user-blocked address ranges are routed into the
 *    tunnel. DNS is relayed with a protected socket, so name resolution works. Blocked
 *    destinations enter the tunnel and are dropped, which is real blocking.
 *  - CAPTURE: every address is routed in for a bounded window. DNS is still relayed; all other
 *    packets are counted and dropped, so the device has no internet for the duration. This is
 *    what makes full 70-feature flow observation and on-device inference possible without a
 *    userspace TCP stack. The window ends automatically.
 *
 * Nothing is forwarded for TCP, because injecting TCP back out requires a full userspace
 * stack. That limitation is stated in the app rather than hidden.
 */
class SentinelVpnService : VpnService() {

    companion object {
        const val ACTION_START_DNS = "ai.sentinel.mobile.action.START_DNS"
        const val ACTION_START_CAPTURE = "ai.sentinel.mobile.action.START_CAPTURE"
        const val ACTION_STOP = "ai.sentinel.mobile.action.STOP"
        const val EXTRA_CAPTURE_SECONDS = "capture_seconds"

        fun startDnsOnly(context: Context) {
            val intent = Intent(context, SentinelVpnService::class.java).setAction(ACTION_START_DNS)
            context.startForegroundService(intent)
        }

        fun startCapture(context: Context, seconds: Int) {
            val intent = Intent(context, SentinelVpnService::class.java)
                .setAction(ACTION_START_CAPTURE)
                .putExtra(EXTRA_CAPTURE_SECONDS, seconds)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SentinelVpnService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private lateinit var container: AppContainer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running = false

    private var tunnel: ParcelFileDescriptor? = null
    private var outputStream: FileOutputStream? = null
    private var readerThread: Thread? = null
    private var sweepThread: Thread? = null
    private val writeLock = Any()

    private val tracker = FlowTracker()
    private var policy: PolicyEngine = PolicyEngine()
    private var rulesByKey: Map<String, RuleEntity> = emptyMap()
    private var blocklist: Blocklist = Blocklist.empty()
    private var ruleEngine: RuleEngine = RuleEngine(blocklist)

    private var dnsServers: List<String> = emptyList()
    private var dnsUpstream: String = "1.1.1.1"

    private var captureMode = false
    private var captureDeadlineMs = 0L

    private val dnsExecutor = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(64),
        ThreadPoolExecutor.DiscardPolicy()
    )

    private val droppedDns = AtomicLong(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as SentinelApp).container
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel("Stopped by user")
                return START_NOT_STICKY
            }
            ACTION_START_CAPTURE -> {
                val seconds = intent.getIntExtra(EXTRA_CAPTURE_SECONDS, 60).coerceIn(15, 300)
                beginTunnel(capture = true, captureSeconds = seconds)
                return START_NOT_STICKY
            }
            ACTION_START_DNS -> {
                beginTunnel(capture = false, captureSeconds = 0)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun beginTunnel(capture: Boolean, captureSeconds: Int) {
        if (running) stopTunnel("Restarting")

        captureMode = capture
        dnsServers = resolveDnsServers()
        dnsUpstream = dnsServers.firstOrNull() ?: "1.1.1.1"
        blocklist = Blocklist.load(this)
        ruleEngine = RuleEngine(blocklist)

        val storedRules = kotlinx.coroutines.runBlocking {
            runCatching { container.rules.active() }.getOrDefault(emptyList())
        }
        rulesByKey = storedRules.associateBy { ruleKey(it) }
        policy = PolicyEngine(dnsServers.toSet(), null)
        policy.updateRules(storedRules.map { it.toPolicyRule() })

        val builder = Builder()
            .setSession("SentinelAI")
            .setMtu(1500)
            .addAddress(PolicyEngine.TUNNEL_ADDRESS, 32)
            .addDnsServer(dnsUpstream)

        if (capture) {
            builder.addRoute("0.0.0.0", 0)
        } else {
            var routeCount = 0
            dnsServers.forEach { server ->
                runCatching { builder.addRoute(server, 32) }.onSuccess { routeCount++ }
            }
            policy.routesFor(policy.activeRules()).forEach { route ->
                runCatching { builder.addRoute(route.address, route.prefix) }.onSuccess { routeCount++ }
            }
            if (routeCount == 0) runCatching { builder.addRoute(dnsUpstream, 32) }
        }

        // Our own traffic must never enter our tunnel, or the DNS relay would feed itself.
        runCatching { builder.addDisallowedApplication(packageName) }

        val established = try {
            builder.establish()
        } catch (t: Throwable) {
            null
        }
        if (established == null) {
            TunnelState.fail("Android refused to establish the tunnel")
            stopSelf()
            return
        }

        tunnel = established
        outputStream = FileOutputStream(established.fileDescriptor)
        running = true
        tracker.sweep(System.currentTimeMillis())

        val settings = container.settings.current()
        if (capture) {
            captureDeadlineMs = SystemClock.elapsedRealtime() + captureSeconds * 1000L
        }
        TunnelState.update {
            it.copy(
                status = if (capture) TunnelState.Status.CAPTURE else TunnelState.Status.DNS_ONLY,
                startedAt = System.currentTimeMillis(),
                packetsIn = 0,
                bytesIn = 0,
                dnsQueries = 0,
                flowsObserved = 0,
                flowsPersisted = 0,
                droppedPackets = 0,
                queueDrops = 0,
                blockedPackets = 0,
                activeFlows = 0,
                captureRemainingMs = if (capture) captureSeconds * 1000L else 0L,
                lastError = null,
                dnsUpstream = dnsUpstream
            )
        }

        startForeground(
            Notifications.FOREGROUND_ID,
            Notifications.foreground(
                this,
                if (capture) "Capturing traffic" else "SentinelAI protection",
                if (capture) "Internet is paused for ${captureSeconds}s while flows are recorded"
                else "Monitoring DNS · ${policy.activeRules().size} firewall rules"
            )
        )

        val input = FileInputStream(established.fileDescriptor)
        readerThread = Thread({ readLoop(input) }, "sentinel-tunnel").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
        sweepThread = Thread({ sweepLoop() }, "sentinel-sweep").apply { start() }

        container.settings.setProtectionEnabled(true)
        if (!capture && !settings.notificationsEnabled) {
            // notification channel stays; nothing else to do here
        }
    }

    private fun readLoop(input: FileInputStream) {
        val buffer = ByteArray(65535)
        try {
            while (running) {
                val read = try {
                    input.read(buffer)
                } catch (t: Throwable) {
                    break
                }
                if (!running) break
                if (read <= 0) continue
                handlePacket(buffer, read)
            }
        } catch (t: Throwable) {
            if (running) {
                scope.launch { }.invokeOnCompletion { }
                stopTunnel("Tunnel read loop failed: ${t.message ?: t::class.java.simpleName}")
                return
            }
        }
        if (running) {
            // Loop ended while we still believed we were running: never leave the device offline.
            stopTunnel("Tunnel closed unexpectedly")
        }
    }

    private fun handlePacket(buffer: ByteArray, length: Int) {
        val packet = PacketParser.parse(buffer, length, System.currentTimeMillis())
        TunnelState.update {
            it.copy(
                packetsIn = it.packetsIn + 1,
                bytesIn = it.bytesIn + length,
                activeFlows = tracker.activeCount
            )
        }
        TunnelState.pushSeries()
        if (packet == null) {
            TunnelState.update { it.copy(droppedPackets = it.droppedPackets + 1) }
            TunnelState.pushSeries()
            return
        }

        val closed = tracker.onPacket(packet)
        closed.forEach { persistFlow(it) }

        if (packet.isDns && packet.dstPort == 53) {
            handleDnsQuery(packet)
            return
        }

        val decision = policy.evaluate(packet.dstIp, packet.dstPort, null)
        if (decision is Decision.Block) {
            recordBlock(decision.rule, packet.dstIp, packet.dstPort, packet.payloadLength.toLong())
            return
        }
        if (decision is Decision.Allow) {
            return
        }

        // Nothing else is routed in DNS_ONLY mode. In capture mode everything is, and dropping
        // is the documented behaviour for the bounded window.
        TunnelState.update { it.copy(droppedPackets = it.droppedPackets + 1) }
        TunnelState.pushSeries()
    }

    private fun handleDnsQuery(packet: ParsedPacket) {
        val domain = DnsInspector.queryName(packet.payload)
        val key = FlowKey.of(packet)
        tracker.noteDomain(key, domain)

        if (domain != null) {
            TunnelState.update { it.copy(dnsQueries = it.dnsQueries + 1) }
            TunnelState.pushSeries()
            val decision = policy.evaluate(packet.dstIp, 53, domain)
            if (decision is Decision.Block) {
                // Dropping the query means the name never resolves: real domain blocking.
                recordBlock(decision.rule, packet.dstIp, 53, packet.payloadLength.toLong())
                return
            }
            ruleEngine.onQuery(domain, System.currentTimeMillis()).forEach { finding ->
                raiseRuleAlert(finding.category, finding.severity, finding.reason, packet.dstIp, 53, finding.domain)
            }
        }

        val payload = packet.payload
        if (payload == null) {
            TunnelState.update { it.copy(droppedPackets = it.droppedPackets + 1) }
            return
        }
        if (dnsExecutor.queue.remainingCapacity() <= 0) {
            droppedDns.incrementAndGet()
            TunnelState.update { it.copy(queueDrops = it.queueDrops + 1) }
            TunnelState.pushSeries()
            return
        }
        dnsExecutor.execute { relayDns(packet, payload) }
    }

    private fun relayDns(query: ParsedPacket, payload: ByteArray) {
        if (query.ipVersion != 4) return
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            if (!protect(socket)) {
                return
            }
            socket.soTimeout = 5000
            val upstream = InetAddress.getByName(dnsUpstream)
            socket.send(DatagramPacket(payload, payload.size, upstream, 53))

            val responseBuffer = ByteArray(4096)
            val response = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(response)

            val answerBytes = response.data.copyOfRange(0, response.length)
            val addresses = DnsInspector.answerAddresses(answerBytes)
            val domain = DnsInspector.queryName(payload)
            if (domain != null && addresses.isNotEmpty()) {
                policy.learnDomain(domain, addresses)
                tracker.noteDomain(FlowKey.of(query), domain)
                val rules = policy.activeRules()
                if (rules.any { it.type == "domain" }) {
                    // Newly resolved addresses may match domain rules already in force.
                    rulesByKey.values.filter { it.type == "domain" }.forEach { rule ->
                        if (domain.endsWith(rule.value.trim().lowercase().trimStart('.'))) {
                            // Routes cannot change without a restart; the next tunnel start picks them up.
                        }
                    }
                }
            }

            val replyBytes = PacketParser.buildUdpIpv4(
                srcIp = query.dstIp,
                dstIp = query.srcIp,
                srcPort = 53,
                dstPort = query.srcPort,
                payload = answerBytes
            )
            synchronized(writeLock) {
                try {
                    outputStream?.write(replyBytes)
                    outputStream?.flush()
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            // A failed relay leaves the query unanswered, which the app sees as a DNS timeout.
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun sweepLoop() {
        while (running) {
            try {
                Thread.sleep(5000)
            } catch (_: InterruptedException) {
                return
            }
            if (!running) return
            val now = System.currentTimeMillis()
            tracker.sweep(now).forEach { persistFlow(it) }
            val remaining = if (captureMode) {
                (captureDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            } else 0L
            TunnelState.update { it.copy(activeFlows = tracker.activeCount, captureRemainingMs = remaining) }
        TunnelState.pushSeries()
        TunnelState.tick()
            if (captureMode && remaining <= 0) {
                stopTunnel("Capture window finished")
                return
            }
        }
    }

    private fun persistFlow(flow: ClosedFlow) {
        scope.launch {
            try {
                val settings = container.settings.current()
                val contract = container.contract
                var categoryKey: String? = null
                var confidence: Double? = null
                var source = DetectionSource.NONE.key

                if (captureMode && flow.totalPackets >= 2) {
                    val features = FeatureBuilder.build(contract, flow.metrics)
                    val prediction = container.detector.predict(features)
                    val predicted = prediction?.category
                    if (predicted != null && prediction.confidence >= settings.confidenceThreshold) {
                        categoryKey = predicted.key
                        confidence = prediction.confidence
                        source = DetectionSource.AI.key
                    } else {
                        source = DetectionSource.UNCLASSIFIED.key
                    }
                }

                val entity = FlowEntity(
                    tsStart = flow.startMs,
                    tsEnd = flow.endMs,
                    srcIp = flow.key.ipA,
                    srcPort = flow.key.portA,
                    dstIp = flow.serverIp,
                    dstPort = flow.serverPort,
                    protocol = flow.protocol,
                    packetsFwd = flow.packetsFwd,
                    packetsBwd = flow.packetsBwd,
                    bytesFwd = flow.bytesFwd,
                    bytesBwd = flow.bytesBwd,
                    durationMs = flow.durationMs,
                    category = categoryKey,
                    confidence = confidence,
                    source = source,
                    provenance = "captured",
                    ruleFlags = null,
                    modelVersion = if (source == DetectionSource.AI.key) contract.modelVersion else null,
                    captureMode = if (captureMode) "capture" else "dns",
                    domain = flow.domain
                )
                val flowId = container.flows.insert(entity)
                TunnelState.update {
                    it.copy(
                        flowsObserved = it.flowsObserved + 1,
                        flowsPersisted = it.flowsPersisted + 1
                    )
                }
                TunnelState.pushSeries()

                if (categoryKey != null && Category.fromKey(categoryKey)?.isThreat == true) {
                    val alert = AlertEntity(
                        ts = flow.endMs,
                        kind = DetectionSource.AI.key,
                        category = categoryKey,
                        severity = Category.fromKey(categoryKey)?.severity ?: "medium",
                        confidence = confidence,
                        dstIp = flow.serverIp,
                        dstPort = flow.serverPort,
                        reason = "Model classified this flow as $categoryKey at " +
                            "${FeatureBuilder.build(contract, flow.metrics).size} input features",
                        flowId = flowId,
                        domain = flow.domain,
                        topFeatures = FeatureBuilder.describe(flow.metrics)
                    )
                    val alertId = container.alerts.insert(alert)
                    if (settings.notificationsEnabled) {
                        Notifications.threat(this@SentinelVpnService, alert.copy(id = alertId))
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun raiseRuleAlert(
        category: String,
        severity: String,
        reason: String,
        dstIp: String,
        dstPort: Int,
        domain: String?
    ) {
        scope.launch {
            try {
                val alert = AlertEntity(
                    ts = System.currentTimeMillis(),
                    kind = DetectionSource.RULE.key,
                    category = category,
                    severity = severity,
                    confidence = null,
                    dstIp = dstIp,
                    dstPort = dstPort,
                    reason = reason,
                    domain = domain
                )
                val id = container.alerts.insert(alert)
                if (container.settings.current().notificationsEnabled) {
                    Notifications.threat(this@SentinelVpnService, alert.copy(id = id))
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun recordBlock(rule: PolicyRule, dstIp: String, dstPort: Int, bytes: Long) {
        TunnelState.update {
            it.copy(blockedPackets = it.blockedPackets + 1, droppedPackets = it.droppedPackets + 1)
        }
        TunnelState.pushSeries()
        scope.launch {
            try {
                container.blocks.insert(
                    BlockEventEntity(
                        ts = System.currentTimeMillis(),
                        dstIp = dstIp,
                        dstPort = dstPort,
                        ruleId = rule.id,
                        ruleDescription = policy.describe(rule),
                        protocol = "IP",
                        bytesDropped = bytes
                    )
                )
                container.rules.recordHit(rule.id)
            } catch (_: Throwable) {
            }
        }
    }

    private fun stopTunnel(reason: String) {
        if (!running && tunnel == null) {
            stopSelf()
            return
        }
        running = false
        captureMode = false
        dnsExecutor.shutdownNow()

        val leftovers = runCatching { tracker.drain() }.getOrDefault(emptyList())
        leftovers.forEach { persistFlow(it) }

        try {
            readerThread?.interrupt()
        } catch (_: Throwable) {
        }
        try {
            sweepThread?.interrupt()
        } catch (_: Throwable) {
        }
        try {
            synchronized(writeLock) {
                outputStream?.flush()
                outputStream = null
            }
        } catch (_: Throwable) {
        }
        try {
            tunnel?.close()
        } catch (_: Throwable) {
        }
        tunnel = null
        readerThread = null
        sweepThread = null

        container.settings.setProtectionEnabled(false)
        TunnelState.update {
            it.copy(
                status = if (reason.startsWith("Tunnel") || reason.contains("failed")) {
                    TunnelState.Status.ERROR
                } else TunnelState.Status.STOPPED,
                lastError = if (it.status == TunnelState.Status.ERROR) reason else null,
                captureRemainingMs = 0
            )
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf()
        scope.cancel()
    }

    override fun onRevoke() {
        stopTunnel("VPN permission was revoked by Android")
        super.onRevoke()
    }

    override fun onDestroy() {
        running = false
        try {
            tunnel?.close()
        } catch (_: Throwable) {
        }
        tunnel = null
        super.onDestroy()
    }

    private fun resolveDnsServers(): List<String> {
        return try {
            val manager = getSystemService(ConnectivityManager::class.java)
            val network = manager?.activeNetwork
            val properties = network?.let { manager.getLinkProperties(it) }
            val servers = properties?.dnsServers
                ?.mapNotNull { it.hostAddress }
                ?.filter { PolicyEngine.parseIpv4(it) != null }
                .orEmpty()
            servers.ifEmpty { listOf("1.1.1.1") }
        } catch (_: Throwable) {
            listOf("1.1.1.1")
        }
    }

    private fun ruleKey(rule: RuleEntity): String = "${rule.id}:${rule.type}:${rule.value}"

    private fun RuleEntity.toPolicyRule(): PolicyRule = PolicyRule(
        id = id,
        type = type,
        value = value,
        action = action,
        enabled = enabled,
        expiresAt = expiresAt
    )
}
