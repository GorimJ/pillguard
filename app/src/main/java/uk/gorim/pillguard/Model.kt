package uk.gorim.pillguard

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A dose slot in the daily routine, e.g. 07:00 "Morning". */
data class DoseTime(val minuteOfDay: Int, val label: String) {
    fun toJson(): JSONObject = JSONObject().put("m", minuteOfDay).put("l", label)

    companion object {
        fun fromJson(o: JSONObject) = DoseTime(o.getInt("m"), o.getString("l"))
    }
}

/** A meal reminder: nags every [repeatMin] minutes from [minuteOfDay] until he says he's eating. */
data class MealTime(val minuteOfDay: Int, val label: String, val repeatMin: Int) {
    fun toJson(): JSONObject = JSONObject().put("m", minuteOfDay).put("l", label).put("r", repeatMin)

    companion object {
        fun fromJson(o: JSONObject) = MealTime(o.getInt("m"), o.getString("l"), o.optInt("r", 15))
    }
}

data class Settings(
    val doseTimes: List<DoseTime>,
    /** Minutes after a dose is taken before eating is allowed. */
    val eatAfterMin: Int,
    /** Minutes before the next dose by which eating must be finished. */
    val eatBeforeMin: Int,
    /** Legacy; superseded by [reRingMin]. */
    val snoozeMin: Int,
    /** Minutes of quiet after "I'm going to get the pill" before the alarm rings again. */
    val reRingMin: Int,
    /** Minutes of continuous ringing before the alarm goes quiet and re-rings after [reRingMin]. */
    val ringTimeoutMin: Int,
    val pin: String,
    val qrSecret: String,
    /** Push alerts to the carer via ntfy.sh when a dose goes unconfirmed. */
    val alertsEnabled: Boolean,
    val alertTopic: String,
    val alertAfterMin: Int,
    /** Meal reminders. */
    val mealsEnabled: Boolean,
    val meals: List<MealTime>,
    /** Empty = bundled bugle call; otherwise a ringtone URI chosen by the user. */
    val mealSoundUri: String,
    /** Loudest the app will play, as a percentage of the phone's alarm volume. */
    val alarmVolumePct: Int,
    /** Where the alarm starts, as a percentage of [alarmVolumePct]. */
    val alarmStartVolumePct: Int,
    /** Minutes of ringing over which it climbs from start to full. 0 = full at once. */
    val volumeRampMin: Int,
    /** Meal reminders have their own, usually gentler, volume. */
    val mealVolumePct: Int,
    val mealStartVolumePct: Int,
    val mealVolumeRampMin: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mealsEnabled", mealsEnabled)
        .put("meals", JSONArray().apply { meals.forEach { put(it.toJson()) } })
        .put("mealSoundUri", mealSoundUri)
        .put("alarmVolumePct", alarmVolumePct)
        .put("alarmStartVolumePct", alarmStartVolumePct)
        .put("volumeRampMin", volumeRampMin)
        .put("mealVolumePct", mealVolumePct)
        .put("mealStartVolumePct", mealStartVolumePct)
        .put("mealVolumeRampMin", mealVolumeRampMin)
        .put("alertsEnabled", alertsEnabled)
        .put("alertTopic", alertTopic)
        .put("alertAfterMin", alertAfterMin)
        .put("doseTimes", JSONArray().apply { doseTimes.forEach { put(it.toJson()) } })
        .put("eatAfterMin", eatAfterMin)
        .put("eatBeforeMin", eatBeforeMin)
        .put("snoozeMin", snoozeMin)
        .put("reRingMin", reRingMin)
        .put("ringTimeoutMin", ringTimeoutMin)
        .put("pin", pin)
        .put("qrSecret", qrSecret)

    companion object {
        val DEFAULT_DOSES = listOf(
            DoseTime(7 * 60, "Morning"),
            DoseTime(10 * 60 + 30, "Mid-morning"),
            DoseTime(14 * 60 + 30, "Afternoon"),
            DoseTime(18 * 60 + 30, "Evening"),
            DoseTime(22 * 60 + 30, "Night"),
        )

        val DEFAULT_MEALS = listOf(
            MealTime(7 * 60 + 30, "Breakfast", 15),
            MealTime(11 * 60, "Lunch", 15),
            MealTime(19 * 60, "Dinner", 30),
        )

        fun defaults(secret: String) = Settings(
            doseTimes = DEFAULT_DOSES,
            eatAfterMin = 30,
            eatBeforeMin = 90,
            snoozeMin = 5,
            reRingMin = 2,
            ringTimeoutMin = 2,
            pin = "",
            qrSecret = secret,
            alertsEnabled = false,
            alertTopic = "",
            alertAfterMin = 15,
            mealsEnabled = true,
            meals = DEFAULT_MEALS,
            mealSoundUri = "",
            alarmVolumePct = 100,
            alarmStartVolumePct = 25,
            volumeRampMin = 5,
            mealVolumePct = 70,
            mealStartVolumePct = 30,
            mealVolumeRampMin = 20,
        )

        fun fromJson(o: JSONObject, secretIfMissing: String): Settings {
            val d = defaults(secretIfMissing)
            val arr = o.optJSONArray("doseTimes")
            val doses = if (arr == null || arr.length() == 0) d.doseTimes
            else (0 until arr.length()).map { DoseTime.fromJson(arr.getJSONObject(it)) }.sortedBy { it.minuteOfDay }
            return Settings(
                doseTimes = doses,
                eatAfterMin = o.optInt("eatAfterMin", d.eatAfterMin),
                eatBeforeMin = o.optInt("eatBeforeMin", d.eatBeforeMin),
                snoozeMin = o.optInt("snoozeMin", d.snoozeMin),
                reRingMin = o.optInt("reRingMin", d.reRingMin),
                ringTimeoutMin = o.optInt("ringTimeoutMin", d.ringTimeoutMin),
                pin = o.optString("pin", ""),
                qrSecret = o.optString("qrSecret", secretIfMissing).ifEmpty { secretIfMissing },
                alertsEnabled = o.optBoolean("alertsEnabled", false),
                alertTopic = o.optString("alertTopic", ""),
                alertAfterMin = o.optInt("alertAfterMin", 15),
                mealsEnabled = o.optBoolean("mealsEnabled", true),
                meals = o.optJSONArray("meals")?.let { a ->
                    (0 until a.length()).map { MealTime.fromJson(a.getJSONObject(it)) }.sortedBy { it.minuteOfDay }
                } ?: d.meals,
                mealSoundUri = o.optString("mealSoundUri", ""),
                alarmVolumePct = o.optInt("alarmVolumePct", d.alarmVolumePct),
                alarmStartVolumePct = o.optInt("alarmStartVolumePct", d.alarmStartVolumePct),
                volumeRampMin = o.optInt("volumeRampMin", d.volumeRampMin),
                mealVolumePct = o.optInt("mealVolumePct", d.mealVolumePct),
                mealStartVolumePct = o.optInt("mealStartVolumePct", d.mealStartVolumePct),
                mealVolumeRampMin = o.optInt("mealVolumeRampMin", d.mealVolumeRampMin),
            )
        }
    }
}

enum class DoseStatus { PENDING, TAKEN, MISSED }

/** Persisted deviations for one dose on one day. Key = "yyyy-MM-dd#index". */
data class DoseRecord(
    val key: String,
    /** Effective (possibly shifted) time, epoch millis. 0 = not shifted. */
    val shiftedTo: Long = 0,
    val shiftReason: String = "",
    val takenAt: Long = 0,
    val method: String = "",
    val missed: Boolean = false,
    val snoozes: Int = 0,
    /** A "not confirmed" alert was sent to the carer for this dose. */
    val alerted: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("shiftedTo", shiftedTo).put("shiftReason", shiftReason)
        .put("takenAt", takenAt).put("method", method).put("missed", missed).put("snoozes", snoozes)
        .put("alerted", alerted)

    companion object {
        fun fromJson(o: JSONObject) = DoseRecord(
            key = o.getString("key"),
            shiftedTo = o.optLong("shiftedTo", 0),
            shiftReason = o.optString("shiftReason", ""),
            takenAt = o.optLong("takenAt", 0),
            method = o.optString("method", ""),
            missed = o.optBoolean("missed", false),
            snoozes = o.optInt("snoozes", 0),
            alerted = o.optBoolean("alerted", false),
        )
    }
}

/** A concrete dose on a concrete day, with record applied. */
data class DoseInstance(
    val key: String,
    val index: Int,
    val label: String,
    val scheduledMillis: Long,
    val effectiveMillis: Long,
    val takenAt: Long,
    val method: String,
    val status: DoseStatus,
    val snoozes: Int,
    val shiftReason: String,
) {
    val isShifted get() = effectiveMillis != scheduledMillis
}

data class Meal(val atMillis: Long) {
    fun toJson(): JSONObject = JSONObject().put("at", atMillis)
    companion object { fun fromJson(o: JSONObject) = Meal(o.getLong("at")) }
}

data class LogEntry(val atMillis: Long, val text: String) {
    fun toJson(): JSONObject = JSONObject().put("at", atMillis).put("text", text)
    companion object { fun fromJson(o: JSONObject) = LogEntry(o.getLong("at"), o.getString("text")) }
}

enum class EatKind { OK, WAIT_AFTER_DOSE, TOO_CLOSE_TO_NEXT, DOSE_DUE }

data class EatStatus(
    val kind: EatKind,
    /** For OK: when the window closes (0 = open-ended). For WAIT/TOO_CLOSE: when the next OK moment is (0 = unknown). */
    val untilMillis: Long,
    val headline: String,
    val detail: String,
)

object TimeFmt {
    val zone: ZoneId get() = ZoneId.systemDefault()
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val dhm = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

    fun hm(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).format(hm)
    fun dayHm(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).format(dhm)
    fun minuteOfDay(m: Int): String = LocalTime.of(m / 60, m % 60).format(hm)
    fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    fun millisOf(date: LocalDate, minuteOfDay: Int): Long =
        date.atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(zone).toInstant().toEpochMilli()
    fun key(date: LocalDate, index: Int) = "$date#$index"
}
