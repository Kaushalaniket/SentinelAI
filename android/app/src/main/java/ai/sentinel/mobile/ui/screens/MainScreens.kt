package ai.sentinel.mobile.ui.screens



import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.sentinel.mobile.data.db.FlowEntity
import ai.sentinel.mobile.model.Category
import ai.sentinel.mobile.model.DetectionSource
import ai.sentinel.mobile.net.TunnelState
import ai.sentinel.mobile.ui.components.Badge
import ai.sentinel.mobile.ui.components.EmptyState
import ai.sentinel.mobile.ui.components.InfoRow
import ai.sentinel.mobile.ui.components.LineChart
import ai.sentinel.mobile.ui.components.LiveGauge
import ai.sentinel.mobile.ui.components.Panel
import ai.sentinel.mobile.ui.components.SectionHeading
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import ai.sentinel.mobile.ui.components.SparkLine
import ai.sentinel.mobile.ui.components.StatTile
import ai.sentinel.mobile.ui.theme.SentinelAmber
import ai.sentinel.mobile.ui.theme.SentinelBackground
import ai.sentinel.mobile.ui.theme.SentinelBlue
import ai.sentinel.mobile.ui.theme.SentinelCard
import ai.sentinel.mobile.ui.theme.SentinelCyan
import ai.sentinel.mobile.ui.theme.SentinelGreen
import ai.sentinel.mobile.ui.theme.SentinelRed
import ai.sentinel.mobile.ui.theme.SentinelSurface
import ai.sentinel.mobile.ui.theme.SentinelTeal
import ai.sentinel.mobile.ui.theme.SentinelText
import ai.sentinel.mobile.ui.theme.SentinelTextSecondary
import ai.sentinel.mobile.ui.theme.SentinelTitleText
import ai.sentinel.mobile.ui.theme.SentinelWave
import ai.sentinel.mobile.ui.theme.SentinelLine
import ai.sentinel.mobile.ui.components.WaveLine
import ai.sentinel.mobile.ui.vm.AppViewModel
import ai.sentinel.mobile.util.Formatters

/** Convert a Long series (counter snapshot) to a Float series for the chart components. */
internal fun LongListToFloatList(series: List<Long>): List<Float> = series.map { it.toFloat() }

/** Convert a Long series to normalized percent (0..100) for the waveline. */
internal fun LongListToPercentSeries(series: List<Long>): List<Float> {
    if (series.isEmpty()) return emptyList()
    val max = series.maxOrNull()?.toFloat() ?: 1f
    return series.filter { it > 0L }.map { (it.toFloat() / max) * 100f }
}

@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = SentinelTitleText)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SentinelTextSecondary
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatTile(
                "Online",
                if (TunnelState.current().isRunning) "Yes" else "No",
                tint = if (TunnelState.current().isRunning) SentinelGreen else SentinelAmber,
                modifier = Modifier.weight(1f)
            )
        }
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProtectionCard(vm: AppViewModel, onEnableProtection: () -> Unit) {
    val snapshot by vm.tunnel.collectAsState()
    val settings by vm.settings.collectAsState()
    val running = snapshot.isRunning
    var showCaptureWarning by remember { mutableStateOf(false) }

    // Warn before capture if the device is on a network with active traffic
    val captureWarning = "Capture mode drops all non-DNS traffic for the full window (default ${settings.captureSeconds}s). The tablet will have no internet until the window ends. Confirm to proceed. This is a disruptive diagnostic test, not normal operation."

    Panel(
        title = when {
            snapshot.isCapture -> "Capture session running"
            running -> "Protection active"
            snapshot.status == TunnelState.Status.ERROR -> "Tunnel stopped with an error"
            else -> "Protection is off"
        },
        subtitle = when {
            snapshot.isCapture -> "Internet paused · ${snapshot.captureRemainingMs / 1000}s remaining"
            running -> "DNS monitoring and firewall rules enforced · Internet works normally"
            else -> "Nothing monitored · Enable protection to start"
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!running) {
                    OutlinedButton(onClick = onEnableProtection, modifier = Modifier.weight(1f)) {
                        Text("Enable protection")
                    }
                } else {
                    OutlinedButton(
                        onClick = { vm.stopTunnel() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = SentinelRed)
                    ) { Text("Emergency stop") }
                }
                OutlinedButton(
                    onClick = { showCaptureWarning = true },
                    enabled = !snapshot.isCapture,
                    modifier = Modifier.weight(1f)
                ) { Text("Capture ${settings.captureSeconds}s") }
            }
            Text(
                "Protection mode routes only DNS and user-blocked address ranges, so the tablet keeps internet. " +
                    "Capture mode is for full flow observation only and pauses internet for the whole window.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (snapshot.lastError != null) {
                Text(
                    snapshot.lastError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = SentinelRed
                )
            }
        }
    }

    if (showCaptureWarning) {
        AlertDialog(
            onDismissRequest = { showCaptureWarning = false },
            title = { Text("Confirm capture session", style = MaterialTheme.typography.titleMedium) },
            text = { Text(captureWarning, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    showCaptureWarning = false
                    vm.startCapture(settings.captureSeconds)
                }) {
                    Text("Start capture", color = SentinelRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCaptureWarning = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DashboardScreen(vm: AppViewModel, onEnableProtection: () -> Unit) {
    val dashboard = vm.dashboard
    val snapshot by vm.tunnel.collectAsState()
    val flows by vm.recentFlows.collectAsState()
    val packetSeries by vm.packetSeries.collectAsState()
    val bytesSeries by vm.bytesSeries.collectAsState()
    val dnsSeries by vm.dnsSeries.collectAsState()
    val blockSeries by vm.blockSeries.collectAsState()

    val packetFloats = LongListToFloatList(packetSeries)
    val bytesFloats = LongListToFloatList(bytesSeries)
    val dnsFloats = LongListToFloatList(dnsSeries)
    val blockFloats = LongListToFloatList(blockSeries)
    val liveBytes by vm.liveBytes.collectAsState()

    // Determine the visual state for status display
    val statusColor = when {
        snapshot.isCapture -> SentinelRed
        snapshot.isRunning -> SentinelTeal
        else -> SentinelAmber
    }

    val statusLabel = when {
        snapshot.isCapture -> "⬤ CAPTURE"
        snapshot.isRunning -> "⬤ PROTECT"
        else -> "○ STOPPED"
    }

    LaunchedEffect(snapshot.flowsPersisted, snapshot.blockedPackets) {
        vm.refreshDashboard()
    }

    ScreenScaffold(
        title = "Dashboard",
        subtitle = "Everything below is read from this tablet's own database."
    ) {
        ProtectionCard(vm, onEnableProtection)

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            StatTile("Flows 24h", dashboard.flows24h.toString(), modifier = Modifier.weight(1f))
            StatTile(
                "Threats 24h",
                dashboard.threats24h.toString(),
                modifier = Modifier.weight(1f),
                tint = if (dashboard.threats24h > 0) SentinelRed else MaterialTheme.colorScheme.onSurface
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            StatTile("Blocked 24h", dashboard.blocked24h.toString(), modifier = Modifier.weight(1f))
            StatTile("Observed", Formatters.bytes(dashboard.bytes24h), modifier = Modifier.weight(1f))
        }

        Panel(
            title = "Tunnel counters",
            subtitle = "Live values from the service, not estimates."
        ) {
            Column {
                LiveGauge("Status", statusLabel, color = statusColor.toString())
                LiveGauge("Packets", snapshot.packetsIn.toString(), color = "#3FB950")
                LiveGauge("Bytes", Formatters.bytes(snapshot.bytesIn), color = "#22D3EE")
                LiveGauge("DNS queries", snapshot.dnsQueries.toString(), color = "#17B4A0")
                LiveGauge("Active flows", snapshot.activeFlows.toString(), color = "#6366F1")
                LiveGauge("Flows stored", snapshot.flowsPersisted.toString(), color = "#2F81F7")
                LiveGauge("Dropped", snapshot.droppedPackets.toString(), color = "#D29922")
                LiveGauge("Blocked", snapshot.blockedPackets.toString(), color = "#E5534B")
            }
        }

        // ---- Live activity feed ----
        Panel(title = "What is happening right now", subtitle = "Counters update as packet activity occurs.") {
            Column(Modifier.height(120.dp)) {
                SparkLine(values = packetFloats, color = SentinelLine)
                SparkLine(values = bytesFloats, color = SentinelWave)
                SparkLine(values = dnsFloats, color = SentinelTeal)
                SparkLine(values = blockFloats, color = SentinelRed)
            }
        }

        Panel(
            title = "Bytes flowing by (live)",
            subtitle = "A waveline — bar height tracks incoming bytes. The app runs with " + Formatters.bytes(liveBytes) + " bytes in the last sample."
        ) {
            WaveLine(values = bytesFloats, activeColor = SentinelWave, idleColor = SentinelTextSecondary, barCount = 24)
        }

        Panel(
            title = "Detected categories", subtitle = "Last 24 hours, from stored flows."
        ) {
            if (dashboard.categories.isEmpty()) {
                EmptyState(
                    "No classified flows yet",
                    "On-device classification runs on flows captured in a capture session. " +
                        "Start one from the protection card above."
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    dashboard.categories.forEach { row ->
                        val category = Category.fromKey(row.category)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Badge(category?.label ?: row.category, category?.color ?: SentinelAmber)
                            Spacer(Modifier.weight(1f))
                            Text(row.hits.toString(), style = MaterialTheme.typography.bodyMedium, color = SentinelText)
                        }
                    }
                }
            }
        }

        Panel(title = "Top destinations", subtitle = "Last 24 hours by flow count.") {
            if (dashboard.topDestinations.isEmpty()) {
                EmptyState(
                    "Nothing recorded yet",
                    "Destinations appear here once the tunnel has observed traffic."
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    dashboard.topDestinations.forEach { destination ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                destination.dstIp,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(1f),
                                color = SentinelText
                            )
                            Text(
                                "${destination.hits} flows · ${Formatters.bytes(destination.bytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = SentinelTextSecondary
                            )
                        }
                    }
                }
            }
        }

        Panel(title = "Latest observed flows", subtitle = "Top 6 most recent captures.") {
            if (flows.isEmpty()) {
                EmptyState("No flows", "Flows are recorded while protection or a capture session runs.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    flows.take(6).forEach { flow -> FlowRowCompact(flow) }
                }
            }
        }
    }
}

@Composable
private fun FlowRowCompact(flow: FlowEntity) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${flow.dstIp}:${flow.dstPort}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = SentinelText
            )
            Text(
                "${flow.protocol} · ${flow.packetsFwd + flow.packetsBwd} pkts · ${Formatters.bytes(flow.bytesFwd + flow.bytesBwd)}",
                style = MaterialTheme.typography.labelSmall,
                color = SentinelTextSecondary
            )
        }
        val category = Category.fromKey(flow.category)
        if (category != null) {
            Badge(category.label, category.color)
        } else {
            Badge(DetectionSource.fromKey(flow.source).label, SentinelTextSecondary)
        }
    }
}

@Composable
fun LiveMonitorScreen(vm: AppViewModel, onEnableProtection: () -> Unit) {
    val snapshot by vm.tunnel.collectAsState()
    val flows by vm.recentFlows.collectAsState()
    val alerts by vm.recentAlerts.collectAsState()
    val packetSeries by vm.packetSeries.collectAsState()
    val bytesSeries by vm.bytesSeries.collectAsState()
    val dnsSeries by vm.dnsSeries.collectAsState()
    val blockSeries by vm.blockSeries.collectAsState()

    val packetFloats = LongListToFloatList(packetSeries)
    val bytesFloats = LongListToFloatList(bytesSeries)
    val dnsFloats = LongListToFloatList(dnsSeries)
    val blockFloats = LongListToFloatList(blockSeries)

    // Determine the visual state for status display
    val statusColor = when {
        snapshot.isCapture -> SentinelRed
        snapshot.isRunning -> SentinelTeal
        else -> SentinelAmber
    }

    val statusLabel = when {
        snapshot.isCapture -> "⬤ CAPTURE"
        snapshot.isRunning -> "⬤ PROTECT"
        else -> "○ STOPPED"
    }

    ScreenScaffold(
        title = "Live Monitor",
        subtitle = "Counters update as the tunnel observes packets."
    ) {
        ProtectionCard(vm, onEnableProtection)

        Panel(title = "Session", subtitle = when {
            snapshot.isCapture -> "Capture mode · ${snapshot.captureRemainingMs / 1000}s remaining"
            snapshot.isRunning -> "Started ${Formatters.ago(snapshot.startedAt)}"
            else -> "Idle — no tunnel active"
        }) {
            Column {
                LiveGauge("State", statusLabel, color = statusColor.toString())
                LiveGauge("Packets observed", snapshot.packetsIn.toString(), color = "#3FB950")
                LiveGauge("Bytes observed", Formatters.bytes(snapshot.bytesIn), color = "#22D3EE")
                LiveGauge("DNS queries", snapshot.dnsQueries.toString(), color = "#17B4A0")
                LiveGauge("Flows persisted", snapshot.flowsPersisted.toString(), color = "#6366F1")
                LiveGauge("Queue drops", snapshot.queueDrops.toString(), color = "#D29922")
                if (snapshot.isCapture) {
                    LiveGauge("Capture remaining", "${snapshot.captureRemainingMs / 1000}s", color = "#2F81F7")
                }
            }
        }

        Panel(
            title = "Tunnel activity now",
            subtitle = "Animated bars react to live packet activity."
        ) {
            Column(Modifier.height(120.dp)) {
                SparkLine(values = packetFloats, color = SentinelLine)
                SparkLine(values = bytesFloats, color = SentinelWave)
                SparkLine(values = dnsFloats, color = SentinelTeal)
                SparkLine(values = blockFloats, color = SentinelRed)
            }
        }

        Panel(
            title = "Bytes in per sample",
            subtitle = "A waveline showing the last 24 samples of incoming bytes."
        ) {
            WaveLine(values = bytesFloats, activeColor = SentinelWave, idleColor = SentinelTextSecondary, barCount = 24)
        }

        Panel(title = "Packet activity") {
            Column(Modifier.height(120.dp)) {
                SparkLine(values = packetFloats, color = SentinelLine)
            }
        }

        Panel(title = "Recent flows") {
            if (flows.isEmpty()) {
                EmptyState("Nothing observed yet", "Start protection or a capture session.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    flows.take(12).forEach { flow -> FlowRowCompact(flow) }
                }
            }
        }

        Panel(title = "Recent alerts") {
            if (alerts.isEmpty()) {
                EmptyState("No alerts", "Alerts appear here and on the Alerts screen when a rule or the model fires.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    alerts.take(5).forEach { alert ->
                        Column {
                            Text(alert.reason, style = MaterialTheme.typography.bodySmall, color = SentinelText)
                            Text(
                                "${Formatters.time(alert.ts)} · ${alert.kind}",
                                style = MaterialTheme.typography.labelSmall,
                                color = SentinelTextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrafficScreen(vm: AppViewModel) {
    var protocol by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<FlowEntity>>(emptyList()) }
    var offset by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    val pageSize = 100

    LaunchedEffect(protocol, query) {
        loading = true
        rows = vm.flowPage(protocol, "", query, pageSize, 0)
        offset = rows.size
        loading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Traffic", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFF0B1220))
        }
        item {
            Text(
                "Every row was captured by the tunnel on this device. Pagination reads 100 rows at a time from SQLite.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Filter by IP or domain") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("" to "All", "TCP" to "TCP", "UDP" to "UDP", "ICMP" to "ICMP").forEach { (value, label) ->
                    FilterChip(
                        selected = protocol == value,
                        onClick = { protocol = value },
                        label = { Text(label) }
                    )
                }
            }
        }
        if (rows.isEmpty() && !loading) {
            item {
                EmptyState(
                    "No matching flows",
                    "Run protection or a capture session, then come back. Filters apply to stored rows only."
                )
            }
        }
        items(rows.size) { index ->
            val flow = rows[index]
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${flow.dstIp}:${flow.dstPort}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f)
                    )
                    val category = Category.fromKey(flow.category)
                    if (category != null) Badge(category.label, category.color)
                    else Badge(flow.source, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${Formatters.dateTime(flow.tsStart)} · ${flow.protocol} · " +
                        "${flow.packetsFwd + flow.packetsBwd} pkts · ${Formatters.bytes(flow.bytesFwd + flow.bytesBwd)} · " +
                        "${flow.captureMode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (flow.domain != null) {
                    Text(flow.domain!!, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            if (rows.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(
                        enabled = !loading,
                        onClick = {
                            loading = true
                        }
                    ) {
                        if (loading) CircularProgressIndicator(modifier = Modifier.height(16.dp))
                        else Text("Next page")
                    }
                }
                LaunchedEffect(loading) {
                    if (loading) {
                        val next = vm.flowPage(protocol, "", query, pageSize, offset)
                        rows = rows + next
                        offset += next.size
                        loading = false
                    }
                }
            }
        }
    }
}

@Composable
fun LevelBadge(kind: String) {
    val source = DetectionSource.fromKey(kind)
    Badge(source.label, if (source == DetectionSource.AI) SentinelGreen else SentinelAmber)
}
