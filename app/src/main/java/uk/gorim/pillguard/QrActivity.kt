package uk.gorim.pillguard

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.print.PrintHelper
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.io.File

class QrActivity : AppCompatActivity() {
    private lateinit var sheet: Bitmap
    /** Which container's code is on screen. Only relevant once the night pills have their own. */
    private var showingNight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr)
        title = getString(R.string.print_qr)
        val store = Store.get(this)

        val toggle = findViewById<Button>(R.id.btnWhichCode)
        if (store.hasSeparateNightQr) {
            toggle.visibility = android.view.View.VISIBLE
            toggle.setOnClickListener { showingNight = !showingNight; render() }
        }
        render()

        findViewById<Button>(R.id.btnPrint).setOnClickListener {
            PrintHelper(this).apply {
                scaleMode = PrintHelper.SCALE_MODE_FIT
                colorMode = PrintHelper.COLOR_MODE_MONOCHROME
            }.printBitmap("PillGuard QR codes", sheet)
        }
        findViewById<Button>(R.id.btnShare).setOnClickListener { share() }
    }

    private fun render() {
        val store = Store.get(this)
        val payload = if (showingNight) store.nightQrPayload else store.qrPayload
        val caption = if (showingNight) "PillGuard night pills" else "PillGuard medication code"

        val qr = BarcodeEncoder().encodeBitmap(
            payload, BarcodeFormat.QR_CODE, 800, 800,
            mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H)
        )
        findViewById<ImageView>(R.id.qrImage).setImageBitmap(qr)
        findViewById<TextView>(R.id.qrText).text =
            (if (store.hasSeparateNightQr) (if (showingNight) "NIGHT container\n" else "DAYTIME containers\n") else "") + payload
        findViewById<Button>(R.id.btnWhichCode).text =
            if (showingNight) "Show the daytime code" else "Show the night code"
        sheet = makeSheet(qr, caption)
    }

    /** A4-ish sheet with a 3x3 grid of codes so several containers / spares can be labelled from one print. */
    private fun makeSheet(qr: Bitmap, caption: String): Bitmap {
        val w = 2480; val h = 3508 // A4 @ 300dpi
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f; textAlign = Paint.Align.CENTER }
        val cell = 700; val gap = 90
        val startX = (w - (3 * cell + 2 * gap)) / 2
        val startY = 250
        val header = if (showingNight)
            "PillGuard — stick one code on the NIGHT medication container"
        else "PillGuard — stick one code on the bottom of each medication container"
        c.drawText(header, w / 2f, 150f, paint.apply { textSize = 56f })
        paint.textSize = 40f
        val scaled = Bitmap.createScaledBitmap(qr, cell, cell, true)
        for (r in 0 until 3) for (col in 0 until 3) {
            val x = startX + col * (cell + gap); val y = startY + r * (cell + gap + 80)
            c.drawBitmap(scaled, x.toFloat(), y.toFloat(), null)
            c.drawText(caption, x + cell / 2f, y + cell + 55f, paint)
        }
        return bmp
    }

    private fun share() {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val f = File(dir, if (showingNight) "pillguard-qr-night.png" else "pillguard-qr.png")
        f.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Share QR sheet"
            )
        )
    }
}
