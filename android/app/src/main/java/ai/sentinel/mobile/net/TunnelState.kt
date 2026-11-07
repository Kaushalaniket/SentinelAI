package ai.sentinel.mobile.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single source of truth for what the tunnel is doing. Written by the service on its own
 * threads, read by every screen. Nothing here is inferred or estimated: counters are
 * incremented where the work happens, and an error string is only set from a caught failure.
 */
object TunnelState {

    /** Number of samples kept for the dashboard charts (5 s cadence, so two minutes). */
    private const val SERIES_CAPACITY = 24

    enum class Status { STOPPED, STARTING, DNS_ONLY, CAPTURE, ERROR }

    data class Snapshot(
        val status: Status = Status.STOPPED,
        val startedAt: Long = 0L,
        val packetsIn: Long = 0L,
        val bytesIn: Long = 0L,
        val dnsQueries: Long = 0L,
        val flowsObserved: Long = 0L,
        val flowsPersisted: Long = 0L,
        val droppedPackets: Long = 0L,
        val queueDrops: Long = 0L,
        val blockedPackets: Long = 0L,
        val activeFlows: Int = 0,
        val captureRemainingMs: Long = 0L,
        val lastError: String? = null,
        val dnsUpstream: String? = null,
        val restartCount: Int = 0
    ) {
        val isRunning: Boolean get() = status == Status.DNS_ONLY || status == Status.CAPTURE
        val isCapture: Boolean get() = status == Status.CAPTURE
    }

    private val lock = Any()
    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun current(): Snapshot = _state.value

    fun update(transform: (Snapshot) -> Snapshot) {
        synchronized(lock) { _state.value = transform(_state.value) }
    }

    fun reset() {
        synchronized(lock) { _state.value = Snapshot() }
    }

    // Rolling series of counter snapshots. Updated on every packet; consumed by
    // the UI for the live charts and wavelines.
    private val _packetSeries = MutableStateFlow(mutableListOf<Long>())
    val packetSeries: StateFlow<MutableList<Long>> = _packetSeries.asStateFlow()
    private val _bytesSeries = MutableStateFlow(mutableListOf<Long>())
    val bytesSeries: StateFlow<MutableList<Long>> = _bytesSeries.asStateFlow()
    private val _flowSeries = MutableStateFlow(mutableListOf<Long>())
    val flowSeries: StateFlow<MutableList<Long>> = _flowSeries.asStateFlow()
    private val _dnsSeries = MutableStateFlow(mutableListOf<Long>())
    val dnsSeries: StateFlow<MutableList<Long>> = _dnsSeries.asStateFlow()
    private val _blockSeries = MutableStateFlow(mutableListOf<Long>())
    val blockSeries: StateFlow<MutableList<Long>> = _blockSeries.asStateFlow()

    /**
     * Called from the packet path; intentionally cheap. Samples are taken on [tick] so the
     * charts move at a fixed cadence instead of once per packet.
     */
    fun pushSeries() = Unit

    // Live counter snapshots, refreshed each tick so the UI can show a
    // current value without reading the whole rolling series.
    private val _livePackets = MutableStateFlow(0L)
    val livePackets: StateFlow<Long> = _livePackets.asStateFlow()
    private val _liveBytes = MutableStateFlow(0L)
    val liveBytes: StateFlow<Long> = _liveBytes.asStateFlow()
    private val _liveFlows = MutableStateFlow(0L)
    val liveFlows: StateFlow<Long> = _liveFlows.asStateFlow()
    private val _liveDns = MutableStateFlow(0L)
    val liveDns: StateFlow<Long> = _liveDns.asStateFlow()
    private val _liveBlocked = MutableStateFlow(0L)
    val liveBlocked: StateFlow<Long> = _liveBlocked.asStateFlow()
    private val _liveDropped = MutableStateFlow(0L)
    val liveDropped: StateFlow<Long> = _liveDropped.asStateFlow()

    fun tick() {
        val snap = current()
        _livePackets.value = snap.packetsIn
        _liveBytes.value = snap.bytesIn
        _liveFlows.value = snap.flowsObserved
        _liveDns.value = snap.dnsQueries
        _liveBlocked.value = snap.blockedPackets
        _liveDropped.value = snap.droppedPackets

        _packetSeries.update { append(it, snap.packetsIn) }
        _bytesSeries.update { append(it, snap.bytesIn) }
        _flowSeries.update { append(it, snap.flowsObserved) }
        _dnsSeries.update { append(it, snap.dnsQueries) }
        _blockSeries.update { append(it, snap.blockedPackets) }
    }

    private fun append(series: List<Long>, value: Long): MutableList<Long> =
        (series + value).takeLast(SERIES_CAPACITY).toMutableList()

    fun fail(message: String) {
        synchronized(lock) {
            _state.value = _state.value.copy(status = Status.ERROR, lastError = message)
        }
    }
}
