package uk.gorim.pillguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Keeps exactly one medication alarm armed (the next thing that should ring) plus
 * informational reminders for the eating window. Call [reschedule] after any state change.
 */
object AlarmScheduler {
    const val ACTION_DOSE = "uk.gorim.pillguard.DOSE"
    const val ACTION_INFO = "uk.gorim.pillguard.INFO"
    const val EXTRA_KEY = "key"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"

    const val ACTION_ALERT = "uk.gorim.pillguard.ALERT"
    private const val RC_DOSE = 100
    private const val RC_INFO_OPEN = 101
    private const val RC_INFO_CLOSE = 102
    private const val RC_ALERT = 105

    private fun alertPi(ctx: Context, key: String?): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALERT)
        if (key != null) i.putExtra(EXTRA_KEY, key)
        return PendingIntent.getBroadcast(ctx, RC_ALERT, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    private fun dosePi(ctx: Context, key: String?): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_DOSE)
        if (key != null) i.putExtra(EXTRA_KEY, key)
        return PendingIntent.getBroadcast(ctx, RC_DOSE, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun infoPi(ctx: Context, rc: Int, title: String?, text: String?): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_INFO + rc)
        if (title != null) i.putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)
        return PendingIntent.getBroadcast(ctx, rc, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @Synchronized
    fun reschedule(ctx: Context) {
        val store = Store.get(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val engine = store.engine()

        // Anything pending in the past that has been superseded is now formally missed.
        val recs = store.records
        engine.window(now).filter { it.status == DoseStatus.MISSED && recs[it.key]?.missed != true }
            .forEach { store.markMissed(it.key) }
        // Clear stale ringing state.
        store.ringingKey?.let { rk ->
            val inst = engine.window(now).firstOrNull { it.key == rk }
            if (inst == null || inst.status != DoseStatus.PENDING) { store.ringingKey = null; store.snoozeUntil = 0 }
        }

        val due = engine.dueDose(now)
        val next = engine.nextUpcoming(now)
        val snoozeUntil = store.snoozeUntil

        am.cancel(dosePi(ctx, null))
        when {
            due != null && AlarmService.ringingKey == due.key -> Unit // already ringing; leave it alone
            due != null && snoozeUntil > now -> setExact(am, ctx, snoozeUntil, dosePi(ctx, due.key))
            due != null -> {
                // Overdue and not snoozed: arm an alarm-clock alarm for "now". Going through AlarmManager
                // (rather than broadcasting directly) is what grants the foreground-service start exemption.
                store.snoozeUntil = 0
                setExact(am, ctx, now, dosePi(ctx, due.key))
            }
            next != null -> setExact(am, ctx, next.effectiveMillis, dosePi(ctx, next.key))
        }

        val s = store.settings

        // Carer alert if the current/next dose is still unconfirmed N minutes after it was due.
        am.cancel(alertPi(ctx, null))
        if (s.alertsEnabled && s.alertTopic.isNotEmpty()) {
            val target = due ?: next
            if (target != null && store.records[target.key]?.alerted != true) {
                val at = target.effectiveMillis + s.alertAfterMin * 60_000L
                setInexact(am, maxOf(at, now + 1000), alertPi(ctx, target.key))
            }
        }

        // Eating-window reminders.
        am.cancel(infoPi(ctx, RC_INFO_OPEN, null, null))
        am.cancel(infoPi(ctx, RC_INFO_CLOSE, null, null))
        val last = engine.lastTaken(now)
        if (last != null) {
            val opens = last.takenAt + s.eatAfterMin * 60_000L
            val closes = next?.let { it.effectiveMillis - s.eatBeforeMin * 60_000L } ?: 0L
            if (opens > now && (closes == 0L || closes > opens)) {
                val text = if (closes > 0) "You can eat now. Finish by ${TimeFmt.hm(closes)}." else "You can eat now."
                setInexact(am, opens, infoPi(ctx, RC_INFO_OPEN, "OK to eat", text))
            }
        }
        if (next != null) {
            val closes = next.effectiveMillis - s.eatBeforeMin * 60_000L
            if (closes > now) {
                setInexact(
                    am, closes,
                    infoPi(ctx, RC_INFO_CLOSE, "Stop eating now", "${next.label} dose at ${TimeFmt.hm(next.effectiveMillis)} — no food from now.")
                )
            }
        }
    }

    const val TEST_KEY = "test"

    /** Fires a fake alarm after [delayMs] so the setup can be checked (lock screen, sound, QR). */
    fun scheduleTest(ctx: Context, delayMs: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_DOSE).putExtra(EXTRA_KEY, TEST_KEY)
        val pi = PendingIntent.getBroadcast(ctx, 103, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        setExact(am, ctx, System.currentTimeMillis() + delayMs, pi)
    }

    private fun setExact(am: AlarmManager, ctx: Context, at: Long, pi: PendingIntent) {
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (canExact(ctx)) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } else {
            // Degraded mode (Android 12/13 without the exact-alarm permission): may be late by minutes.
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun setInexact(am: AlarmManager, at: Long, pi: PendingIntent) {
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}
