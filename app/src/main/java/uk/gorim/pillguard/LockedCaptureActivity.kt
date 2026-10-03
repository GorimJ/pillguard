package uk.gorim.pillguard

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.ImageButton
import com.journeyapps.barcodescanner.CaptureActivity

/** zxing's scanner, allowed to show over the lock screen so the alarm can be cleared without unlocking. */
class LockedCaptureActivity : CaptureActivity() {
    private val main = Handler(Looper.getMainLooper())

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
        findViewById<ImageButton>(R.id.btnScanClose)?.setOnClickListener { cancel() }
    }

    /**
     * If the camera has been open long enough for the alarm to start ringing again — he is
     * struggling with the code, or has put the phone down mid-scan — close it. An alarm sounding
     * behind a viewfinder looks to him like a screen that has stopped working; the alarm screen,
     * with its own buttons, is where a ringing alarm belongs.
     */
    override fun onStart() {
        super.onStart()
        AlarmService.onRingingResumed = { main.post { if (!isFinishing) cancel() } }
    }

    override fun onStop() {
        AlarmService.onRingingResumed = null
        super.onStop()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }
}
