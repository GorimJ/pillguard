package uk.gorim.pillguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        when {
            action == AlarmScheduler.ACTION_DOSE -> {
                val key = intent.getStringExtra(AlarmScheduler.EXTRA_KEY) ?: return
                val store = Store.get(ctx)
                if (key != AlarmScheduler.TEST_KEY) {
                    val inst = store.engine().window(System.currentTimeMillis()).firstOrNull { it.key == key }
                    if (inst == null || inst.status != DoseStatus.PENDING) {
                        AlarmScheduler.reschedule(ctx); return
                    }
                    store.ringingKey = key
                    store.snoozeUntil = 0
                }
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, AlarmService::class.java).setAction(AlarmService.ACTION_START).putExtra(AlarmScheduler.EXTRA_KEY, key)
                )
            }
            action == AlarmScheduler.ACTION_MEAL -> {
                val key = intent.getStringExtra(AlarmScheduler.EXTRA_KEY) ?: return
                val store = Store.get(ctx)
                val now = System.currentTimeMillis()
                val logic = store.mealLogic()
                val due = logic.next(now - 1)   // what should be ringing now
                val ring = store.settings.mealsEnabled && due != null && due.key == key && logic.shouldRing(now)
                if (ring) {
                    val d = due!!
                    val st = store.engine().eatStatus(now)
                    val late = ((now - d.mealMillis) / 60_000).toInt()
                    val text = (if (late >= 5) "${d.label} was at ${TimeFmt.hm(d.mealMillis)} — $late min ago. " else "") +
                        st.detail + "\nTap \"Eating now\" once you start, or \"Not today\" to stop these until tomorrow."
                    Notifications.meal(ctx, key, d.label, text)
                    runCatching { ctx.startActivity(Notifications.mealActivityIntent(ctx, key, d.label)) }
                    store.log("${d.label} reminder rang")
                    runCatching {
                        val v = if (android.os.Build.VERSION.SDK_INT >= 31)
                            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
                        else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                        v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
                    }
                    // Keep the receiver alive while the ~7 s clip plays (receivers get ~10 s in the background).
                    val pending = goAsync()
                    var done = false
                    val finish = { if (!done) { done = true; runCatching { pending.finish() } } }
                    MealSound.play(ctx) { finish() }
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, 9_000)
                }
                // Arm the next repeat (or the next meal) regardless.
                AlarmScheduler.reschedule(ctx)
            }
            action == AlarmScheduler.ACTION_MEAL_ATE -> {
                val store = Store.get(ctx)
                Notifications.cancelMeal(ctx)
                val now = System.currentTimeMillis()
                val shift = store.recordMeal(now)
                if (shift != null) Notifications.info(
                    ctx, "${shift.first.label} pill moved to ${TimeFmt.hm(shift.second)}",
                    "Because you're eating now, the next pill is pushed back to keep the ${store.settings.eatBeforeMin} min gap."
                )
                AlarmScheduler.reschedule(ctx)
            }
            action == AlarmScheduler.ACTION_MEAL_STOP -> {
                val key = intent.getStringExtra(AlarmScheduler.EXTRA_KEY) ?: return
                Notifications.cancelMeal(ctx)
                Store.get(ctx).stopMeal(key)
                AlarmScheduler.reschedule(ctx)
            }
            action == AlarmScheduler.ACTION_ALERT -> {
                val key = intent.getStringExtra(AlarmScheduler.EXTRA_KEY) ?: return
                val pending = goAsync()
                Alerts.onUnconfirmedCheck(ctx, key)
                // Give the network thread a moment before the receiver is torn down.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ pending.finish() }, 8_000)
            }
            action.startsWith(AlarmScheduler.ACTION_INFO) -> {
                val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: return
                val text = intent.getStringExtra(AlarmScheduler.EXTRA_TEXT) ?: ""
                // Only speak if still true (state may have changed since it was armed).
                val kind = Store.get(ctx).engine().eatStatus(System.currentTimeMillis()).kind
                val relevant = (title.startsWith("OK") && kind == EatKind.OK) || (title.startsWith("Stop") && kind == EatKind.TOO_CLOSE_TO_NEXT)
                if (relevant) Notifications.info(ctx, title, text)
            }
        }
    }
}
