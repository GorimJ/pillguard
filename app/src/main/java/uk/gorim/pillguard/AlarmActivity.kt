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
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                Ui.launchScan(scanLauncher)
            else cameraPermission.launch(android.Manifest.permission.CAMERA)
        }
        findViewById<Button>(R.id.btnGoing).setOnClickListener {
            startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_SNOOZE))
            Ui.toast(this, "OK — it will ring again in ${Store.get(this).settings.reRingMin} min unless you scan the container.")
            finish()
        }
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
        findViewById<TextView>(R.id.alarmSub).text =
            "The alarm will keep coming back every ${store.settings.reRingMin} minutes until you scan the code on the pill container."
        if (k == AlarmScheduler.TEST_KEY) {
            findViewById<TextView>(R.id.alarmTitle).text = "TEST alarm"
            findViewById<TextView>(R.id.alarmTime).text = TimeFmt.hm(System.currentTimeMillis())
            return
        }
        val inst = k?.let { kk -> store.engine().window(System.currentTimeMillis()).firstOrNull { it.key == kk } }
        if (k == null || inst == null || inst.status != DoseStatus.PENDING) { finish(); return }
        findViewById<TextView>(R.id.alarmTitle).text = "Go and get your ${inst.label.lowercase()} pills"
        findViewById<TextView>(R.id.alarmTime).text = TimeFmt.hm(inst.effectiveMillis)
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
        // Back does not dismiss the alarm; the alarm keeps ringing and the notification stays.
        moveTaskToBack(true)
    }
}
