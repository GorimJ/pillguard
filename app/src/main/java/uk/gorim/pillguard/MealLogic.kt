package uk.gorim.pillguard

import java.time.LocalDate

/**
 * Meal reminders (pure logic). A meal reminder for day D, index i:
 *  - is "done" once a meal was recorded at or after (mealTime - 60 min) on that day, or the user stopped it;
 *  - otherwise fires at mealTime, then every repeatMin, but only while eating is actually allowed
 *    (dose taken, 30 min passed, more than 90 min to the next dose);
 *  - gives up for the day once the eating window has closed (next dose too close) or the day ends.
 */
class MealLogic(
    private val settings: Settings,
    private val engine: Engine,
    private val meals: List<Meal>,
    private val stopped: Set<String>,
) {
    data class Due(val key: String, val label: String, val mealMillis: Long, val fireAt: Long, val repeatMin: Int)

    fun key(date: LocalDate, idx: Int) = "$date#$idx"

    private fun endOfDay(date: LocalDate): Long = TimeFmt.millisOf(date.plusDays(1), 0)

    fun isDone(date: LocalDate, idx: Int, mt: MealTime, now: Long): Boolean {
        if (key(date, idx) in stopped) return true
        val start = TimeFmt.millisOf(date, mt.minuteOfDay)
        val end = endOfDay(date)
        if (meals.any { it.atMillis in (start - 60 * 60_000L) until end }) return true
        // Give up on a meal once the next meal's time has arrived...
        settings.meals.getOrNull(idx + 1)?.let { if (now >= TimeFmt.millisOf(date, it.minuteOfDay)) return true }
        // ...or once a pill has come due since the meal time (the eating window has closed and moved on).
        if (engine.window(now).any { it.effectiveMillis > start && it.effectiveMillis <= now }) return true
        return false
    }

    /** Next reminder that should be armed after [now], or null. */
    fun next(now: Long): Due? {
        if (!settings.mealsEnabled || settings.meals.isEmpty()) return null
        val today = TimeFmt.dateOf(now)
        for (date in listOf(today, today.plusDays(1))) {
            val eod = endOfDay(date)
            settings.meals.forEachIndexed { i, mt ->
                if (isDone(date, i, mt, now)) return@forEachIndexed
                val start = TimeFmt.millisOf(date, mt.minuteOfDay)
                val rep = mt.repeatMin.coerceAtLeast(1) * 60_000L
                var t = if (start > now) start else start + ((now - start) / rep + 1) * rep
                // If eating opens up (30 min after a dose) before the next repeat, fire then instead.
                engine.lastTaken(now)?.let { lt ->
                    val opens = lt.takenAt + settings.eatAfterMin * 60_000L
                    if (opens > now && opens > start && opens < t) t = opens
                }
                if (t < eod) return Due(key(date, i), mt.label, start, t, mt.repeatMin)
            }
        }
        return null
    }

    /** Whether a reminder firing at [at] should actually make noise. */
    fun shouldRing(at: Long): Boolean = engine.eatStatus(at).kind == EatKind.OK
}
