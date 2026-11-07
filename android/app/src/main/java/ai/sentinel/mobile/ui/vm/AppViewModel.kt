package ai.sentinel.mobile.ui.vm

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.sentinel.mobile.SentinelApp
import ai.sentinel.mobile.data.db.AlertEntity
import ai.sentinel.mobile.data.db.BlockEventEntity
import ai.sentinel.mobile.data.db.CategoryCount
import ai.sentinel.mobile.data.db.DestinationCount
import ai.sentinel.mobile.data.db.FlowEntity
import ai.sentinel.mobile.data.db.RuleEntity
import ai.sentinel.mobile.net.PolicyEngine
import ai.sentinel.mobile.net.TunnelState
import ai.sentinel.mobile.net.VpnController
import ai.sentinel.mobile.util.ReportExporter
import ai.sentinel.mobile.util.SelfTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One view model for the whole app. Every value it exposes is read from Room or from the
 * tunnel's counters; nothing is seeded, sampled or estimated.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as SentinelApp).container

    val settings = container.settings.settings
    val tunnel = TunnelState.state

    val rules = container.rules.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recentFlows = container.flows.latest(60)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recentAlerts = container.alerts.latest(60)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val blockEvents = container.blocks.latest(60)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val flowCount = container.flows.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    // Live rolling series shown in the charts and wavelines. Each sample is a
    // counter value (packets, bytes, flows, DNS queries, blocked packets) taken
    // on a short tick so the bars and line move when activity occurs.
    val livePackets = TunnelState.livePackets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), 0L)
    val liveBytes = TunnelState.liveBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), 0L)
    val liveFlows = TunnelState.liveFlows
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), 0L)
    val liveDns = TunnelState.liveDns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), 0L)
    val liveBlocked = TunnelState.liveBlocked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), 0L)
    val liveDropped = TunnelState.liveDropped
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), 0L)

    // Rolling series (longest-lived) used by WaveLine/LineChart.
    val packetSeries = TunnelState.packetSeries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), mutableListOf<Long>())
    val bytesSeries = TunnelState.bytesSeries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), mutableListOf<Long>())
    val flowSeries = TunnelState.flowSeries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), mutableListOf<Long>())
    val dnsSeries = TunnelState.dnsSeries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), mutableListOf<Long>())
    val blockSeries = TunnelState.blockSeries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), mutableListOf<Long>())

    data class Dashboard(
        val flows24h: Long = 0,
        val threats24h: Long = 0,
        val alerts24h: Long = 0,
        val blocked24h: Long = 0,
        val bytes24h: Long = 0,
        val totalFlows: Long = 0,
        val topDestinations: List<DestinationCount> = emptyList(),
        val categories: List<CategoryCount> = emptyList()
    )

    var dashboard by mutableStateOf(Dashboard())
        private set

    var selfTestResult by mutableStateOf<SelfTest.Result?>(null)
        private set

    var selfTestRunning by mutableStateOf(false)
        private set

    var statusMessage by mutableStateOf<String?>(null)
        private set

    init {
        refreshDashboard()
    }

    fun refreshDashboard() {
        viewModelScope.launch {
            val since = System.currentTimeMillis() - 86_400_000L
            dashboard = Dashboard(
                flows24h = container.flows.countSince(since),
                threats24h = container.flows.threatsSince(since),
                alerts24h = container.alerts.countSince(since),
                blocked24h = container.blocks.countSince(since),
                bytes24h = container.flows.bytesSince(since),
                totalFlows = container.flows.count(),
                topDestinations = container.flows.topDestinations(since, 8),
                categories = container.flows.categoryCounts(since)
            )
        }
    }

    fun startProtection() {
        VpnController.startProtection(getApplication())
    }

    fun startCapture(seconds: Int) {
        VpnController.startCapture(getApplication(), seconds)
    }

    fun stopTunnel() {
        VpnController.stop(getApplication())
    }

    /** Returns null on success, or a human reason when the rule was refused. */
    fun addRule(type: String, value: String, note: String): String? {
        val engine = PolicyEngine()
        engine.validate(type, value)?.let { return it }
        viewModelScope.launch {
            container.rules.insert(
                RuleEntity(
                    type = type,
                    value = value.trim(),
                    action = "block",
                    enabled = true,
                    createdAt = System.currentTimeMillis(),
                    note = note.trim()
                )
            )
            VpnController.restartIfRunning(getApplication())
            refreshDashboard()
        }
        return null
    }

    fun toggleRule(rule: RuleEntity) {
        viewModelScope.launch {
            container.rules.setEnabled(rule.id, !rule.enabled)
            VpnController.restartIfRunning(getApplication())
        }
    }

    fun deleteRule(id: Long) {
        viewModelScope.launch {
            container.rules.delete(id)
            VpnController.restartIfRunning(getApplication())
        }
    }

    fun acknowledge(id: Long) {
        viewModelScope.launch { container.alerts.acknowledge(id) }
    }

    fun acknowledgeAll() {
        viewModelScope.launch { container.alerts.acknowledgeAll() }
    }

    fun updateThreshold(value: Double) {
        container.settings.update { it.copy(confidenceThreshold = value) }
    }

    fun updateCaptureSeconds(seconds: Int) {
        container.settings.update { it.copy(captureSeconds = seconds.coerceIn(15, 300)) }
    }

    fun updateRetention(days: Int, rows: Int) {
        container.settings.update { it.copy(flowRetentionDays = days, flowRetentionRows = rows) }
    }

    fun setNotifications(enabled: Boolean) {
        container.settings.update { it.copy(notificationsEnabled = enabled) }
    }

    fun runSelfTest() {
        if (selfTestRunning) return
        selfTestRunning = true
        viewModelScope.launch {
            selfTestResult = SelfTest(
                getApplication(),
                container.contract,
                container.detector
            ).run()
            selfTestRunning = false
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            container.flows.deleteAll()
            container.alerts.deleteAll()
            container.blocks.deleteAll()
            refreshDashboard()
            statusMessage = "History cleared"
        }
    }

    fun trimNow() {
        viewModelScope.launch {
            val current = container.settings.current()
            val removed = container.flows.trim(current.flowRetentionDays, current.flowRetentionRows)
            container.alerts.trim(current.alertRetentionDays)
            container.blocks.trim(current.alertRetentionDays)
            refreshDashboard()
            statusMessage = "Trimmed ${removed.first + removed.second} flows"
        }
    }

    fun exportFlows(): File? {
        var file: File? = null
        viewModelScope.launch {
            val flows = container.flows.recentSince(0, 5000)
            file = withContext(Dispatchers.IO) { ReportExporter.exportFlowsCsv(getApplication(), flows) }
        }
        return file
    }

    fun exportReport(onReady: (File) -> Unit) {
        viewModelScope.launch {
            val current = container.settings.current()
            val file = withContext(Dispatchers.IO) {
                ReportExporter.writeDeviceReport(
                    context = getApplication(),
                    selfTest = selfTestResult,
                    flowCount = container.flows.count(),
                    alertCount = container.alerts.countSince(0),
                    blockedCount = container.blocks.countSince(0),
                    modelVersion = container.contract.modelVersion,
                    modelSha256 = container.contract.sha256,
                    appVersion = appVersion()
                )
            }
            onReady(file)
        }
    }

    fun dismissStatus() {
        statusMessage = null
    }

    suspend fun flowPage(
        protocol: String,
        category: String,
        query: String,
        limit: Int,
        offset: Int
    ): List<FlowEntity> = withContext(Dispatchers.IO) {
        container.flows.pageFiltered(protocol, category, query, limit, offset)
    }

    suspend fun allAlerts(): List<AlertEntity> = withContext(Dispatchers.IO) {
        container.alerts.recent(500)
    }

    suspend fun allBlockEvents(): List<BlockEventEntity> = withContext(Dispatchers.IO) {
        var offset = 0
        val collected = ArrayList<BlockEventEntity>()
        while (collected.size < 500) {
            val page = container.blocks.page(100, offset)
            if (page.isEmpty()) break
            collected.addAll(page)
            offset += page.size
            if (page.size < 100) break
        }
        collected
    }

    suspend fun blocklistSummary(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val list = ai.sentinel.mobile.net.Blocklist.load(getApplication())
        list.domainCount to list.addressCount
    }

    suspend fun rangeSummary(days: Int): Dashboard = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - days * 86_400_000L
        Dashboard(
            flows24h = container.flows.countSince(since),
            threats24h = container.flows.threatsSince(since),
            alerts24h = container.alerts.countSince(since),
            blocked24h = container.blocks.countSince(since),
            bytes24h = container.flows.bytesSince(since),
            totalFlows = container.flows.count(),
            topDestinations = container.flows.topDestinations(since, 8),
            categories = container.flows.categoryCounts(since)
        )
    }

    fun exportAlerts(onReady: (File) -> Unit) {
        viewModelScope.launch {
            val alerts = container.alerts.recent(5000)
            val file = withContext(Dispatchers.IO) {
                ReportExporter.exportAlertsCsv(getApplication(), alerts)
            }
            onReady(file)
        }
    }

    fun exportFlowsAsync(onReady: (File) -> Unit) {
        viewModelScope.launch {
            val flows = container.flows.recentSince(0, 5000)
            val file = withContext(Dispatchers.IO) {
                ReportExporter.exportFlowsCsv(getApplication(), flows)
            }
            onReady(file)
        }
    }

    private fun appVersion(): String {
        val context = getApplication<Application>()
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Throwable) {
            "unknown"
        }
    }
}
