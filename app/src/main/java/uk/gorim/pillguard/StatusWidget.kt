package uk.gorim.pillguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews

/**
 * Home-screen widget: eating status (colour-coded) and a live countdown to the next pill.
 * The countdown is a Chronometer, which ticks on its own without waking the app; the text
 * and colour are refreshed whenever app state changes and at the next known transition.
 */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        render(ctx, mgr, ids)
        scheduleNextRefresh(ctx)
    }

    override fun onEnabled(ctx: Context) { scheduleNextRefresh(ctx) }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) updateAll(ctx)
    }

    companion object {
        const val ACTION_REFRESH = "uk.gorim.pillguard.WIDGET_REFRESH"
        private const val RC_REFRESH = 106

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java))
            if (ids.isEmpty()) return
            render(ctx, mgr, ids)
            scheduleNextRefresh(ctx)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val store = Store.get(ctx)
            val now = System.currentTimeMillis()
            val e = store.engine()
            val st = e.eatStatus(now)
            val due = e.dueDose(now)
            val next = e.nextUpcoming(now)

            val bg = when (st.kind) {
                EatKind.OK -> R.drawable.widget_bg_green
                EatKind.DOSE_DUE -> R.drawable.widget_bg_amber
                else -> R.drawable.widget_bg_red
            }
            val headline = when (st.kind) {
                EatKind.OK -> st.headline
                EatKind.WAIT_AFTER_DOSE -> "Don't eat until ${TimeFmt.hm(st.untilMillis)}"
                EatKind.TOO_CLOSE_TO_NEXT -> "Don't eat — pill soon"
                EatKind.DOSE_DUE -> "Take your pills now"
            }
            val detail = when (st.kind) {
                EatKind.OK -> if (next != null) "Then no food until after the ${next.label.lowercase()} pill." else "No more pills today."
                EatKind.WAIT_AFTER_DOSE -> "${store.settings.eatAfterMin} min after your pill."
                EatKind.TOO_CLOSE_TO_NEXT -> "Nothing to eat in the ${store.settings.eatBeforeMin} min before a pill."
                EatKind.DOSE_DUE -> "Due ${TimeFmt.hm(due!!.effectiveMillis)} — scan the pill container."
            }

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            for (id in ids) {
                val v = RemoteViews(ctx.packageName, R.layout.widget_status)
                v.setInt(R.id.widgetRoot, "setBackgroundResource", bg)
                v.setTextViewText(R.id.wHeadline, headline)
                v.setTextViewText(R.id.wDetail, detail)
                when {
                    due != null -> {
                        v.setTextViewText(R.id.wNextLabel, "${due.label} pill overdue")
                        v.setChronometerCountDown(R.id.wCountdown, false)
                        v.setChronometer(R.id.wCountdown, SystemClock.elapsedRealtime() - (now - due.effectiveMillis), null, true)
                    }
                    next != null -> {
                        v.setTextViewText(R.id.wNextLabel, "Next pill ${TimeFmt.hm(next.effectiveMillis)}")
                        v.setChronometerCountDown(R.id.wCountdown, true)
                        v.setChronometer(R.id.wCountdown, SystemClock.elapsedRealtime() + (next.effectiveMillis - now), null, true)
                    }
                    else -> {
                        v.setTextViewText(R.id.wNextLabel, "No pill scheduled")
                        v.setChronometer(R.id.wCountdown, SystemClock.elapsedRealtime(), "", false)
                        v.setTextViewText(R.id.wCountdown, "")
                    }
                }
                v.setOnClickPendingIntent(R.id.widgetRoot, open)
                mgr.updateAppWidget(id, v)
            }
        }

        /** Arm a refresh at the next moment the status text/colour will change. */
        private fun scheduleNextRefresh(ctx: Context) {
            val store = Store.get(ctx)
            val now = System.currentTimeMillis()
            val e = store.engine()
            val s = store.settings
            val candidates = ArrayList<Long>()
            e.lastTaken(now)?.let { candidates += it.takenAt + s.eatAfterMin * 60_000L }
            e.nextUpcoming(now)?.let {
                candidates += it.effectiveMillis - s.eatBeforeMin * 60_000L
                candidates += it.effectiveMillis
            }
            // Fallback so a stale widget never sits there for hours (e.g. after a day rollover).
            candidates += now + 30 * 60_000L
            val at = candidates.filter { it > now }.minOrNull() ?: return
            val pi = PendingIntent.getBroadcast(
                ctx, RC_REFRESH, Intent(ctx, StatusWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = ctx.getSystemService(AlarmManager::class.java)
            am.cancel(pi)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at + 1000, pi)
        }
    }
}
