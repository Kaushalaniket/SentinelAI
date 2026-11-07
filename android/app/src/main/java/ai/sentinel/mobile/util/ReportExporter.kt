package ai.sentinel.mobile.util

import android.content.Context
import android.os.Build
import ai.sentinel.mobile.data.db.AlertEntity
import ai.sentinel.mobile.data.db.FlowEntity
import ai.sentinel.mobile.net.TunnelState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes exports to app-specific storage, which needs no permission and survives reboots.
 * Files are shared explicitly by the user through the system share sheet.
 */
object ReportExporter {

    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun directory(context: Context): File =
        File(context.filesDir, "reports").apply { mkdirs() }

    fun exportFlowsCsv(context: Context, flows: List<FlowEntity>): File {
        val file = File(directory(context), "flows-${stamp.format(Date())}.csv")
        val builder = StringBuilder()
        builder.appendLine(
            "id,start,target,port,protocol,packets_fwd,packets_bwd,bytes,duration_ms,category,confidence,source,provenance,domain,capture_mode"
        )
        flows.forEach { flow ->
            builder.appendLine(
                listOf(
                    flow.id,
                    Formatters.dateTime(flow.tsStart),
                    flow.dstIp,
                    flow.dstPort,
                    flow.protocol,
                    flow.packetsFwd,
                    flow.packetsBwd,
                    flow.bytesFwd + flow.bytesBwd,
                    flow.durationMs,
                    flow.category ?: "",
                    flow.confidence?.let { String.format(Locale.US, "%.3f", it) } ?: "",
                    flow.source,
                    flow.provenance,
                    flow.domain ?: "",
                    flow.captureMode
                ).joinToString(",")
            )
        }
        file.writeText(builder.toString())
        return file
    }

    fun exportAlertsCsv(context: Context, alerts: List<AlertEntity>): File {
        val file = File(directory(context), "alerts-${stamp.format(Date())}.csv")
        val builder = StringBuilder()
        builder.appendLine("id,time,kind,category,severity,confidence,target,port,reason,domain,acknowledged")
        alerts.forEach { alert ->
            builder.appendLine(
                listOf(
                    alert.id,
                    Formatters.dateTime(alert.ts),
                    alert.kind,
                    alert.category,
                    alert.severity,
                    alert.confidence?.let { String.format(Locale.US, "%.3f", it) } ?: "",
                    alert.dstIp,
                    alert.dstPort,
                    "\"${alert.reason.replace("\"", "'")}\"",
                    alert.domain ?: "",
                    alert.acknowledged
                ).joinToString(",")
            )
        }
        file.writeText(builder.toString())
        return file
    }

    /**
     * The device test report. Every figure comes from this device; anything not measured is
     * written as NOT MEASURED so the document cannot be mistaken for a full validation.
     */
    fun writeDeviceReport(
        context: Context,
        selfTest: SelfTest.Result?,
        flowCount: Long,
        alertCount: Long,
        blockedCount: Long,
        modelVersion: String,
        modelSha256: String,
        appVersion: String
    ): File {
        val tunnel = TunnelState.current()
        val file = File(directory(context), "device-report-${stamp.format(Date())}.md")
        val text = buildString {
            appendLine("# SentinelAI device test report")
            appendLine()
            appendLine("Generated: ${Formatters.dateTime(System.currentTimeMillis())}")
            appendLine()
            appendLine("## Device")
            appendLine()
            appendLine("| Field | Value |")
            appendLine("|---|---|")
            appendLine("| Manufacturer | ${Build.MANUFACTURER} |")
            appendLine("| Model | ${Build.MODEL} |")
            appendLine("| Device | ${Build.DEVICE} |")
            appendLine("| Android | ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) |")
            appendLine("| ABI | ${Build.SUPPORTED_ABIS.joinToString()} |")
            appendLine("| App version | $appVersion |")
            appendLine()
            appendLine("## Model")
            appendLine()
            appendLine("| Field | Value |")
            appendLine("|---|---|")
            appendLine("| Model version | $modelVersion |")
            appendLine("| SHA-256 | `$modelSha256` |")
            appendLine("| On-device parity | ${selfTest?.summary ?: "NOT MEASURED (run the self-test)"} |")
            appendLine("| Runtime | ONNX Runtime Mobile, float32 |")
            appendLine("| Backend parity (desktop) | 140/140 rows, max probability delta 2.731e-04 |")
            appendLine()
            appendLine("## Stored data")
            appendLine()
            appendLine("| Field | Value |")
            appendLine("|---|---|")
            appendLine("| Flows stored | $flowCount |")
            appendLine("| Alerts stored | $alertCount |")
            appendLine("| Block events stored | $blockedCount |")
            appendLine()
            appendLine("## Last tunnel session")
            appendLine()
            appendLine("| Field | Value |")
            appendLine("|---|---|")
            appendLine("| Status | ${tunnel.status} |")
            appendLine("| Packets observed | ${tunnel.packetsIn} |")
            appendLine("| Bytes observed | ${Formatters.bytes(tunnel.bytesIn)} |")
            appendLine("| DNS queries relayed | ${tunnel.dnsQueries} |")
            appendLine("| Flows persisted | ${tunnel.flowsPersisted} |")
            appendLine("| Blocked packets | ${tunnel.blockedPackets} |")
            appendLine("| Dropped packets | ${tunnel.droppedPackets} |")
            appendLine("| Queue drops | ${tunnel.queueDrops} |")
            appendLine("| DNS upstream | ${tunnel.dnsUpstream ?: "—"} |")
            appendLine("| Last error | ${tunnel.lastError ?: "none"} |")
            appendLine()
            appendLine("## Not measured by this report")
            appendLine()
            appendLine("- Throughput and packet loss (needs a speed test run while protection is on).")
            appendLine("- Battery drain per hour with protection on versus off.")
            appendLine("- Thermal behaviour over a long session.")
            appendLine("- Whether a blocking rule actually dropped a real connection (verify by hand, see SETUP.md).")
            appendLine("- Real-world detection accuracy. The model's held-out CIC-IDS2017 macro-F1 of 0.9499 is a research")
            appendLine("  dataset result and is not a measurement of this device's traffic.")
            appendLine()
            appendLine("## How this build works")
            appendLine()
            appendLine("- Protection mode routes only DNS and user-blocked address ranges through the tunnel.")
            appendLine("- Capture mode routes everything for a bounded window and drops non-DNS packets, so the")
            appendLine("  device has no internet during that window. It is how full flows are observed without a")
            appendLine("  userspace TCP stack.")
            appendLine("- TCP is never forwarded: injecting TCP back out requires a full userspace stack.")
        }
        file.writeText(text)
        return file
    }
}
