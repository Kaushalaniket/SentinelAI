package ai.sentinel.mobile.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import ai.sentinel.mobile.ui.theme.SentinelText
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import ai.sentinel.mobile.data.db.BlockEventEntity
import ai.sentinel.mobile.data.db.RuleEntity
import ai.sentinel.mobile.ui.components.Badge
import ai.sentinel.mobile.ui.components.EmptyState
import ai.sentinel.mobile.ui.components.InfoRow
import ai.sentinel.mobile.ui.components.LiveGauge
import ai.sentinel.mobile.ui.components.Panel
import ai.sentinel.mobile.ui.components.SectionHeading
import ai.sentinel.mobile.ui.theme.SentinelAmber
import ai.sentinel.mobile.ui.theme.SentinelGreen
import ai.sentinel.mobile.ui.theme.SentinelRed
import ai.sentinel.mobile.ui.theme.SentinelText
import ai.sentinel.mobile.ui.theme.SentinelTextSecondary
import ai.sentinel.mobile.ui.theme.SentinelTextMuted
import ai.sentinel.mobile.ui.vm.AppViewModel
import ai.sentinel.mobile.util.Formatters
import java.io.File

private fun shareFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.name.endsWith(".csv")) "text/csv" else "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    } catch (_: Throwable) {
    }
}

@Composable
fun FirewallScreen(vm: AppViewModel) {
    val rules by vm.rules.collectAsState()
    val context = LocalContext.current
    var type by remember { mutableStateOf("ip") }
    var value by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var events by remember { mutableStateOf<List<BlockEventEntity>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(rules.size, reload) { events = vm.allBlockEvents() }

    ScreenScaffold(
        title = "Firewall",
        subtitle = "Default allow. Nothing is blocked unless you add a rule here."
    ) {
        Panel(title = "Add a block rule") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ip" to "IP", "cidr" to "Range", "port" to "Port", "domain" to "Domain")
                        .forEach { (key, label) ->
                            FilterChip(
                                selected = type == key,
                                onClick = { type = key; error = null },
                                label = { Text(label) }
                            )
                        }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    label = {
                        Text(
                            when (type) {
                                "ip" -> "203.0.113.10"
                                "cidr" -> "203.0.113.0/24"
                                "port" -> "8080"
                                else -> "ads.example.com"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = error != null
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (error != null) {
                    Text(error!!, color = SentinelRed, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        val result = vm.addRule(type, value, note)
                        if (result != null) error = result else {
                            value = ""
                            note = ""
                        }
                    },
                    enabled = value.isNotBlank()
                ) { Text("Add rule") }
            }
        }

        Panel(
            title = "Active rules",
            subtitle = "${rules.count { it.enabled }} of ${rules.size} enabled"
        ) {
            if (rules.isEmpty()) {
                EmptyState(
                    "No rules",
                    "Add one above. IP, range and domain rules take effect as soon as the tunnel restarts; " +
                        "port rules only match traffic inside a capture session."
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rules.forEach { rule -> RuleRow(rule, vm) }
                }
            }
        }

        Panel(
            title = "Blocked attempts",
            subtitle = "Evidence that a rule actually dropped a routed packet."
        ) {
            if (events.isEmpty()) {
                EmptyState(
                    "Nothing blocked yet",
                    "A rule appears in this list only after a real packet to that destination was routed into the tunnel and dropped. If the list is empty, no matching traffic has been observed yet."
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    events.take(25).forEach { event ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "${event.dstIp}:${event.dstPort}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(1f),
                                color = SentinelText
                            )
                            Text(
                                "${event.ruleDescription} · ${Formatters.time(event.ts)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = SentinelTextSecondary
                            )
                        }
                    }
                }
            }
        }

        Panel(title = "How blocking works in this build") {
            Text(
                "Protection mode routes only DNS and the destinations you block, so a block rule is " +
                    "enforced by dropping those packets inside the tunnel: the connection fails while the " +
                    "rule is enabled and works again when you disable it. That does not disturb the rest " +
                    "of the device's traffic. Port and domain rules are matched on traffic the tunnel " +
                    "already sees, which means port rules only apply during a capture session. Blocking " +
                    "DNS servers, the gateway or SentinelAI's own address is refused, because it would " +
                    "cut the tablet off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RuleRow(rule: RuleEntity, vm: AppViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${rule.type.uppercase()}  ${rule.value}",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            )
            Text(
                buildString {
                    append("${rule.hitCount} hits")
                    if (rule.note.isNotBlank()) append(" · ${rule.note}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = rule.enabled, onCheckedChange = { vm.toggleRule(rule) })
        TextButton(onClick = { vm.deleteRule(rule.id) }) { Text("Delete") }
    }
}

@Composable
fun ReportsScreen(vm: AppViewModel) {
    var days by remember { mutableIntStateOf(1) }
    var summary by remember { mutableStateOf(AppViewModel.Dashboard()) }
    var message by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(days) { summary = vm.rangeSummary(days) }

    ScreenScaffold(
        title = "Reports",
        subtitle = "Aggregated from stored rows. Nothing is extrapolated."
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "24 hours", 7 to "7 days", 30 to "30 days").forEach { (value, label) ->
                FilterChip(selected = days == value, onClick = { days = value }, label = { Text(label) })
            }
        }

        Panel(title = "Last $days day(s)") {
            Column {
                LiveGauge("Flows", summary.flows24h.toString(), color = "#3FB950")
                LiveGauge("Threat flows", summary.threats24h.toString(), color = "#E5534B")
                LiveGauge("Alerts", summary.alerts24h.toString(), color = "#D29922")
                LiveGauge("Blocked", summary.blocked24h.toString(), color = "#6366F1")
                LiveGauge("Observed bytes", Formatters.bytes(summary.bytes24h), color = "#22D3EE")
                LiveGauge("Total stored", summary.totalFlows.toString(), color = "#2F81F7")
            }
        }

        Panel(title = "Categories") {
            if (summary.categories.isEmpty()) {
                EmptyState("No classified flows in this range", "Capture sessions produce classifiable flows.")
            } else {
                Column {
                    summary.categories.forEach { row ->
                        InfoRow(row.category, row.hits.toString())
                    }
                }
            }
        }

        Panel(title = "Export") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    vm.exportFlowsAsync { file -> shareFile(context, file) }
                }) { Text("Export flows (CSV)") }
                OutlinedButton(onClick = {
                    vm.exportAlerts { file -> shareFile(context, file) }
                }) { Text("Export alerts (CSV)") }
                Button(onClick = {
                    vm.exportReport { file ->
                        message = "Report written: ${file.name}"
                        shareFile(context, file)
                    }
                }) { Text("Export device test report") }
                if (message != null) {
                    Text(message!!, style = MaterialTheme.typography.bodySmall, color = SentinelGreen)
                }
                Text(
                    "Files are written to this app's private storage and shared through Android's " +
                        "share sheet, so no storage permission is needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Panel(title = "Retention") {
            val settings by vm.settings.collectAsState()
            Column {
                LiveGauge("Flows kept", "${settings.flowRetentionDays} days / ${settings.flowRetentionRows} rows", color = "#3FB950")
                LiveGauge("Alerts kept", "${settings.alertRetentionDays} days", color = "#D29922")
                OutlinedButton(onClick = { vm.trimNow(); message = "Retention applied" }) {
                    Text("Apply retention now")
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel, onEnableProtection: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val snapshot by vm.tunnel.collectAsState()
    val context = LocalContext.current
    var captureSeconds by remember { mutableIntStateOf(settings.captureSeconds) }
    var retentionDays by remember { mutableIntStateOf(settings.flowRetentionDays) }
    var confirmClear by remember { mutableStateOf(false) }
    val result = vm.selfTestResult
    val contract = remember { (context.applicationContext as ai.sentinel.mobile.SentinelApp).container.contract }

    ScreenScaffold(title = "Settings", subtitle = "All settings apply immediately.") {
        Panel(title = "Protection") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Protection enabled", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (snapshot.isRunning) "Tunnel is running" else "Tunnel is stopped",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = snapshot.isRunning,
                        onCheckedChange = { enabled ->
                            if (enabled) onEnableProtection() else vm.stopTunnel()
                        }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Alert notifications", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Uses the threats and service channels",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.notificationsEnabled,
                        onCheckedChange = { vm.setNotifications(it) }
                    )
                }
                Text("Capture window: ${captureSeconds}s")
                Slider(
                    value = captureSeconds.toFloat(),
                    onValueChange = { captureSeconds = it.toInt() },
                    onValueChangeFinished = { vm.updateCaptureSeconds(captureSeconds) },
                    valueRange = 15f..180f
                )
                Text(
                    "Longer windows capture more flows but leave the tablet without internet for longer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Panel(title = "Storage") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Flow retention: $retentionDays days")
                Slider(
                    value = retentionDays.toFloat(),
                    onValueChange = { retentionDays = it.toInt() },
                    onValueChangeFinished = {
                        vm.updateRetention(retentionDays, settings.flowRetentionRows)
                    },
                    valueRange = 1f..30f
                )
                OutlinedButton(onClick = { vm.trimNow() }) { Text("Trim now") }
                if (!confirmClear) {
                    OutlinedButton(onClick = { confirmClear = true }) { Text("Clear all history") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.clearAllData(); confirmClear = false },
                            colors = ButtonDefaults.buttonColors(containerColor = SentinelRed)
                        ) { Text("Confirm delete") }
                        OutlinedButton(onClick = { confirmClear = false }) { Text("Cancel") }
                    }
                }
            }
        }

        Panel(title = "Model diagnostics") {
            Column {
                InfoRow("Model version", contract.modelVersion)
                InfoRow("Trained", contract.trainedAt)
                InfoRow("scikit-learn", contract.sklearnVersion)
                InfoRow("SHA-256", contract.sha256.take(20) + "…", mono = true)
                InfoRow("On-device parity",
                    if (result != null) result.summary else "Test not run")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.runSelfTest() }, enabled = !vm.selfTestRunning) {
                        Text(if (vm.selfTestRunning) "Running…" else "Run self-test")
                    }
                    OutlinedButton(onClick = {
                        vm.exportReport { file -> shareFile(context, file) }
                    }) { Text("Export report") }
                }
            }
        }

        Panel(
            title = "What SentinelAI can and cannot see",
            subtitle = "The honest limits of a non-root VPN on Android 14."
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Protection mode routes only DNS and the destinations you block. Other traffic never enters the tunnel.",
                    "A capture session routes everything for a bounded window and drops it, so the tablet has no internet until the window ends.",
                    "TCP is never forwarded. Re-injecting TCP requires a full userspace TCP/IP stack.",
                    "HTTPS payloads are invisible. Detection uses connection metadata and flow shape only.",
                    "QUIC (UDP/443) hides TLS metadata; it is visible only as a flow.",
                    "Encrypted DNS (DoH/DoT) hides domain names. Only plain UDP/53 is readable.",
                    "ICMP and non-TCP/UDP protocols are counted, never inspected.",
                    "Only one VPN can be active; enabling SentinelAI disconnects any other VPN.",
                    "The classifier was trained on CIC-IDS2017 test-network traffic. Accuracy on this tablet is unmeasured."
                ).forEach { line ->
                    Row {
                        Text("•  ", color = SentinelAmber)
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Panel(title = "About") {
            Column {
                InfoRow("App version", appVersion(context))
                InfoRow("Package", context.packageName)
                InfoRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                InfoRow("ABI", Build.SUPPORTED_ABIS.joinToString())
                Text(
                    "Experimental build. Protection and blocking behaviour has not been verified on this " +
                        "device until the on-device packet-capture and firewall-blocking checks have been " +
                        "run by hand.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SentinelTextSecondary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

private fun appVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
} catch (_: Throwable) {
    "unknown"
}
