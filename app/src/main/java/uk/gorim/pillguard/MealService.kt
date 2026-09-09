package uk.gorim.pillguard

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Short-lived foreground service for a meal reminder.
 *
 * It exists for one reason: a plain BroadcastReceiver is a background context, and Android 10+
 * silently refuses startActivity() from one, so the meal screen never appeared while the phone was
 * unlocked — only the heads-up notification did. The pill alarm never had that problem because it
 * goes through AlarmService. This does the same thing for meals: come up in the foreground, put the
 * screen up, play the bugle, then hand the notification back to the system and stop.
 */
class MealService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_LABEL = "label"
        const val EXTRA_TEXT = "text"
        const val EXTRA_AMP = "amp"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: android.media.MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopAll(remove = true); return START_NOT_STICKY }

        val key = intent?.getStringExtra(AlarmScheduler.EXTRA_KEY)
        if (key == null) { stopSelf(); return START_NOT_STICKY }
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "Meal"
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val amp = intent.getFloatExtra(EXTRA_AMP, -1f).takeIf { it >= 0f }

        val n = Notifications.mealNotification(this, key, label, text)
        val foreground = try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifications.ID_MEAL, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(Notifications.ID_MEAL, n)
            }
            true
        } catch (e: Exception) {
            // Never go silent: post the same notification plainly and carry on without the service.
            runCatching { getSystemService(NotificationManager::class.java).notify(Notifications.ID_MEAL, n) }
            Store.get(this).log("Meal service could not start (${e.javaClass.simpleName}); notification only")
            false
        }

        // Being in the foreground is what makes this succeed where the receiver's attempt was blocked.
        runCatching { startActivity(Notifications.mealActivityIntent(this, key, label)) }

        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31)
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1), attrs)
        }

        player = MealSound.play(this, amp) { stopAll(remove = false) }
        // Backstop in case the clip never reports completion.
        handler.postDelayed({ stopAll(remove = false) }, 30_000)

        if (!foreground) stopSelf()
        return START_NOT_STICKY
    }

    /** Leaves the notification behind (unless asked to clear it) so "Eating now" stays reachable. */
    private fun stopAll(remove: Boolean) {
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        runCatching {
            stopForeground(if (remove) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        }
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { player?.release() }
        super.onDestroy()
    }
}
