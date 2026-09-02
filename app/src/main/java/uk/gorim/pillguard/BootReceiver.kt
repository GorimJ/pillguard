package uk.gorim.pillguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms alarms after reboot, app update, or clock/timezone changes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // Snoozes don't survive a reboot: if a dose is due, it should ring again straight away.
        Store.get(ctx).snoozeUntil = 0
        AlarmScheduler.reschedule(ctx)
    }
}
