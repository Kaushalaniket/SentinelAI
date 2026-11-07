package ai.sentinel.mobile.net

import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * UI-facing tunnel control. Consent is always requested through the system dialog; nothing here
 * can start a tunnel without the user having granted it.
 */
object VpnController {

    fun consentIntent(context: Context): Intent? = VpnService.prepare(context)

    fun startProtection(context: Context) {
        SentinelVpnService.startDnsOnly(context)
    }

    fun startCapture(context: Context, seconds: Int) {
        SentinelVpnService.startCapture(context, seconds)
    }

    fun stop(context: Context) {
        SentinelVpnService.stop(context)
    }

    /** Firewall routes are fixed when the tunnel is built, so a rule change restarts it. */
    fun restartIfRunning(context: Context) {
        val snapshot = TunnelState.current()
        if (!snapshot.isRunning) return
        if (snapshot.isCapture) return
        SentinelVpnService.stop(context)
        SentinelVpnService.startDnsOnly(context)
    }
}
