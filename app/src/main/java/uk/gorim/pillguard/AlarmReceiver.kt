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
