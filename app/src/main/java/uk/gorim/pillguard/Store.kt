package uk.gorim.pillguard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** Single source of truth, persisted as JSON in SharedPreferences. */
class Store private constructor(ctx: Context) {
    private val prefs: SharedPreferences = ctx.applicationContext.getSharedPreferences("pillguard", Context.MODE_PRIVATE)

    companion object {
        @Volatile private var instance: Store? = null
        fun get(ctx: Context): Store = instance ?: synchronized(this) { instance ?: Store(ctx).also { instance = it } }

        const val QR_PREFIX = "PILLGUARD:"
        private const val K_SETTINGS = "settings"
        private const val K_RECORDS = "records"
        private const val K_MEALS = "meals"
        private const val K_LOG = "log"
        private const val K_RINGING = "ringing"
        private const val K_SNOOZE_UNTIL = "snoozeUntil"
        private const val K_SETUP_DONE = "setupDone"

        fun newSecret(): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val rnd = SecureRandom()
            return (1..12).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }
    }

    // ---- settings ----
    var settings: Settings
        get() {
            val s = prefs.getString(K_SETTINGS, null)
            val secret = newSecret()
            val parsed = if (s == null) Settings.defaults(secret) else Settings.fromJson(JSONObject(s), secret)
            if (s == null || JSONObject(s).optString("qrSecret", "").isEmpty()) {
                prefs.edit().putString(K_SETTINGS, parsed.toJson().toString()).apply()
            }
            return parsed
        }
        set(v) = prefs.edit().putString(K_SETTINGS, v.toJson().toString()).apply()

    val qrPayload: String get() = QR_PREFIX + settings.qrSecret
    fun matchesQr(text: String?): Boolean = text != null && text.trim() == qrPayload

    var setupDone: Boolean
        get() = prefs.getBoolean(K_SETUP_DONE, false)
        set(v) = prefs.edit().putBoolean(K_SETUP_DONE, v).apply()

    // ---- dose records ----
    val records: Map<String, DoseRecord>
        get() {
            val s = prefs.getString(K_RECORDS, null) ?: return emptyMap()
            val arr = JSONArray(s)
            val m = HashMap<String, DoseRecord>()
            for (i in 0 until arr.length()) {
                val r = DoseRecord.fromJson(arr.getJSONObject(i)); m[r.key] = r
            }
            return m
        }

    private fun saveRecords(m: Map<String, DoseRecord>) {
        // Keep the store small: drop records older than 60 days.
        val cutoff = System.currentTimeMillis() - 60L * 24 * 3600_000
        val arr = JSONArray()
        m.values.filter { r ->
            val date = r.key.substringBefore('#')
            runCatching { java.time.LocalDate.parse(date) }.getOrNull()?.let {
                TimeFmt.millisOf(it, 0) > cutoff
            } ?: true
        }.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(K_RECORDS, arr.toString()).apply()
    }

    fun updateRecord(key: String, f: (DoseRecord) -> DoseRecord) {
        val m = HashMap(records)
        m[key] = f(m[key] ?: DoseRecord(key))
        saveRecords(m)
    }

    val startedAt: Long
        get() {
            val v = prefs.getLong("startedAt", 0)
            if (v > 0) return v
            val now = System.currentTimeMillis()
            prefs.edit().putLong("startedAt", now).apply()
            return now
        }

    fun engine() = Engine(settings, records, startedAt)

    // ---- meal reminders ----
    val stoppedMeals: Set<String>
        get() = prefs.getStringSet("stoppedMeals", emptySet()) ?: emptySet()

    fun stopMeal(key: String) {
        // Keep only today's/tomorrow's keys so the set doesn't grow forever.
        val today = TimeFmt.dateOf(System.currentTimeMillis()).toString()
        val keep = stoppedMeals.filter { it >= today }.toMutableSet()
        keep += key
        prefs.edit().putStringSet("stoppedMeals", keep).apply()
        log("${key.substringAfter('#').toIntOrNull()?.let { settings.meals.getOrNull(it)?.label } ?: "Meal"} reminder stopped for today")
    }

    fun mealLogic() = MealLogic(settings, engine(), meals, stoppedMeals)

    // ---- ringing state ----
    var ringingKey: String?
        get() = prefs.getString(K_RINGING, null)
        set(v) = prefs.edit().putString(K_RINGING, v).apply()

    var snoozeUntil: Long
        get() = prefs.getLong(K_SNOOZE_UNTIL, 0)
        set(v) = prefs.edit().putLong(K_SNOOZE_UNTIL, v).apply()

    // ---- meals ----
    val meals: List<Meal>
        get() {
            val s = prefs.getString(K_MEALS, null) ?: return emptyList()
            val arr = JSONArray(s)
            return (0 until arr.length()).map { Meal.fromJson(arr.getJSONObject(it)) }
        }

    fun addMeal(m: Meal) {
        val cutoff = System.currentTimeMillis() - 60L * 24 * 3600_000
        val arr = JSONArray()
        (meals + m).filter { it.atMillis > cutoff }.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(K_MEALS, arr.toString()).apply()
    }

    // ---- log ----
    val log: List<LogEntry>
        get() {
            val s = prefs.getString(K_LOG, null) ?: return emptyList()
            val arr = JSONArray(s)
            return (0 until arr.length()).map { LogEntry.fromJson(arr.getJSONObject(it)) }
        }

    fun log(text: String, at: Long = System.currentTimeMillis()) {
        val all = (log + LogEntry(at, text)).takeLast(1000)
        val arr = JSONArray(); all.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(K_LOG, arr.toString()).apply()
    }

    // ---- high-level actions ----

    fun markTaken(key: String, method: String, at: Long = System.currentTimeMillis()) {
        updateRecord(key) { it.copy(takenAt = at, method = method, missed = false) }
        if (ringingKey == key) { ringingKey = null; snoozeUntil = 0 }
        val label = labelFor(key)
        log("$label dose taken (${if (method == "scan") "QR scanned" else "carer override"})", at)
    }

    fun markMissed(key: String) {
        updateRecord(key) { if (it.takenAt > 0) it else it.copy(missed = true) }
        log("${labelFor(key)} dose MISSED")
    }

    fun recordSnooze(key: String, minutes: Int, auto: Boolean) {
        updateRecord(key) { it.copy(snoozes = it.snoozes + 1) }
        snoozeUntil = System.currentTimeMillis() + minutes * 60_000L
        log("${labelFor(key)} alarm ${if (auto) "auto-" else ""}snoozed $minutes min")
    }

    /** Records a meal and shifts the next dose if needed. Returns the shifted dose and new time, or null. */
    fun recordMeal(ateAt: Long): Pair<DoseInstance, Long>? {
        addMeal(Meal(ateAt))
        log("Ate something at ${TimeFmt.hm(ateAt)}", ateAt)
        val shift = engine().mealShift(ateAt, System.currentTimeMillis()) ?: return null
        val (dose, newTime) = shift
        updateRecord(dose.key) { it.copy(shiftedTo = newTime, shiftReason = "ate at ${TimeFmt.hm(ateAt)}") }
        log("${dose.label} dose moved ${TimeFmt.hm(dose.effectiveMillis)} → ${TimeFmt.hm(newTime)} (ate at ${TimeFmt.hm(ateAt)})")
        return shift
    }

    fun labelFor(key: String): String {
        val idx = key.substringAfter('#').toIntOrNull() ?: return "Dose"
        return settings.doseTimes.getOrNull(idx)?.label ?: "Dose"
    }
}
