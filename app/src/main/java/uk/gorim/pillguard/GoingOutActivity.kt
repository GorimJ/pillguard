package uk.gorim.pillguard

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * "I'm going out": one tap silences everything for a couple of hours, having first said in plain
 * words what it is about to silence. Doses inside the window are moved to the end of it rather than
 * dropped — going out should cost him an anxious pocket-buzz, not a dose.
 *
 * "Longer" adds an hour at a time: before confirming it stretches the window being offered, and
 * while he is already out it pushes the end back and takes the deferred doses with it.
 *
 * Opening it while a window is already running offers the way back instead.
 */
class GoingOutActivity : AppCompatActivity() {
    /** Hours being offered on this screen; "Longer" grows it before anything is committed. */
    private var hours = 0

    companion object {
        /** Long enough for an appointment and the journey either side of it. */
        const val MAX_HOURS = 12
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_going_out)
        hours = Store.get(this).settings.goingOutHours
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        hours = Store.get(this).settings.goingOutHours
        render()
    }

    private fun render() {
        val store = Store.get(this)
        val yes = findViewById<Button>(R.id.btnOutYes)
        val longer = findViewById<Button>(R.id.btnOutLonger)
        val no = findViewById<Button>(R.id.btnOutNo)
        val title = findViewById<TextView>(R.id.outTitle)
        val until = findViewById<TextView>(R.id.outUntil)
        val detail = findViewById<TextView>(R.id.outDetail)
        // Match the alarm screens: the white buttons' text picks up the screen colour.
        val alt = ContextCompat.getColor(this, R.color.out_text)
        no.setTextColor(alt); longer.setTextColor(alt)

        if (store.isQuiet) {
            title.text = "You are out"
            until.text = "Quiet until ${TimeFmt.hm(store.quietUntil)}"
            val moved = store.records.values
                .filter { it.shiftReason == "going out" && it.takenAt == 0L }
                .map { store.labelFor(it.key) }
            detail.text = if (moved.isEmpty()) "Nothing is waiting."
            else "Waiting for you: ${moved.joinToString()} pills."
            yes.text = "I'm home now"
            yes.setOnClickListener {
                store.endGoingOut()
                Alerts.onBackHome(this)
                Notifications.cancelQuiet(this)
                AlarmScheduler.reschedule(this)
                Ui.toast(this, "Alarms are back on.")
                finish()
            }
            // Still out and running late: one tap buys another hour, pills and all.
            longer.text = "Another hour — until ${TimeFmt.hm(store.quietUntil + 3_600_000L)}"
            longer.setOnClickListener {
                val plan = store.extendGoingOut(1)
                Alerts.onGoingOut(this, plan)
                AlarmScheduler.reschedule(this)
                Ui.toast(this, "Quiet until ${TimeFmt.hm(plan.until)}.")
                render()
            }
            no.text = "Leave it quiet"
            no.setOnClickListener { finish() }
            return
        }

        val plan = store.goingOutPreview(hours)
        title.text = "Going out?"
        until.text = "Quiet until ${TimeFmt.hm(plan.until)}"

        val lines = ArrayList<String>()
        plan.doses.forEach { (d, at) ->
            lines += "${d.label} pills ${TimeFmt.hm(d.effectiveMillis)} → ${TimeFmt.hm(at)}"
        }
        plan.meals.forEach { lines += "$it — skipped" }
        // An overdue dose is not moved and not hidden: it is still owed.
        plan.waiting.forEach { lines += "${it.label} pills (${TimeFmt.hm(it.effectiveMillis)}) still to take" }
        detail.text = (if (lines.isEmpty()) "Nothing is due in the next $hours hours anyway."
        else "In that time:\n" + lines.joinToString("\n")) + "\n\n($hours hours)"

        yes.text = "Yes, I'm going out"
        yes.setOnClickListener {
            val done = store.startGoingOut(hours)
            // A dose that was ringing as he left stops now.
            startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP))
            Alerts.onGoingOut(this, done)
            AlarmScheduler.reschedule(this)
            Ui.toast(this, "Quiet until ${TimeFmt.hm(done.until)}.")
            finish()
        }

        if (hours >= MAX_HOURS) {
            longer.isEnabled = false
            longer.alpha = 0.4f
            longer.text = "That's as long as it goes"
        } else {
            longer.isEnabled = true
            longer.alpha = 1f
            // Adding an hour re-reads the whole plan, so the list below always matches the button above.
            longer.text = "Longer — until ${TimeFmt.hm(plan.until + 3_600_000L)}"
            longer.setOnClickListener { hours += 1; render() }
        }

        no.text = "No, stay as it is"
        no.setOnClickListener { finish() }
    }
}
