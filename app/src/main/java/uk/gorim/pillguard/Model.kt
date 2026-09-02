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

data class Settings(
    val doseTimes: List<DoseTime>,
    /** Minutes after a dose is taken before eating is allowed. */
    val eatAfterMin: Int,
    /** Minutes before the next dose by which eating must be finished. */
    val eatBeforeMin: Int,
    val snoozeMin: Int,
    /** Minutes of continuous ringing before the alarm auto-snoozes. */
    val ringTimeoutMin: Int,
    val pin: String,
    val qrSecret: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("doseTimes", JSONArray().apply { doseTimes.forEach { put(it.toJson()) } })
        .put("eatAfterMin", eatAfterMin)
        .put("eatBeforeMin", eatBeforeMin)
        .put("snoozeMin", snoozeMin)
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

        fun defaults(secret: String) = Settings(
            doseTimes = DEFAULT_DOSES,
            eatAfterMin = 30,
            eatBeforeMin = 90,
            snoozeMin = 5,
            ringTimeoutMin = 4,
            pin = "",
            qrSecret = secret,
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
                ringTimeoutMin = o.optInt("ringTimeoutMin", d.ringTimeoutMin),
                pin = o.optString("pin", ""),
                qrSecret = o.optString("qrSecret", secretIfMissing).ifEmpty { secretIfMissing },
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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("shiftedTo", shiftedTo).put("shiftReason", shiftReason)
        .put("takenAt", takenAt).put("method", method).put("missed", missed).put("snoozes", snoozes)

    companion object {
        fun fromJson(o: JSONObject) = DoseRecord(
            key = o.getString("key"),
            shiftedTo = o.optLong("shiftedTo", 0),
            shiftReason = o.optString("shiftReason", ""),
            takenAt = o.optLong("takenAt", 0),
            method = o.optString("method", ""),
            missed = o.optBoolean("missed", false),
            snoozes = o.optInt("snoozes", 0),
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
