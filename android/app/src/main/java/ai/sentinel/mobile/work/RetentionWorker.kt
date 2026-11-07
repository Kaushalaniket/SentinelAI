package ai.sentinel.mobile.work

import android.content.Context
import ai.sentinel.mobile.SentinelApp
import ai.sentinel.mobile.data.db.DailyStatEntity
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Daily housekeeping: enforce retention, drop expired rules and refresh the daily aggregate the
 * dashboard reads. Keeps the database bounded on a 128 GB device without user action.
 */
class RetentionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SentinelApp ?: return Result.failure()
        val container = app.container
        val settings = container.settings.current()
        return try {
            container.rules.purgeExpired()
            container.flows.trim(settings.flowRetentionDays, settings.flowRetentionRows)
            container.alerts.trim(settings.alertRetentionDays)
            container.blocks.trim(settings.alertRetentionDays)

            val day = dayKey(System.currentTimeMillis())
            val since = startOfDay(System.currentTimeMillis())
            container.stats.upsert(
                DailyStatEntity(
                    day = day,
                    flows = container.flows.countSince(since),
                    threats = container.flows.threatsSince(since),
                    blocked = container.blocks.countSince(since),
                    bytes = container.flows.bytesSince(since)
                )
            )
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "sentinelai-retention"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun dayKey(epochMillis: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(epochMillis)

        fun startOfDay(epochMillis: Long): Long =
            Calendar.getInstance().apply {
                timeInMillis = epochMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
    }
}
