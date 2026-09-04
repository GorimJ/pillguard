package uk.gorim.pillguard

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import java.time.ZonedDateTime

class MainActivity : AppCompatActivity() {
    companion object { const val EARLY_SCAN_MIN = 60 }
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 30_000) }
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) return@registerForActivityResult
        val store = Store.get(this)
        if (!store.matchesQr(result.contents)) { Ui.toast(this, "That's not the medication QR code."); return@registerForActivityResult }
        if (AlarmService.ringingKey == AlarmScheduler.TEST_KEY) {
            AlarmScheduler.cancelTest(this)
            Ui.toast(this, "Test alarm cleared."); return@registerForActivityResult
        }
        val now = System.currentTimeMillis()
        val e = store.engine()
        val due = e.dueDose(now)
        val next = e.nextUpcoming(now)
        // A manual scan only counts as taking a dose if one is due, or the next one is within 60 minutes.
        val target = due ?: next?.takeIf { it.effectiveMillis - now <= EARLY_SCAN_MIN * 60_000L }
        if (target == null) {
            store.log("QR scanned — no dose due (next ${next?.let { "${it.label.lowercase()} at ${TimeFmt.hm(it.effectiveMillis)}" } ?: "none"}); logged only")
            Ui.toast(this, "Scan logged. No pill is due yet" + (next?.let { " — next is ${it.label.lowercase()} at ${TimeFmt.hm(it.effectiveMillis)}." } ?: "."))
            render(); return@registerForActivityResult
        }
        store.markTaken(target.key, "scan", now)
        Alerts.onTaken(this, target.key, "scan")
        startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP))
        AlarmScheduler.reschedule(this)
        Ui.toast(this, "${target.label} dose recorded. Don't eat until ${TimeFmt.hm(now + store.settings.eatAfterMin * 60_000L)}.")
        render()
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Ui.launchScan(scanLauncher) else Ui.toast(this, "Camera permission is needed to scan the QR code.")
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Notifications.ensureChannels(this)
        Store.get(this).migrate()

        findViewById<Button>(R.id.btnAte).setOnClickListener { askMealTime() }
        findViewById<Button>(R.id.btnScanNow).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                Ui.launchScan(scanLauncher) else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        findViewById<Button>(R.id.btnHistory).setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        findViewById<Button>(R.id.btnQr).setOnClickListener { startActivity(Intent(this, QrActivity::class.java)) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            val open = { startActivity(Intent(this, SettingsActivity::class.java)) }
            if (Store.get(this).settings.pin.isEmpty()) open() else Ui.askPin(this, "Settings — enter carer PIN", open)
        }
        findViewById<Button>(R.id.btnSetup).setOnClickListener { fixNextSetupItem() }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        AlarmScheduler.reschedule(this)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ---------- rendering ----------

    private fun render() {
        val store = Store.get(this)
        val now = System.currentTimeMillis()
        val e = store.engine()
        val st = e.eatStatus(now)

        val card = findViewById<LinearLayout>(R.id.eatCard)
        val head = findViewById<TextView>(R.id.eatHeadline)
        val detail = findViewById<TextView>(R.id.eatDetail)
        val (bg, fg) = when (st.kind) {
            EatKind.OK -> R.color.ok_green_bg to R.color.ok_green
            EatKind.DOSE_DUE -> R.color.warn_amber_bg to R.color.warn_amber
            else -> R.color.no_red_bg to R.color.no_red
        }
        card.setBackgroundColor(ContextCompat.getColor(this, bg))
        head.setTextColor(ContextCompat.getColor(this, fg))
        head.text = st.headline
        detail.text = st.detail

        val due = e.dueDose(now)
        val next = e.nextUpcoming(now)
        findViewById<TextView>(R.id.nextDose).text = when {
            due != null -> "⚠ ${due.label} dose is due now (${TimeFmt.hm(due.effectiveMillis)})"
            next != null -> "Next dose: ${next.label.lowercase()} at ${TimeFmt.hm(next.effectiveMillis)}" +
                (if (next.isShifted) " (moved from ${TimeFmt.hm(next.scheduledMillis)})" else "")
            else -> "No more doses scheduled"
        }

        val list = findViewById<LinearLayout>(R.id.doseList)
        list.removeAllViews()
        for (d in e.todays(now)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 12); gravity = Gravity.CENTER_VERTICAL }
            val left = TextView(this).apply {
                textSize = 19f
                text = "${TimeFmt.hm(d.effectiveMillis)}  ${d.label}" + if (d.isShifted) "  (was ${TimeFmt.hm(d.scheduledMillis)})" else ""
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val right = TextView(this).apply {
                textSize = 19f
                when (d.status) {
                    DoseStatus.TAKEN -> { text = "✓ ${TimeFmt.hm(d.takenAt)}"; setTextColor(ContextCompat.getColor(context, R.color.ok_green)) }
                    DoseStatus.MISSED -> { text = "missed"; setTextColor(ContextCompat.getColor(context, R.color.no_red)) }
                    DoseStatus.PENDING -> { text = if (d.effectiveMillis <= now) "DUE" else "—"; setTextColor(ContextCompat.getColor(context, if (d.effectiveMillis <= now) R.color.warn_amber else R.color.black)) }
                }
            }
            row.addView(left); row.addView(right)
            list.addView(row)
        }

        renderSetup(store)
    }

    // ---------- meals ----------

    private fun askMealTime() {
        val now = ZonedDateTime.now()
        TimePickerDialog(this, { _, h, m ->
            var at = now.withHour(h).withMinute(m).withSecond(0).withNano(0)
            if (at.isAfter(now)) at = at.minusDays(1) // "I ate at 23:50" said at 00:10 → yesterday
            confirmMeal(at.toInstant().toEpochMilli())
        }, now.hour, now.minute, true).apply {
            setTitle("When did you eat?")
            show()
        }
    }

    private fun confirmMeal(ateAt: Long) {
        val store = Store.get(this)
        val now = System.currentTimeMillis()
        val e = store.engine()
        val shift = e.mealShift(ateAt, now)
        val msg = StringBuilder("Ate at ${TimeFmt.hm(ateAt)}.\n\n")
        if (shift == null) {
            msg.append("No dose needs to move.")
        } else {
            val (dose, newTime) = shift
            msg.append("${dose.label} dose will move from ${TimeFmt.hm(dose.effectiveMillis)} to ${TimeFmt.hm(newTime)}.")
            e.crowdsFollowing(dose, newTime, now)?.let { f ->
                msg.append("\n\n⚠ That is only ${(f.effectiveMillis - newTime) / 60_000} min before the ${f.label.lowercase()} dose at ${TimeFmt.hm(f.effectiveMillis)}. Check with the care team.")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Confirm")
            .setMessage(msg.toString())
            .setPositiveButton("Yes") { _, _ ->
                store.recordMeal(ateAt)
                Notifications.cancelMeal(this)
                AlarmScheduler.reschedule(this)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- setup / permissions ----------

    private data class SetupItem(val text: String, val fix: () -> Unit)

    private fun setupItems(store: Store): List<SetupItem> {
        val items = ArrayList<SetupItem>()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) items += SetupItem("Allow notifications") { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        else if (!nm.areNotificationsEnabled())
            items += SetupItem("Notifications are turned off for this app") { openAppNotificationSettings() }

        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
            items += SetupItem("Allow alarms & reminders (exact timing)") {
                startActivity(Intent(SysSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:$packageName")))
            }

        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent())
            items += SetupItem("Allow full-screen alarms on the lock screen") {
                startActivity(Intent(SysSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).setData(Uri.parse("package:$packageName")))
            }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName))
            items += SetupItem("Turn off battery optimisation so alarms are never delayed") {
                startActivity(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName")))
            }

        if (store.settings.pin.isEmpty())
            items += SetupItem("Set a carer PIN (Settings)") { startActivity(Intent(this, SettingsActivity::class.java)) }
        return items
    }

    private fun renderSetup(store: Store) {
        val items = setupItems(store)
        val banner = findViewById<LinearLayout>(R.id.setupBanner)
        if (items.isEmpty()) { banner.visibility = View.GONE; return }
        banner.visibility = View.VISIBLE
        findViewById<TextView>(R.id.setupText).text = "Setup needed:\n" + items.joinToString("\n") { "• ${it.text}" }
    }

    private fun fixNextSetupItem() {
        val items = setupItems(Store.get(this))
        items.firstOrNull()?.let { runCatching { it.fix() }.onFailure { Ui.toast(this, "Couldn't open that setting: ${it.message}") } }
    }

    private fun openAppNotificationSettings() {
        startActivity(Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SysSettings.EXTRA_APP_PACKAGE, packageName))
    }
}
