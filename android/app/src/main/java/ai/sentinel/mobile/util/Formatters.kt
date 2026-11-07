package ai.sentinel.mobile.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Formatters {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dateTimeFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)

    fun time(epochMillis: Long): String = timeFormat.format(Date(epochMillis))
    fun day(epochMillis: Long): String = dayFormat.format(Date(epochMillis))
    fun dateTime(epochMillis: Long): String = dateTimeFormat.format(Date(epochMillis))

    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = value.toDouble() / 1024.0
        var index = 0
        while (size >= 1024 && index < units.size - 1) {
            size /= 1024.0
            index++
        }
        return String.format(Locale.US, "%.1f %s", size, units[index])
    }

    fun rate(bytesPerSecond: Double): String = bytes(bytesPerSecond.toLong()) + "/s"

    fun duration(millis: Long): String = when {
        millis < 1000 -> "${millis}ms"
        millis < 60_000 -> String.format(Locale.US, "%.1fs", millis / 1000.0)
        millis < 3_600_000 -> "${TimeUnit.MILLISECONDS.toMinutes(millis)}m ${TimeUnit.MILLISECONDS.toSeconds(millis) % 60}s"
        else -> "${TimeUnit.MILLISECONDS.toHours(millis)}h ${TimeUnit.MILLISECONDS.toMinutes(millis) % 60}m"
    }

    fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", value * 100)

    fun ago(epochMillis: Long): String {
        if (epochMillis <= 0) return "never"
        val delta = System.currentTimeMillis() - epochMillis
        return if (delta < 60_000) "just now" else duration(delta) + " ago"
    }
}
