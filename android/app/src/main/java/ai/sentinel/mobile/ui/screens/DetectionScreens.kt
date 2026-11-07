@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ai.sentinel.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ai.sentinel.mobile.data.db.AlertEntity
import ai.sentinel.mobile.model.Category
import ai.sentinel.mobile.model.DetectionSource
import ai.sentinel.mobile.ui.components.Badge
import ai.sentinel.mobile.ui.components.EmptyState
import ai.sentinel.mobile.ui.components.InfoRow
import ai.sentinel.mobile.ui.components.LineChart
import ai.sentinel.mobile.ui.components.Panel
import ai.sentinel.mobile.ui.theme.SentinelAmber
import ai.sentinel.mobile.ui.theme.SentinelBlue
import ai.sentinel.mobile.ui.theme.SentinelGreen
import ai.sentinel.mobile.ui.theme.SentinelRed
import ai.sentinel.mobile.ui.vm.AppViewModel
import ai.sentinel.mobile.util.Formatters

@Composable
private fun AlertRow(
    alert: AlertEntity,
    onAcknowledge: (() -> Unit)? = null
) {
    val category = Category.fromKey(alert.category)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        FlowRow(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Badge(
                DetectionSource.fromKey(alert.kind).label,
                if (alert.kind == DetectionSource.AI.key) SentinelGreen else SentinelAmber
            )
            Text(
                category?.label ?: alert.category,
                style = MaterialTheme.typography.bodyMedium
            )
            if (alert.confidence != null) {
                Text(
                    Formatters.percent(alert.confidence!!),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (alert.acknowledged) {
                Text(
                    "acknowledged",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(alert.reason, style = MaterialTheme.typography.bodySmall)
        Text(
            "${Formatters.dateTime(alert.ts)} · ${alert.dstIp}:${alert.dstPort}" +
                (alert.domain?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (alert.topFeatures != null) {
            Text(
                alert.topFeatures!!,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onAcknowledge != null && !alert.acknowledged) {
            TextButton(onClick = onAcknowledge) { Text("Acknowledge") }
        }
    }
}

@Composable
fun DetectionScreen(vm: AppViewModel) {
    val alerts by vm.recentAlerts.collectAsState()
    val settings by vm.settings.collectAsState()
    val result = vm.selfTestResult
    val context = LocalContext.current
    val contract = remember { (context.applicationContext as ai.sentinel.mobile.SentinelApp).container.contract }
    var threshold by remember { mutableStateOf(settings.confidenceThreshold.toFloat()) }

    ScreenScaffold(
        title = "AI Threat Detection",
        subtitle = "Model scoring runs on this device. No traffic leaves it."
    ) {
        Panel(
            title = "Model",
            subtitle = "Exported from the project's trained Random Forest."
        ) {
            Column {
                InfoRow("Model version", contract.modelVersion)
                InfoRow("Features", contract.featureCount.toString())
                InfoRow("Classes", contract.classes.joinToString(", "))
                InfoRow("Opset", contract.opset.toString())
                InfoRow("SHA-256", contract.sha256.take(24) + "…", mono = true)
                InfoRow(
                    "Reference parity",
                    "${contract.parityAgreement}/${contract.parityRows} rows, delta ${contract.parityDelta}"
                )
                InfoRow("On-device test",
                    if (result != null) result.summary else "Test not run yet")
            }
        }

        Panel(
            title = "Confidence threshold",
            subtitle = "Below this score a flow is labelled Unclassified, never forced into a category."
        ) {
            Column {
                Text(Formatters.percent(threshold.toDouble()))
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    onValueChangeFinished = { vm.updateThreshold(threshold.toDouble()) },
                    valueRange = 0.3f..0.95f
                )
            }
        }

        Panel(
            title = "Verify the model on this device",
            subtitle = "Scores the bundled 140 labelled flows and compares with the desktop reference."
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { vm.runSelfTest() },
                    enabled = !vm.selfTestRunning
                ) { Text(if (vm.selfTestRunning) "Running…" else "Run parity self-test") }
                if (vm.selfTestRunning) {
                    Text(
                        "Running on-device parity test…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (result != null && !vm.selfTestRunning) {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            result.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.passed) SentinelGreen else SentinelRed
                        )
                        if (result.error != null) {
                            Text(
                                result.error!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = SentinelRed
                            )
                        }
                    }
                }
            }
        }

        Panel(
            title = "AI detections",
            subtitle = "Only flows scored in a capture session can be classified."
        ) {
            val aiAlerts = alerts.filter { it.kind == DetectionSource.AI.key }
            if (aiAlerts.isEmpty()) {
                EmptyState(
                    "No AI detections yet",
                    "Run a capture session to score full flows. Model output is labelled AI; " +
                        "rule hits are labelled Rule and never merged with it."
                )
            } else {
                Column { aiAlerts.take(15).forEach { alert -> AlertRow(alert) } }
            }
        }

        // ---- Classification over time ----
        Panel(
            title = "Classifications this session",
            subtitle = "Rolling view of which categories the model has been scoring."
        ) {
            // kinds is List<Pair<Pair<String,String>, Color>>
            // Each element is (Pair<String,String>, Color) where Pair<String,String> is (key, label)
            val kinds = listOf(
                "benign" to "Benign" to SentinelGreen,
                "bot" to "Bot / C2" to SentinelRed,
                "bruteforce" to "Brute force" to SentinelRed,
                "ddos" to "DDoS" to SentinelRed,
                "dos" to "Denial of service" to SentinelAmber,
                "heartbleed" to "Heartbleed" to SentinelRed,
                "infiltration" to "Infiltration" to SentinelRed,
                "portscan" to "Port scan" to SentinelAmber,
                "webattack" to "Web attack" to SentinelRed
            )
            val counts = kinds.associate { (pair, color) ->
                pair.first to alerts.count { it.category == pair.first }
            }
            val maxCount = counts.values.maxOrNull() ?: 1
            val series = kinds.map { (pair, color) ->
                val hits = counts[pair.first] ?: 0
                val pct = if (maxCount > 0) (hits * 100) / maxCount.toFloat() else 0f
                Pair(pct, hits.toFloat())
            }.filter { it.second > 0 }
            if (series.isEmpty()) {
                Text("No classifications yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LineChart(points = series, lineColor = SentinelBlue, areaColor = SentinelBlue.copy(alpha = 0.15f))
            }
        }

        Panel(title = "What the number does not mean") {
            Text(
                "The model's held-out CIC-IDS2017 macro-F1 of 0.9499 is a research dataset result. " +
                    "It is not a measurement of accuracy on this device's traffic, and the app never " +
                    "presents it as one. Real-world accuracy here is unknown until you measure it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AlertsScreen(vm: AppViewModel) {
    var alerts by remember { mutableStateOf<List<AlertEntity>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        alerts = vm.allAlerts()
    }

    ScreenScaffold(
        title = "Alerts",
        subtitle = "Every alert carries either AI or Rule provenance."
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { vm.acknowledgeAll(); reload++ }) { Text("Acknowledge all") }
            OutlinedButton(onClick = { vm.exportAlerts { }; reload++ }) { Text("Export CSV") }
        }
        if (alerts.isEmpty()) {
            EmptyState(
                "No alerts stored",
                "Rules fire on observed DNS activity and the model fires on captured flows. " +
                    "Nothing is generated to fill this screen."
            )
        } else {
            Panel(title = "${alerts.size} alerts") {
                Column {
                    alerts.take(60).forEach { alert ->
                        AlertRow(alert, onAcknowledge = { vm.acknowledge(alert.id); reload++ })
                    }
                }
            }
        }
    }
}

@Composable
fun IntelScreen(vm: AppViewModel) {
    var counts by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(Unit) { counts = vm.blocklistSummary() }

    ScreenScaffold(
        title = "Threat Intelligence",
        subtitle = "Offline indicator list, loaded from the APK."
    ) {
        Panel(title = "Bundled list") {
            Column {
                InfoRow("Domains", counts.first.toString())
                InfoRow("IP addresses", counts.second.toString())
                InfoRow("Network required", "no")
                InfoRow("Refresh", "not implemented")
            }
        }

        Panel(title = "What this list is") {
            Text(
                "The bundled file is a user-editable starter list of well-known advertising and " +
                    "tracking domains, included so the rule layer has real entries to match. It is not " +
                    "a threat feed, contains no reputation data, and makes no claim that any listed " +
                    "address is malicious. Edit assets/blocklist.txt in the project and rebuild to " +
                    "replace it with your own indicators.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Panel(title = "Online enrichment") {
            Text(
                "Online lookups (for example AbuseIPDB or VirusTotal) are not implemented in this " +
                    "build. They require API keys, and sending indicators off-device conflicts with " +
                    "the standalone requirement. Detection and blocking work entirely without them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Panel(
            title = "Threat intelligence refresh",
            subtitle = "Not available"
        ) {
            Column {
                Text(
                    "This build has no network-based threat-intelligence refresh. The bundled list is " +
                        "a local, offline indicator list loaded from the APK. There is no caching layer " +
                        "and no live lookup, so results cannot go stale and no network access is needed " +
                        "to verify them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
