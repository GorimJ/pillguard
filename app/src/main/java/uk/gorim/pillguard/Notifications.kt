package uk.gorim.pillguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object Notifications {
    const val CH_ALARM = "alarm"
    const val CH_INFO = "info"
    const val ID_ALARM = 1
    const val ID_INFO = 2

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_ALARM) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ALARM, ctx.getString(R.string.notif_channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Medication is due. Sound is played by the app itself."
                    setSound(null, null)
                    enableVibration(false)
                    setBypassDnd(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }
        if (nm.getNotificationChannel(CH_INFO) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_INFO, ctx.getString(R.string.notif_channel_info), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When it is and isn't OK to eat."
                }
            )
        }
    }

    fun info(ctx: Context, title: String, text: String) {
        ensureChannels(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_INFO, n) }
    }
}
