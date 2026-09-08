package uk.gorim.pillguard

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract

/** Full-screen alarm UI shown over the lock screen. */
class AlarmActivity : AppCompatActivity() {
    private var key: String? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var countdown: Runnable? = null
    /** True while the confirm screen is still holding — Back is ignored during this. */
    private var confirmHeld = false
    private var quietTick: Runnable? = null

    companion object {
        /** Seconds the "yes" is held before it can be pressed. */
        const val DELAY_CONFIRM_HOLD_S = 3
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val store = Store.get(this)
        val k = key ?: return@registerForActivityResult
        when {
            result.contents == null -> Unit // cancelled; keep ringing
            store.matchesQr(result.contents) -> confirm(k, "scan")
            else -> Ui.toast(this, "That's not the medication QR code. Try again.")
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Ui.launchScan(scanLauncher) else Ui.toast(this, "Camera permission is needed to scan the QR code.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)

        findViewById<Button>(R.id.btnTaking).setOnClickListener {
            reprieve()
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                Ui.launchScan(scanLauncher)
            else cameraPermission.launch(android.Manifest.permission.CAMERA)
        }
        findViewById<Button>(R.id.btnGoing).setOnClickListener {
            startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_SNOOZE))
            // Stay on screen: he needs Scan in front of him when he gets back with the container.
            showQuietCountdown()
        }
        findViewById<android.widget.ImageButton>(R.id.btnDelay).setOnClickListener { confirmDelay() }
        findViewById<Button>(R.id.btnOverride).setOnClickListener {
            Ui.askPin(this, "Carer override — enter PIN") { key?.let { k -> confirm(k, "override") } }
        }
        bind(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    private fun bind(intent: Intent?) {
        val store = Store.get(this)
        key = intent?.getStringExtra(AlarmScheduler.EXTRA_KEY) ?: store.ringingKey
        val k = key
        if (k == AlarmScheduler.TEST_KEY) {
            findViewById<TextView>(R.id.alarmTime).text = TimeFmt.hm(System.currentTimeMillis())
            // The triangle stays: a test you cannot rehearse the delay on is not much of a test.
            findViewById<android.widget.ImageButton>(R.id.btnDelay).alpha = 1f
            return
        }
        val inst = k?.let { kk -> store.engine().window(System.currentTimeMillis()).firstOrNull { it.key == kk } }
        if (k == null || inst == null || inst.status != DoseStatus.PENDING) { finish(); return }
        findViewById<TextView>(R.id.alarmTime).text = TimeFmt.hm(inst.effectiveMillis)
        if (store.snoozeUntil > System.currentTimeMillis()) showQuietCountdown()
        // Fade the triangle once both delays are used, rather than hiding it — a control that
        // vanishes mid-alarm is more confusing than one that says no.
        findViewById<android.widget.ImageButton>(R.id.btnDelay).alpha = if (inst.canDelay) 1f else 0.35f
    }

    override fun onResume() {
        super.onResume()
        // If the dose was confirmed elsewhere (e.g. from the main screen) close this screen.
        val k = key ?: return
        // Nothing ringing (auto-snoozed, or confirmed elsewhere) → nothing to show.
        if (AlarmService.ringingKey == null) { finish(); return }
        if (k == AlarmScheduler.TEST_KEY) return
        val inst = Store.get(this).engine().window(System.currentTimeMillis()).firstOrNull { it.key == k }
        if (inst == null || inst.status != DoseStatus.PENDING) finish()
    }

    /**
     * While the alarm is quiet after "Get pill", that button becomes a live countdown and Scan
     * stays ready. When the quiet runs out the alarm restarts and the button comes back.
     */
    private fun showQuietCountdown() {
        val going = findViewById<Button>(R.id.btnGoing)
        quietTick?.let { handler.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                val left = Store.get(this@AlarmActivity).snoozeUntil - System.currentTimeMillis()
                if (left > 0) {
                    val total = (left / 1000).toInt()
                    going.isEnabled = false
                    going.alpha = 0.45f
                    going.text = String.format("Quiet %d:%02d", total / 60, total % 60)
                    handler.postDelayed(this, 500)
                } else {
                    going.isEnabled = true
                    going.alpha = 1f
                    going.text = "Get pill"
                    quietTick = null
                }
            }
        }
        quietTick = tick
        handler.post(tick)
    }

    /** Silences the alarm for a minute so he can act without the noise (appointment, cinema). */
    private fun reprieve() {
        startService(
            Intent(this, AlarmService::class.java)
                .setAction(AlarmService.ACTION_PAUSE)
                .putExtra(AlarmService.EXTRA_PAUSE_SECONDS, AlarmService.DEFAULT_PAUSE_SECONDS)
        )
    }

    /** Red triangle: an hour's delay, twice at most, and the carer is told loudly each time. */
    private fun confirmDelay() {
        val k = key ?: return
        val store = Store.get(this)
        val now = System.currentTimeMillis()
        val isTest = k == AlarmScheduler.TEST_KEY
        val inst = if (isTest) null else store.engine().window(now).firstOrNull { it.key == k } ?: return

        reprieve()
        val panel = findViewById<android.widget.LinearLayout>(R.id.delayConfirm)
        val yes = findViewById<Button>(R.id.btnDelayYes)
        val no = findViewById<Button>(R.id.btnDelayNo)

        if (isTest) {
            findViewById<TextView>(R.id.delayNewTime).text = "This is only a test"
            findViewById<TextView>(R.id.delayCount).text =
                "On a real alarm this puts the pills off an hour and tells your carer. Nothing happens now."
            yes.visibility = android.view.View.VISIBLE
            no.text = "No, keep it"
            no.setOnClickListener { hideDelayPanel() }
            panel.visibility = android.view.View.VISIBLE
            holdThenEnable(yes) {
                hideDelayPanel()
                AlarmScheduler.cancelTest(this)
                Ui.toast(this, "Test alarm cleared — the delay works.")
                finish()
            }
            return
        }

        if (!inst!!.canDelay) {
            findViewById<TextView>(R.id.delayNewTime).text = "Already put off twice"
            findViewById<TextView>(R.id.delayCount).text = "Please take your pills, or ask your carer."
            yes.visibility = android.view.View.GONE
            no.text = "Back to the alarm"
            no.setOnClickListener { hideDelayPanel() }
            confirmHeld = false
            panel.visibility = android.view.View.VISIBLE
            return
        }

        val newTime = now + 3_600_000L
        findViewById<TextView>(R.id.delayNewTime).text = "Alarm comes back at ${TimeFmt.hm(newTime)}"
        findViewById<TextView>(R.id.delayCount).text =
            "Delay ${inst.delays + 1} of ${DoseRecord.MAX_DELAYS}. Your carer will be told."
        yes.visibility = android.view.View.VISIBLE
        no.text = "No, keep it"
        no.setOnClickListener { hideDelayPanel() }
        panel.visibility = android.view.View.VISIBLE

        holdThenEnable(yes) { hideDelayPanel(); doDelay(k) }
    }

    /** Holds a button disabled for a few seconds, counting down on its own label, then arms it. */
    private fun holdThenEnable(yes: Button, onPress: () -> Unit) {
        yes.isEnabled = false
        yes.alpha = 0.4f
        confirmHeld = true
        yes.setOnClickListener { onPress() }
        countdown?.let { handler.removeCallbacks(it) }
        var left = DELAY_CONFIRM_HOLD_S
        val tick = object : Runnable {
            override fun run() {
                if (left > 0) {
                    yes.text = "Yes — wait $left"
                    left--
                    handler.postDelayed(this, 1_000)
                } else {
                    yes.text = "Yes, put it off"
                    yes.isEnabled = true
                    yes.alpha = 1f
                    confirmHeld = false
                }
            }
        }
        countdown = tick
        handler.post(tick)
    }

    private fun hideDelayPanel() {
        countdown?.let { handler.removeCallbacks(it) }
        countdown = null
        confirmHeld = false
        findViewById<android.widget.LinearLayout>(R.id.delayConfirm).visibility = android.view.View.GONE
    }

    private fun doDelay(k: String) {
        val store = Store.get(this)
        val before = store.engine().window(System.currentTimeMillis()).firstOrNull { it.key == k }
        val newTime = store.delayDose(k) ?: return
        val clash = before?.let { store.engine().crowdsFollowing(it, newTime, System.currentTimeMillis()) }
        Alerts.onDelayed(this, k, newTime, (before?.delays ?: 0) + 1, clash)
        startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP))
        AlarmScheduler.reschedule(this)
        Ui.toast(this, "Alarm moved to ${TimeFmt.hm(newTime)}")
        finish()
    }

    private fun confirm(k: String, method: String) {
        val store = Store.get(this)
        if (k == AlarmScheduler.TEST_KEY) {
            startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP))
            Ui.toast(this, "Test alarm cleared (${if (method == "scan") "QR scanned" else "PIN"}).")
            finish(); return
        }
        store.markTaken(k, method)
        Alerts.onTaken(this, k, method)
        startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP))
        AlarmScheduler.reschedule(this)
        val s = store.settings
        Ui.toast(this, "Medication taken. Don't eat until ${TimeFmt.hm(System.currentTimeMillis() + s.eatAfterMin * 60_000L)}.")
        finish()
    }

    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        val panel = findViewById<android.widget.LinearLayout>(R.id.delayConfirm)
        if (panel.visibility == android.view.View.VISIBLE) {
            // Back is deliberately inert while the choice is still held, so a panicked jab at it
            // does not bounce him straight back to a ringing alarm.
            if (!confirmHeld) hideDelayPanel()
            return
        }
        // Back does not dismiss the alarm; the alarm keeps ringing and the notification stays.
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        countdown?.let { handler.removeCallbacks(it) }
        quietTick?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }
}
