package uk.gorim.pillguard

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Full-screen meal reminder shown over the lock screen: one big "eating now" button, one small "later". */
class MealActivity : AppCompatActivity() {
    private var key: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_meal)
        bind(intent)

        findViewById<Button>(R.id.btnEating).setOnClickListener {
            sendBroadcast(Intent(this, AlarmReceiver::class.java).setAction(AlarmScheduler.ACTION_MEAL_ATE).putExtra(AlarmScheduler.EXTRA_KEY, key))
            finish()
        }
        findViewById<Button>(R.id.btnLater).setOnClickListener {
            Notifications.cancelMeal(this)
            Store.get(this).log("Meal reminder: remind me later")
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); bind(intent)
    }

    private fun bind(intent: Intent?) {
        key = intent?.getStringExtra(AlarmScheduler.EXTRA_KEY)
        val label = intent?.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "Meal"
        findViewById<TextView>(R.id.mealTitle).text = label
        val until = Store.get(this).engine().eatStatus(System.currentTimeMillis()).let {
            if (it.kind == EatKind.OK && it.untilMillis > 0) "Eat by ${TimeFmt.hm(it.untilMillis)}" else "Time to eat"
        }
        findViewById<TextView>(R.id.mealSub).text = until
    }
}
