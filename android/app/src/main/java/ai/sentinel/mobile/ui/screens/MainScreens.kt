@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ai.sentinel.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.sentinel.mobile.data.db.FlowEntity
import ai.sentinel.mobile.model.Category
import ai.sentinel.mobile.model.DetectionSource
import ai.sentinel.mobile.net.TunnelState
import ai.sentinel.mobile.ui.adaptive.LocalAdaptive
import ai.sentinel.mobile.ui.adaptive.ResponsiveGrid
import ai.sentinel.mobile.ui.adaptive.TwoPane
import ai.sentinel.mobile.ui.components.Badge
import ai.sentinel.mobile.ui.components.EmptyState
import ai.sentinel.mobile.ui.components.LiveGauge
import ai.sentinel.mobile.ui.components.Panel
import ai.sentinel.mobile.ui.components.SparkLine
import ai.sentinel.mobile.ui.components.StatTile
import ai.sentinel.mobile.ui.components.WaveLine
import ai.sentinel.mobile.ui.theme.SentinelAmber
import ai.sentinel.mobile.ui.theme.SentinelBlue
import ai.sentinel.mobile.ui.theme.SentinelCard
import ai.sentinel.mobile.ui.theme.SentinelCyan
import ai.sentinel.mobile.ui.theme.SentinelGreen
import ai.sentinel.mobile.ui.theme.SentinelGrid
import ai.sentinel.mobile.ui.theme.SentinelLine
import ai.sentinel.mobile.ui.theme.SentinelRed
import ai.sentinel.mobile.ui.theme.SentinelTeal
import ai.sentinel.mobile.ui.theme.SentinelText
import ai.sentinel.mobile.ui.theme.SentinelTextSecondary
import ai.sentinel.mobile.ui.theme.SentinelTitleText
import ai.sentinel.mobile.ui.theme.SentinelWave
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

/**
 * Screen shell used by every module: a single scrolling column, centred and capped at the
 * size-class content width, with spacing taken from the same size class. On phones it is one
 * column; on tablets it stops stretching panels to an unreadable width.
 */
@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val metrics = LocalAdaptive.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = metrics.maxContentWidth)
                .padding(metrics.screenPadding),
            verticalArrangement = Arrangement.spacedBy(metrics.gap)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = SentinelTitleText
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SentinelTextSecondary
                    )
                }
            }
            StatusStrip()
            content()
            Spacer(Modifier.height(metrics.gap))
        }
    }
}

/**
 * One consistent status line under every screen title. Reads the service's own state, so the
 * pill can never claim monitoring that is not happening. Stacks on narrow phones so the label
 * and the counters cannot collide.
 */
@Composable
private fun StatusStrip() {
    val snapshot by TunnelState.state.collectAsState()
    val label = when {
        snapshot.isCapture -> "CAPTURE · internet paused for ${snapshot.captureRemainingMs / 1000}s"
        snapshot.isRunning -> "PROTECTION ACTIVE · DNS + firewall"
        snapshot.status == TunnelState.Status.ERROR -> "TUNNEL ERROR"
        else -> "MONITORING OFF"
    }
    val tint = when {
        snapshot.isCapture -> SentinelRed
        snapshot.isRunning -> SentinelGreen
        snapshot.status == TunnelState.Status.ERROR -> SentinelRed
        else -> SentinelAmber
    }
    val counters = "${snapshot.packetsIn} pkts · ${Formatters.bytes(snapshot.bytesIn)}"

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(SentinelCard, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        val inline = maxWidth >= 460.dp
        if (inline) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(tint, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2
                )
                Text(
                    counters,
                    style = MaterialTheme.typography.labelSmall,
                    color = SentinelTextSecondary
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(tint, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = tint,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2
                    )
                }
                Text(
                    counters,
                    style = MaterialTheme.typography.labelSmall,
                    color = SentinelTextSecondary
                )
            }
        }
    }
}

/** Two actions that stay tappable side by side on tablets and stack on narrow phones. */
@Composable
private fun ActionPair(
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 340.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth()) { primary() }
                Box(Modifier.fillMaxWidth()) { secondary() }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { primary() }
                Box(Modifier.weight(1f)) { secondary() }
            }
        }
    }
}

@Composable
private fun ProtectionCard(vm: AppViewModel, onEnableProtection: () -> Unit) {
    val snapshot by vm.tunnel.collectAsState()
    val settings by vm.settings.collectAsState()
    val running = snapshot.isRunning
    var showCaptureWarning by remember { mutableStateOf(false) }

    // Warn before capture if the device is on a network with active traffic
    val captureWarning = "Capture mode drops all non-DNS traffic for the full window (default ${settings.captureSeconds}s). The device will have no internet until the window ends. Confirm to proceed. This is a disruptive diagnostic test, not normal operation."

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
            ActionPair(
                primary = {
                    if (!running) {
                        OutlinedButton(
                            onClick = onEnableProtection,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Enable protection", maxLines = 2, textAlign = TextAlign.Center) }
                    } else {
                        OutlinedButton(
                            onClick = { vm.stopTunnel() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = SentinelRed)
                        ) { Text("Emergency stop", maxLines = 2, textAlign = TextAlign.Center) }
                    }
                },
                secondary = {
                    OutlinedButton(
                        onClick = { showCaptureWarning = true },
                        enabled = !snapshot.isCapture,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Capture ${settings.captureSeconds}s", maxLines = 2, textAlign = TextAlign.Center) }
                }
            )
            Text(
                "Protection mode routes only DNS and user-blocked address ranges, so the device keeps internet. " +
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

private data class StatSpec(val label: String, val value: String, val tint: Color)

@Composable
fun DashboardScreen(vm: AppViewModel, onEnableProtection: () -> Unit) {
    val dashboard = vm.dashboard
    val snapshot by vm.tunnel.collectAsState()
    val flows by vm.recentFlows.collectAsState()
    val packetSeries by vm.packetSeries.collectAsState()
    val bytesSeries by vm.bytesSeries.collectAsState()
    val dnsSeries by vm.dnsSeries.collectAsState()
    val blockSeries by vm.blockSeries.collectAsState()

    // Charts want 0..100 heights; the stored series are raw counters.
    val packetFloats = LongListToPercentSeries(packetSeries)
    val bytesFloats = LongListToPercentSeries(bytesSeries)
    val dnsFloats = LongListToPercentSeries(dnsSeries)
    val blockFloats = LongListToPercentSeries(blockSeries)
    val liveBytes by vm.liveBytes.collectAsState()
    val metrics = LocalAdaptive.current

    val statusHex = when {
        snapshot.isCapture -> "#E5534B"
        snapshot.isRunning -> "#17B4A0"
        else -> "#D29922"
    }
    val statusLabel = when {
        snapshot.isCapture -> "⬤ CAPTURE"
        snapshot.isRunning -> "⬤ PROTECT"
        else -> "○ STOPPED"
    }

    LaunchedEffect(snapshot.flowsPersisted, snapshot.blockedPackets, snapshot.dnsQueries) {
        vm.refreshDashboard()
    }

    ScreenScaffold(
        title = "Dashboard",
        subtitle = "Everything below is read from this device's own database."
    ) {
        ProtectionCard(vm, onEnableProtection)

        val stats = listOf(
            StatSpec("Flows 24h", dashboard.flows24h.toString(), SentinelTitleText),
            StatSpec(
                "Threats 24h",
                dashboard.threats24h.toString(),
                if (dashboard.threats24h > 0) SentinelRed else SentinelTitleText
            ),
            StatSpec("Alerts 24h", dashboard.alerts24h.toString(), SentinelTitleText),
            StatSpec("Blocked 24h", dashboard.blocked24h.toString(), SentinelTitleText),
            StatSpec("Observed", Formatters.bytes(dashboard.bytes24h), SentinelTitleText)
        )
        ResponsiveGrid(items = stats, minItemWidth = metrics.statMinWidth) { spec ->
            StatTile(
                label = spec.label,
                value = spec.value,
                tint = spec.tint,
                modifier = Modifier.fillMaxWidth()
            )
        }

        TwoPane(
            left = {
                Panel(
                    title = "Security health",
                    subtitle = "Derived from the last 24 h of stored rows, with the deduction shown."
                ) {
                    val score = dashboard.healthScore
                    val scoreColor = when {
                        score >= 85 -> SentinelGreen
                        score >= 60 -> SentinelAmber
                        else -> SentinelRed
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "$score",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = scoreColor
                            )
                            Text(
                                " / 100",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SentinelTextSecondary
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                when {
                                    snapshot.isCapture -> "Capture session"
                                    snapshot.isRunning -> "Monitoring"
                                    else -> "Not monitoring"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = SentinelTextSecondary,
                                maxLines = 2,
                                textAlign = TextAlign.End
                            )
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .background(SentinelGrid, RoundedCornerShape(4.dp))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth((score / 100f).coerceIn(0.02f, 1f))
                                    .fillMaxHeight()
                                    .background(scoreColor, RoundedCornerShape(4.dp))
                            )
                        }
                        Text(
                            "100 − up to 45 for the share of flows classified as a threat − 5 per unresolved " +
                                "high alert (max 25) − 10 while nothing is monitored. Not a vendor risk score.",
                            style = MaterialTheme.typography.labelSmall,
                            color = SentinelTextSecondary
                        )
                    }
                }
            },
            right = {
                Panel(
                    title = "What is happening right now",
                    subtitle = "Counters sampled every 5 s while the tunnel runs."
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SparkLine(values = packetFloats, color = SentinelLine)
                        SparkLine(values = bytesFloats, color = SentinelWave)
                        SparkLine(values = dnsFloats, color = SentinelTeal)
                        SparkLine(values = blockFloats, color = SentinelRed)
                    }
                }
            }
        )

        TwoPane(
            left = {
                Panel(title = "Protocol distribution", subtitle = "Last 24 h, by flow count.") {
                    if (dashboard.protocols.isEmpty()) {
                        EmptyState("No flows yet", "Protocols appear once traffic is recorded.")
                    } else {
                        val total = dashboard.protocols.sumOf { it.hits }.coerceAtLeast(1)
                        val palette = listOf(SentinelCyan, SentinelTeal, SentinelBlue, SentinelAmber)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            dashboard.protocols.forEachIndexed { index, row ->
                                DistributionBar(
                                    label = row.protocol,
                                    value = row.hits,
                                    total = total,
                                    detail = Formatters.bytes(row.bytes),
                                    color = palette[index % palette.size]
                                )
                            }
                        }
                    }
                }
            },
            right = {
                Panel(title = "Threat severity", subtitle = "Alerts raised in the last 24 h.") {
                    if (dashboard.severities.isEmpty()) {
                        EmptyState("No alerts in range", "Severity appears when a rule or the model fires.")
                    } else {
                        val total = dashboard.severities.sumOf { it.hits }.coerceAtLeast(1)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            dashboard.severities.forEach { row ->
                                DistributionBar(
                                    label = row.severity.replaceFirstChar { it.uppercase() },
                                    value = row.hits,
                                    total = total,
                                    detail = "${row.hits}",
                                    color = when (row.severity) {
                                        "critical", "high" -> SentinelRed
                                        "medium" -> SentinelAmber
                                        else -> SentinelTeal
                                    }
                                )
                            }
                        }
                    }
                }
            }
        )

        Panel(
            title = "Tunnel counters",
            subtitle = "Live values from the service, not estimates."
        ) {
            val gauges = listOf(
                Triple("Status", statusLabel, statusHex),
                Triple("Packets", snapshot.packetsIn.toString(), "#3FB950"),
                Triple("Bytes", Formatters.bytes(snapshot.bytesIn), "#22D3EE"),
                Triple("DNS queries", snapshot.dnsQueries.toString(), "#17B4A0"),
                Triple("Active flows", snapshot.activeFlows.toString(), "#6366F1"),
                Triple("Flows stored", snapshot.flowsPersisted.toString(), "#2F81F7"),
                Triple("Dropped", snapshot.droppedPackets.toString(), "#D29922"),
                Triple("Blocked", snapshot.blockedPackets.toString(), "#E5534B")
            )
            ResponsiveGrid(items = gauges, minItemWidth = metrics.gaugeMinWidth) { gauge ->
                LiveGauge(
                    title = gauge.first,
                    value = gauge.second,
                    color = gauge.third,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Panel(
            title = "Bytes flowing by (live)",
            subtitle = "Bar height is relative to the busiest sample in the window. " +
                Formatters.bytes(liveBytes) + " bytes observed so far this session."
        ) {
            WaveLine(values = bytesFloats, activeColor = SentinelWave, idleColor = SentinelTextSecondary, barCount = 24)
        }

        TwoPane(
            left = {
                Panel(title = "Detected categories", subtitle = "Last 24 hours, from stored flows.") {
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
                                    Text(
                                        row.hits.toString(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = SentinelText
                                    )
                                }
                            }
                        }
                    }
                }
            },
            right = {
                Panel(title = "Top destinations", subtitle = "Last 24 hours by flow count.") {
                    if (dashboard.topDestinations.isEmpty()) {
                        EmptyState(
                            "Nothing recorded yet",
                            "Destinations appear here once the tunnel has observed traffic."
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            dashboard.topDestinations.forEach { destination ->
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        destination.dstIp,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        modifier = Modifier.weight(0.5f),
                                        color = SentinelText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "${destination.hits} flows · ${Formatters.bytes(destination.bytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SentinelTextSecondary,
                                        modifier = Modifier.weight(0.5f),
                                        textAlign = TextAlign.End,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )

        Panel(title = "Latest observed flows", subtitle = "Top 6 most recent captures.") {
            if (flows.isEmpty()) {
                EmptyState("No flows", "Flows are recorded while protection or a capture session runs.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    flows.take(6).forEach { flow -> FlowRowCompact(flow) }
                }
            }
        }
    }
}

/** A labelled horizontal bar. Width is the value's share of [total]; no invented baseline. */
@Composable
private fun DistributionBar(
    label: String,
    value: Long,
    total: Long,
    detail: String,
    color: Color
) {
    val fraction = (value.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = SentinelText,
            modifier = Modifier.weight(0.3f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(0.44f)
                .height(8.dp)
                .background(SentinelGrid, RoundedCornerShape(4.dp))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceAtLeast(0.02f))
                    .fillMaxHeight()
                    .background(color, RoundedCornerShape(4.dp))
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = SentinelTextSecondary,
            modifier = Modifier.weight(0.26f),
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FlowRowCompact(flow: FlowEntity) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${flow.dstIp}:${flow.dstPort}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = SentinelText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${flow.protocol} · ${flow.packetsFwd + flow.packetsBwd} pkts · ${Formatters.bytes(flow.bytesFwd + flow.bytesBwd)}",
                style = MaterialTheme.typography.labelSmall,
                color = SentinelTextSecondary,
                maxLines = 2
            )
        }
        Spacer(Modifier.width(8.dp))
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

    // Charts want 0..100 heights; the stored series are raw counters.
    val packetFloats = LongListToPercentSeries(packetSeries)
    val bytesFloats = LongListToPercentSeries(bytesSeries)
    val dnsFloats = LongListToPercentSeries(dnsSeries)
    val blockFloats = LongListToPercentSeries(blockSeries)
    val metrics = LocalAdaptive.current

    val statusHex = when {
        snapshot.isCapture -> "#E5534B"
        snapshot.isRunning -> "#17B4A0"
        else -> "#D29922"
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
            val gauges = buildList {
                add(Triple("State", statusLabel, statusHex))
                add(Triple("Packets observed", snapshot.packetsIn.toString(), "#3FB950"))
                add(Triple("Bytes observed", Formatters.bytes(snapshot.bytesIn), "#22D3EE"))
                add(Triple("DNS queries", snapshot.dnsQueries.toString(), "#17B4A0"))
                add(Triple("Flows persisted", snapshot.flowsPersisted.toString(), "#6366F1"))
                add(Triple("Queue drops", snapshot.queueDrops.toString(), "#D29922"))
                if (snapshot.isCapture) {
                    add(Triple("Capture remaining", "${snapshot.captureRemainingMs / 1000}s", "#2F81F7"))
                }
            }
            ResponsiveGrid(items = gauges, minItemWidth = metrics.gaugeMinWidth) { gauge ->
                LiveGauge(
                    title = gauge.first,
                    value = gauge.second,
                    color = gauge.third,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        TwoPane(
            left = {
                Panel(
                    title = "Tunnel activity now",
                    subtitle = "Four sparklines react to live packet activity."
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SparkLine(values = packetFloats, color = SentinelLine)
                        SparkLine(values = bytesFloats, color = SentinelWave)
                        SparkLine(values = dnsFloats, color = SentinelTeal)
                        SparkLine(values = blockFloats, color = SentinelRed)
                    }
                }
            },
            right = {
                Panel(
                    title = "Bytes in per sample",
                    subtitle = "A waveline showing the last 24 samples of incoming bytes."
                ) {
                    WaveLine(values = bytesFloats, activeColor = SentinelWave, idleColor = SentinelTextSecondary, barCount = 24)
                }
            }
        )

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
    val metrics = LocalAdaptive.current

    LaunchedEffect(protocol, query) {
        loading = true
        rows = vm.flowPage(protocol, "", query, pageSize, 0)
        offset = rows.size
        loading = false
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .heightIn(max = 10000.dp)
                .widthIn(max = metrics.maxContentWidth)
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(metrics.screenPadding),
            verticalArrangement = Arrangement.spacedBy(metrics.gap)
        ) {
            item {
                Text(
                    "Traffic",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = SentinelTitleText
                )
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SentinelCard, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${flow.dstIp}:${flow.dstPort}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(8.dp))
                        val category = Category.fromKey(flow.category)
                        if (category != null) Badge(category.label, category.color)
                        else Badge(flow.source, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "${Formatters.dateTime(flow.tsStart)} · ${flow.protocol} · " +
                            "${flow.packetsFwd + flow.packetsBwd} pkts · ${Formatters.bytes(flow.bytesFwd + flow.bytesBwd)} · " +
                            "${flow.captureMode}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                    if (flow.domain != null) {
                        Text(
                            flow.domain!!,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                            onClick = { loading = true }
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
}

@Composable
fun LevelBadge(kind: String) {
    val source = DetectionSource.fromKey(kind)
    Badge(source.label, if (source == DetectionSource.AI) SentinelGreen else SentinelAmber)
}
