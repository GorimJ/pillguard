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
    /** The "I'm going out" window has run out. */
    const val ACTION_QUIET_OVER = "uk.gorim.pillguard.QUIET_OVER"
    /** He is back early and wants the alarms back. */
    const val ACTION_QUIET_END = "uk.gorim.pillguard.QUIET_END"
    private const val RC_QUIET = 108
    const val ACTION_MEAL = "uk.gorim.pillguard.MEAL"
    const val ACTION_MEAL_ATE = "uk.gorim.pillguard.MEAL_ATE"
    const val ACTION_MEAL_STOP = "uk.gorim.pillguard.MEAL_STOP"
    private const val RC_MEAL = 107

    private fun mealPi(ctx: Context, key: String?): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_MEAL)
        if (key != null) i.putExtra(EXTRA_KEY, key)
        return PendingIntent.getBroadcast(ctx, RC_MEAL, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
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
        // While he is out nothing rings, so an overdue dose must be armed for the END of the window
        // rather than for "now" — otherwise it fires, gets silenced, re-arms, and spins.
        val notBefore = maxOf(now, store.quietUntil)
        when {
            due != null && AlarmService.ringingKey == due.key -> Unit // already ringing; leave it alone
            due != null && snoozeUntil > now -> setExact(am, ctx, maxOf(snoozeUntil, notBefore), dosePi(ctx, due.key))
            due != null -> {
                // Overdue and not snoozed: arm an alarm-clock alarm for "now". Going through AlarmManager
                // (rather than broadcasting directly) is what grants the foreground-service start exemption.
                store.snoozeUntil = 0
                setExact(am, ctx, notBefore, dosePi(ctx, due.key))
            }
            next != null -> setExact(am, ctx, maxOf(next.effectiveMillis, notBefore), dosePi(ctx, next.key))
        }

        val s = store.settings
        val quietUntil = store.quietUntil
        val quiet = quietUntil > now
        // Wake up when the quiet window runs out, to clear its notification and re-arm everything.
        val quietPi = PendingIntent.getBroadcast(
            ctx, RC_QUIET, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_QUIET_OVER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(quietPi)
        if (quiet) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quietUntil + 1000, quietPi)
            Notifications.quiet(ctx, quietUntil)
        } else {
            Notifications.cancelQuiet(ctx)
            if (quietUntil > 0) store.quietUntil = 0
        }
        StatusWidget.updateAll(ctx)

        // Carer alert if the current/next dose is still unconfirmed N minutes after it was due.
        am.cancel(alertPi(ctx, null))
        if (s.alertsEnabled && s.alertTopic.isNotEmpty()) {
            val target = due ?: next
            if (target != null && store.records[target.key]?.alerted != true) {
                val at = target.effectiveMillis + s.alertAfterMin * 60_000L
                setInexact(am, maxOf(at, now + 1000), alertPi(ctx, target.key))
            }
        }

        // Meal reminders (bugle). While he is out, look past the quiet window instead of arming
        // inside it — a reminder he cannot act on is exactly what he asked not to have.
        am.cancel(mealPi(ctx, null))
        store.mealLogic().next(maxOf(now, quietUntil))?.let { d ->
            // setAlarmClock, like the dose alarms: it is the alarm-clock grade of alarm that carries
            // the exemption letting the reminder start its foreground service and put a screen up.
            setExact(am, ctx, maxOf(d.fireAt, quietUntil, now + 1000), mealPi(ctx, d.key))
        }

        // Eating-window reminders.
        am.cancel(infoPi(ctx, RC_INFO_OPEN, null, null))
        am.cancel(infoPi(ctx, RC_INFO_CLOSE, null, null))
        val last = engine.lastTaken(now)
        if (quiet) return   // no "OK to eat" / "stop eating" pings while he is out either
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

    /** Cancels a pending test alarm and silences it if it is ringing right now. */
    fun cancelTest(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_DOSE).putExtra(EXTRA_KEY, TEST_KEY)
        am.cancel(PendingIntent.getBroadcast(ctx, 103, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (AlarmService.ringingKey == TEST_KEY)
            ctx.startService(Intent(ctx, AlarmService::class.java).setAction(AlarmService.ACTION_STOP))
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
