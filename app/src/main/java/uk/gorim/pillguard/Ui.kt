package uk.gorim.pillguard

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import com.journeyapps.barcodescanner.ScanOptions

object Ui {
    fun scanOptions(prompt: String = "Point the camera at the QR code on the pill container"): ScanOptions =
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(prompt)
            .setBeepEnabled(true)
            .setOrientationLocked(true)
            .setCaptureActivity(LockedCaptureActivity::class.java)

    fun launchScan(launcher: ActivityResultLauncher<ScanOptions>) = launcher.launch(scanOptions())

    /** Ask for the carer PIN. Calls [onOk] only if it matches. */
    fun askPin(activity: Activity, title: String, onOk: () -> Unit) {
        val pin = Store.get(activity).settings.pin
        if (pin.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("No carer PIN set")
                .setMessage("Set a carer PIN in Settings first.")
                .setPositiveButton("OK", null).show()
            return
        }
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
            textSize = 24f
        }
        val box = FrameLayout(activity).apply {
            val p = (24 * activity.resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0); addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Confirm") { _, _ ->
                if (input.text.toString() == pin) onOk()
                else Toast.makeText(activity, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun toast(activity: Activity, msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
