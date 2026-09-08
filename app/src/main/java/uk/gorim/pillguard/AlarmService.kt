package uk.gorim.pillguard

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

/**
 * Foreground service that rings, without stopping on its own, until the dose is confirmed (QR scan /
 * carer PIN) or "I'm going to get the pill" is pressed (quiet for reRingMin, then rings again).
 * Plays on the ALARM audio stream so silent mode and Do Not Disturb don't mute it.
 */
class AlarmService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_SNOOZE = "snooze"
        const val ACTION_PAUSE = "pause"
        const val EXTRA_PAUSE_SECONDS = "pauseSeconds"
        /** Breathing space while he opens the scanner or the delay screen — long enough to act. */
        const val DEFAULT_PAUSE_SECONDS = 60
        @Volatile var ringingKey: String? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var rampRunnable: Runnable? = null
    private var resumeRunnable: Runnable? = null
    private var rampAnchor = 0L
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopRinging(); return START_NOT_STICKY }
            ACTION_SNOOZE -> { snooze(auto = false); return START_NOT_STICKY }
            ACTION_PAUSE -> {
                pauseFor(intent.getIntExtra(EXTRA_PAUSE_SECONDS, DEFAULT_PAUSE_SECONDS))
                return START_NOT_STICKY
            }
        }
        // A null intent means the system restarted us; the armed AlarmManager alarm is the source of truth, so just stop.
        val key = intent?.getStringExtra(AlarmScheduler.EXTRA_KEY)
        if (key == null) { stopSelf(); return START_NOT_STICKY }

        val store = Store.get(this)
        val isTest = key == AlarmScheduler.TEST_KEY
        val inst = if (isTest) null else store.engine().window(System.currentTimeMillis()).firstOrNull { it.key == key }
        val label = if (isTest) "TEST" else inst?.label ?: store.labelFor(key)
        val time = inst?.let { TimeFmt.hm(it.effectiveMillis) } ?: TimeFmt.hm(System.currentTimeMillis())

        if (!startForegroundWithNotification(key, label, time)) return START_NOT_STICKY
        if (ringingKey != key) {
            if (ringingKey != null) releaseRinging() // a different dose took over: stop the old sound first
            ringingKey = key
            // Escalate from when this dose first came due, not from this bout — pressing
            // "I'm going to get the pill" and wandering off should not reset it to a whisper.
            rampAnchor = inst?.effectiveMillis?.takeIf { it <= System.currentTimeMillis() } ?: System.currentTimeMillis()
            startRinging()
            store.log("$label alarm ringing")
            // Also try to bring the alarm screen up directly (works when the app is allowed to; the
            // full-screen intent on the notification covers the locked-screen case).
            runCatching { startActivity(alarmActivityIntent(key)) }
        }
        return START_NOT_STICKY
    }

    private fun alarmActivityIntent(key: String) = Intent(this, AlarmActivity::class.java)
        .putExtra(AlarmScheduler.EXTRA_KEY, key)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** Returns false if the foreground service could not be started (the notification is still posted). */
    private fun startForegroundWithNotification(key: String, label: String, time: String): Boolean {
        Notifications.ensureChannels(this)
        val full = PendingIntent.getActivity(
            this, 1, alarmActivityIntent(key), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozePi = PendingIntent.getService(
            this, 2, Intent(this, AlarmService::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, Notifications.CH_ALARM)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Go and get your $label pills ($time)")
            .setContentText("Scan the code on the pill container once you have them. The alarm repeats until you do.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setColor(androidx.core.content.ContextCompat.getColor(this, R.color.pill_alarm_bg))
            .setColorized(true)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .addAction(0, "I'm going to get it", snoozePi)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifications.ID_ALARM, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(Notifications.ID_ALARM, n)
            }
            true
        } catch (e: Exception) {
            // Background start not allowed (should not happen via setAlarmClock, but never go silent):
            // fall back to a plain high-priority notification with the full-screen intent.
            runCatching { getSystemService(NotificationManager::class.java).notify(Notifications.ID_ALARM, n) }
            Store.get(this).log("Alarm service could not start (${e.javaClass.simpleName}); notification only")
            stopSelf()
            false
        }
    }

    private fun startRinging() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // No timeout: the alarm rings until someone interacts with it. Released in releaseRinging().
        if (wakeLock?.isHeld != true) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pillguard:alarm").apply { acquire() }
        }

        val candidates = listOfNotNull(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI,
            android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
        )
        for (uri in candidates) {
            player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(this@AlarmService, uri)
                    isLooping = true
                    val s0 = Store.get(this@AlarmService).settings
                    val a0 = Volume.rampAmp(Volume.pill(s0), System.currentTimeMillis() - rampAnchor)
                    setVolume(a0, a0)
                    prepare()
                    start()
                }
            }.getOrNull()
            if (player != null) break
        }

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 800, 400, 800, 1200)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs) }

        startVolumeRamp()
    }

    /**
     * Climbs from the start volume to full over volumeRampMin, so a phone left in another room
     * gets progressively louder instead of startling him when it is next to his chair.
     */
    private fun startVolumeRamp() {
        rampRunnable?.let { handler.removeCallbacks(it) }
        if (rampAnchor == 0L) rampAnchor = System.currentTimeMillis()
        val settings = Store.get(this).settings
        val r = object : Runnable {
            override fun run() {
                val p = player ?: return
                val amp = Volume.rampAmp(Volume.pill(settings), System.currentTimeMillis() - rampAnchor)
                runCatching { p.setVolume(amp, amp) }
                if (amp < Volume.maxAmp(Volume.pill(settings))) handler.postDelayed(this, 10_000)
            }
        }
        rampRunnable = r
        handler.postDelayed(r, 10_000)
    }

    private fun snooze(auto: Boolean) {
        val store = Store.get(this)
        val key = ringingKey ?: store.ringingKey
        if (key == AlarmScheduler.TEST_KEY) {
            stopRinging()
            if (!auto) AlarmScheduler.scheduleTest(this, store.settings.reRingMin * 60_000L)
            return
        }
        if (key != null) store.recordSnooze(key, store.settings.reRingMin, auto)
        stopRinging()
        AlarmScheduler.reschedule(this)
    }

    /** Silences the sound and vibration but keeps the alarm alive (service, notification, key). */
    private fun stopSound() {
        rampRunnable?.let { handler.removeCallbacks(it) }
        rampRunnable = null
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        runCatching { vibrator?.cancel() }
    }

    /**
     * Goes quiet for [seconds] while he deals with it — opening the scanner, or the delay screen —
     * then picks up again if the dose is still unconfirmed. Nothing else about the alarm changes.
     */
    private fun pauseFor(seconds: Int) {
        val k = ringingKey ?: return
        stopSound()
        Store.get(this).log("${Store.get(this).labelFor(k)} alarm quiet for ${seconds}s while he deals with it")
        resumeRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            resumeRunnable = null
            if (ringingKey != null) startRinging()   // still unconfirmed: back on, at the ramped level
        }
        resumeRunnable = r
        handler.postDelayed(r, seconds * 1_000L)
    }

    private fun releaseRinging() {
        resumeRunnable?.let { handler.removeCallbacks(it) }
        resumeRunnable = null
        stopSound()
        rampAnchor = 0L
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        ringingKey = null
    }

    private fun stopRinging() {
        releaseRinging()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { player?.release() }
        runCatching { vibrator?.cancel() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        ringingKey = null
        super.onDestroy()
    }
}
