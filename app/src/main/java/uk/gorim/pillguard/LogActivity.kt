package uk.gorim.pillguard

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Diary view: day-by-day summary plus share buttons; raw event log underneath. */
class LogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.history)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Share diary (30 days)"; isAllCaps = false
            setOnClickListener { Diary.share(this@LogActivity, 30, asCsv = false) }
        })
        buttons.addView(Button(this).apply {
            text = "Share CSV (90 days)"; isAllCaps = false
            (layoutParams as? LinearLayout.LayoutParams)?.marginStart = pad / 2
            setOnClickListener { Diary.share(this@LogActivity, 90, asCsv = true) }
        })
        root.addView(buttons)

        root.addView(TextView(this).apply {
            text = Diary.text(this@LogActivity, 14)
            textSize = 15f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        })

        root.addView(TextView(this).apply {
            text = "\nFull event log"
            textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD)
        })
        val entries = Store.get(this).log.asReversed().take(300).joinToString("\n") { "${TimeFmt.dayHm(it.atMillis)}  —  ${it.text}" }
        root.addView(TextView(this).apply { text = entries.ifEmpty { "Nothing yet." }; textSize = 14f })

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
