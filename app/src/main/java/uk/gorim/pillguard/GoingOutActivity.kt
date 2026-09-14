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
 * Opening it while a window is already running offers the way back instead.
 */
class GoingOutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_going_out)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); render()
    }

    private fun render() {
        val store = Store.get(this)
        val yes = findViewById<Button>(R.id.btnOutYes)
        val no = findViewById<Button>(R.id.btnOutNo)
        val title = findViewById<TextView>(R.id.outTitle)
        val until = findViewById<TextView>(R.id.outUntil)
        val detail = findViewById<TextView>(R.id.outDetail)
        // Match the alarm screens: the white button's text picks up the screen colour.
        no.setTextColor(ContextCompat.getColor(this, R.color.out_text))

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
            no.text = "Leave it quiet"
            no.setOnClickListener { finish() }
            return
        }

        val hours = store.settings.goingOutHours
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
        detail.text = if (lines.isEmpty()) "Nothing is due in the next $hours hours anyway."
        else "In that time:\n" + lines.joinToString("\n")

        yes.setOnClickListener {
            val done = store.startGoingOut(hours)
            // A dose that was ringing as he left stops now.
            startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP))
            Alerts.onGoingOut(this, done)
            AlarmScheduler.reschedule(this)
            Ui.toast(this, "Quiet until ${TimeFmt.hm(done.until)}.")
            finish()
        }
        no.setOnClickListener { finish() }
    }
}
