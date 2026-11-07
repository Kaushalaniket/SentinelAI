package ai.sentinel.mobile.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class TunnelMode { DNS_ONLY, CAPTURE }

data class SentinelSettings(
    val protectionEnabled: Boolean = false,
    val confidenceThreshold: Double = 0.5,
    val flowRetentionDays: Int = 7,
    val flowRetentionRows: Int = 100_000,
    val alertRetentionDays: Int = 30,
    val captureSeconds: Int = 60,
    val notificationsEnabled: Boolean = true,
    val detectDnsRules: Boolean = true,
    val blockByDefault: Boolean = false
)

/**
 * Preferences backed by SharedPreferences and exposed as a StateFlow.
 * Deliberately small: every value here changes service behaviour, so it must be readable
 * synchronously from the tunnel thread without a coroutine hop.
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("sentinelai_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<SentinelSettings> = _settings

    private fun read(): SentinelSettings = SentinelSettings(
        protectionEnabled = prefs.getBoolean(KEY_PROTECTION, false),
        confidenceThreshold = prefs.getFloat(KEY_THRESHOLD, 0.5f).toDouble(),
        flowRetentionDays = prefs.getInt(KEY_FLOW_DAYS, 7),
        flowRetentionRows = prefs.getInt(KEY_FLOW_ROWS, 100_000),
        alertRetentionDays = prefs.getInt(KEY_ALERT_DAYS, 30),
        captureSeconds = prefs.getInt(KEY_CAPTURE_SECONDS, 60),
        notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS, true),
        detectDnsRules = prefs.getBoolean(KEY_DNS_RULES, true),
        blockByDefault = prefs.getBoolean(KEY_BLOCK_DEFAULT, false)
    )

    fun current(): SentinelSettings = _settings.value

    fun update(transform: (SentinelSettings) -> SentinelSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putBoolean(KEY_PROTECTION, next.protectionEnabled)
            .putFloat(KEY_THRESHOLD, next.confidenceThreshold.toFloat())
            .putInt(KEY_FLOW_DAYS, next.flowRetentionDays)
            .putInt(KEY_FLOW_ROWS, next.flowRetentionRows)
            .putInt(KEY_ALERT_DAYS, next.alertRetentionDays)
            .putInt(KEY_CAPTURE_SECONDS, next.captureSeconds)
            .putBoolean(KEY_NOTIFICATIONS, next.notificationsEnabled)
            .putBoolean(KEY_DNS_RULES, next.detectDnsRules)
            .putBoolean(KEY_BLOCK_DEFAULT, next.blockByDefault)
            .apply()
        _settings.value = next
    }

    fun setProtectionEnabled(enabled: Boolean) = update { it.copy(protectionEnabled = enabled) }

    private companion object {
        const val KEY_PROTECTION = "protection_enabled"
        const val KEY_THRESHOLD = "confidence_threshold"
        const val KEY_FLOW_DAYS = "flow_retention_days"
        const val KEY_FLOW_ROWS = "flow_retention_rows"
        const val KEY_ALERT_DAYS = "alert_retention_days"
        const val KEY_CAPTURE_SECONDS = "capture_seconds"
        const val KEY_NOTIFICATIONS = "notifications_enabled"
        const val KEY_DNS_RULES = "dns_rules"
        const val KEY_BLOCK_DEFAULT = "block_default"
    }
}
