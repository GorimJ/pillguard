package uk.gorim.pillguard

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageButton
import com.journeyapps.barcodescanner.CaptureActivity

/** zxing's scanner, allowed to show over the lock screen so the alarm can be cleared without unlocking. */
class LockedCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Opening the camera by mistake should not be a trap: one big target closes it and puts
        // the screen he came from — usually the ringing alarm — back in front of him.
        findViewById<ImageButton>(R.id.btnScanClose)?.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }
}
