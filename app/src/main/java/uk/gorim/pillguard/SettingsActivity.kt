package uk.gorim.pillguard

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {
    private val doses = ArrayList<DoseTime>()
    private lateinit var store: Store

    /** Adopt an already-printed code so a reinstall or new phone doesn't need new labels. */
    private val adoptLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents?.trim() ?: return@registerForActivityResult
        if (!text.startsWith(Store.QR_PREFIX) || text.length <= Store.QR_PREFIX.length) {
            Ui.toast(this, "That isn't a PillGuard code."); return@registerForActivityResult
        }
        store.settings = store.settings.copy(qrSecret = text.removePrefix(Store.QR_PREFIX))
        store.log("Adopted printed QR code")
        findViewById<TextView>(R.id.qrInfo).text = "Current code: ${store.qrPayload}\nThis matches the printed labels."
        Ui.toast(this, "Printed code adopted — the alarm will accept it.")
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) adoptLauncher.launch(Ui.scanOptions("Scan the printed PillGuard code to keep using it"))
        else Ui.toast(this, "Camera permission is needed to scan.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings)
        store = Store.get(this)
        val s = store.settings
        doses.addAll(s.doseTimes)

        num(R.id.eatAfter).setText(s.eatAfterMin.toString())
        num(R.id.eatBefore).setText(s.eatBeforeMin.toString())
        num(R.id.snooze).setText(s.snoozeMin.toString())
        num(R.id.ringTimeout).setText(s.ringTimeoutMin.toString())
        findViewById<TextView>(R.id.qrInfo).text = "Current code: ${store.qrPayload}\nPrint it from the QR code screen and stick it on the bottom of the pill container."

        findViewById<Button>(R.id.btnAddDose).setOnClickListener { editDose(null) }
        findViewById<Button>(R.id.btnTestAlarm).setOnClickListener {
            AlarmScheduler.scheduleTest(this, 15_000)
            Ui.toast(this, "Test alarm in 15 seconds. Lock the phone now.")
        }
        findViewById<Button>(R.id.btnNewQr).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("New QR code?")
                .setMessage("The printed code on the container will stop working until you print and attach the new one.")
                .setPositiveButton("Generate") { _, _ ->
                    store.settings = store.settings.copy(qrSecret = Store.newSecret())
                    store.log("QR code regenerated")
                    findViewById<TextView>(R.id.qrInfo).text = "Current code: ${store.qrPayload}\nPrint it from the QR code screen."
                }
                .setNegativeButton("Cancel", null).show()
        }
        findViewById<Button>(R.id.btnAdoptQr).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                adoptLauncher.launch(Ui.scanOptions("Scan the printed PillGuard code to keep using it"))
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }

        // Carer alerts
        if (s.alertTopic.isEmpty()) store.settings = s.copy(alertTopic = Alerts.newTopic())
        findViewById<MaterialSwitch>(R.id.alertsEnabled).isChecked = s.alertsEnabled
        num(R.id.alertAfter).setText(s.alertAfterMin.toString())
        renderTopic()
        findViewById<Button>(R.id.btnShareTopic).setOnClickListener {
            val url = Alerts.topicUrl(store.settings.alertTopic)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "PillGuard alerts")
                        .putExtra(Intent.EXTRA_TEXT, "Install the ntfy app, then open this link (or subscribe to the topic) to get PillGuard alerts: $url"),
                    "Share alert topic"
                )
            )
        }
        findViewById<Button>(R.id.btnTestAlert).setOnClickListener {
            Alerts.send(this, "PillGuard test", "Alerts are working.", 3) { ok ->
                runOnUiThread { Ui.toast(this, if (ok) "Test alert sent." else "Sending failed — check the phone's internet connection.") }
            }
        }
        renderDoses()
    }

    private fun renderTopic() {
        findViewById<TextView>(R.id.alertTopic).text = "Topic: ${store.settings.alertTopic}\n${Alerts.topicUrl(store.settings.alertTopic)}"
    }

    private fun num(id: Int) = findViewById<EditText>(id)

    private fun renderDoses() {
        val box = findViewById<LinearLayout>(R.id.doseTimes)
        box.removeAllViews()
        doses.sortBy { it.minuteOfDay }
        doses.forEachIndexed { i, d ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val t = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "${TimeFmt.minuteOfDay(d.minuteOfDay)}   ${d.label}"
                isAllCaps = false; textSize = 18f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { editDose(i) }
            }
            val x = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = "Remove"; isAllCaps = false
                setOnClickListener { doses.removeAt(i); renderDoses() }
            }
            row.addView(t); row.addView(x)
            box.addView(row)
        }
    }

    private fun editDose(index: Int?) {
        val existing = index?.let { doses[it] }
        val nameBox = EditText(this).apply { hint = "Label, e.g. Morning"; setText(existing?.label ?: ""); textSize = 18f }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(nameBox) }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New dose" else "Edit dose")
            .setView(wrap)
            .setPositiveButton("Pick time") { _, _ ->
                val label = nameBox.text.toString().ifBlank { "Dose" }
                val init = existing?.minuteOfDay ?: (12 * 60)
                TimePickerDialog(this, { _, h, m ->
                    val dt = DoseTime(h * 60 + m, label)
                    if (index == null) doses.add(dt) else doses[index] = dt
                    renderDoses()
                }, init / 60, init % 60, true).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save() {
        if (doses.isEmpty()) { Ui.toast(this, "Add at least one dose time."); return }
        val s = store.settings
        val pinText = num(R.id.pin).text.toString()
        if (pinText.isNotEmpty() && (pinText.length < 4 || pinText.length > 8)) { Ui.toast(this, "PIN must be 4–8 digits."); return }
        val next = s.copy(
            doseTimes = doses.sortedBy { it.minuteOfDay },
            eatAfterMin = num(R.id.eatAfter).text.toString().toIntOrNull()?.coerceIn(0, 240) ?: s.eatAfterMin,
            eatBeforeMin = num(R.id.eatBefore).text.toString().toIntOrNull()?.coerceIn(0, 240) ?: s.eatBeforeMin,
            snoozeMin = num(R.id.snooze).text.toString().toIntOrNull()?.coerceIn(1, 60) ?: s.snoozeMin,
            ringTimeoutMin = num(R.id.ringTimeout).text.toString().toIntOrNull()?.coerceIn(1, 30) ?: s.ringTimeoutMin,
            pin = if (pinText.isEmpty()) s.pin else pinText,
            alertsEnabled = findViewById<MaterialSwitch>(R.id.alertsEnabled).isChecked,
            alertAfterMin = num(R.id.alertAfter).text.toString().toIntOrNull()?.coerceIn(1, 240) ?: s.alertAfterMin,
        )
        store.settings = next
        store.log("Settings changed")
        AlarmScheduler.reschedule(this)
        Ui.toast(this, "Saved.")
        finish()
    }
}
