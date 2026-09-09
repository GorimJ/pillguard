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
    /**
     * Channel settings are immutable once created, so raising the meal channel to a full alarm-grade
     * channel (bypasses Do Not Disturb, like the pill one) means a new id. The old one is deleted.
     */
    const val CH_MEAL = "meal2"
    private const val CH_MEAL_OLD = "meal"
    const val ID_ALARM = 1
    const val ID_INFO = 2
    const val ID_MEAL = 3

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
        if (nm.getNotificationChannel(CH_MEAL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_MEAL, "Meal reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Time to eat. The bugle is played by the app itself."
                    setSound(null, null)
                    enableVibration(true)
                    setBypassDnd(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }
        runCatching { nm.deleteNotificationChannel(CH_MEAL_OLD) }
        if (nm.getNotificationChannel(CH_INFO) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_INFO, ctx.getString(R.string.notif_channel_info), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When it is and isn't OK to eat."
                }
            )
        }
    }

    fun mealActivityIntent(ctx: Context, key: String, label: String): Intent =
        Intent(ctx, MealActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_KEY, key).putExtra(AlarmScheduler.EXTRA_TITLE, label)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun meal(ctx: Context, key: String, label: String, text: String) {
        runCatching {
            ctx.getSystemService(NotificationManager::class.java).notify(ID_MEAL, mealNotification(ctx, key, label, text))
        }
    }

    /** The meal reminder notification, also used as MealService's foreground notification. */
    fun mealNotification(ctx: Context, key: String, label: String, text: String): android.app.Notification {
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 3, mealActivityIntent(ctx, key, label), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ate = PendingIntent.getBroadcast(
            ctx, 201, Intent(ctx, AlarmReceiver::class.java).setAction(AlarmScheduler.ACTION_MEAL_ATE).putExtra(AlarmScheduler.EXTRA_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getBroadcast(
            ctx, 202, Intent(ctx, AlarmReceiver::class.java).setAction(AlarmScheduler.ACTION_MEAL_STOP).putExtra(AlarmScheduler.EXTRA_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, CH_MEAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("$label time")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            // CATEGORY_ALARM, not REMINDER: some builds only honour a full-screen intent for
            // alarm/call notifications, and this reminder is an alarm in everything but name.
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.meal_alarm_bg))
            .setColorized(true)
            .addAction(0, "Eating now", ate)
            .addAction(0, "Not today", stop)
            .setAutoCancel(false)
            .build()
    }

    fun cancelMeal(ctx: Context) {
        runCatching { ctx.stopService(Intent(ctx, MealService::class.java)) }
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(ID_MEAL) }
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
