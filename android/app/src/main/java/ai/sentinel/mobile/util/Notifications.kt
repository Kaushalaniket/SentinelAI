package ai.sentinel.mobile.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import ai.sentinel.mobile.MainActivity
import ai.sentinel.mobile.R
import ai.sentinel.mobile.data.db.AlertEntity
import ai.sentinel.mobile.net.VpnControlReceiver

object Notifications {

    const val CHANNEL_THREATS = "threats"
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_FIREWALL = "firewall"

    const val FOREGROUND_ID = 1001
    private const val ALERT_ID_BASE = 2000

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_THREATS, "Threat alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "AI and rule detections on captured traffic"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Protection service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Tunnel status and the emergency stop control"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_FIREWALL, "Firewall", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Blocked connection attempts"
            }
        )
    }

    fun foreground(context: Context, title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getBroadcast(
            context, 1, Intent(context, VpnControlReceiver::class.java).setAction(VpnControlReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Emergency stop", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun threat(context: Context, alert: AlertEntity) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_THREATS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${alert.category} ${alert.dstIp}")
            .setContentText(alert.reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.reason))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(ALERT_ID_BASE + (alert.id % 1000).toInt(), notification)
    }
}
