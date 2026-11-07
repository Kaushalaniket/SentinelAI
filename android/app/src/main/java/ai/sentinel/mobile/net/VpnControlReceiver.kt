package ai.sentinel.mobile.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Emergency stop from the persistent notification. One tap must always be able to take the
 * tunnel down, even if the UI is not running.
 */
class VpnControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP -> SentinelVpnService.stop(context)
            ACTION_STOP_AND_CLEAR -> {
                SentinelVpnService.stop(context)
            }
        }
    }

    companion object {
        const val ACTION_STOP = "ai.sentinel.mobile.action.EMERGENCY_STOP"
        const val ACTION_STOP_AND_CLEAR = "ai.sentinel.mobile.action.STOP_AND_CLEAR"
    }
}
