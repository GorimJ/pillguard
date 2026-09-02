package uk.gorim.pillguard

import java.time.LocalDate

/**
 * Pure scheduling logic. No Android dependencies so it can be unit-tested on the JVM.
 *
 * Rules (as given):
 *  - Doses are at fixed times of day.
 *  - Eating is allowed from [eatAfterMin] after a dose was actually taken (QR scanned)
 *    until [eatBeforeMin] before the next dose.
 *  - "I ate at T" pushes the next dose (only the next one) to at least T + eatBeforeMin.
 *  - An untaken dose is considered missed once the following dose becomes due.
 */
class Engine(
    private val settings: Settings,
    private val records: Map<String, DoseRecord>,
    /** Doses scheduled before this instant (first run) are ignored rather than reported as missed. */
    private val startedAt: Long = 0,
) {
    private val minute = 60_000L

    /** All dose instances for the given days, in time order, with records applied and lateness resolved. */
    fun instances(days: List<LocalDate>, now: Long): List<DoseInstance> {
        val raw = ArrayList<DoseInstance>()
        for (date in days) {
            settings.doseTimes.forEachIndexed { i, dt ->
                val key = TimeFmt.key(date, i)
                val sched = TimeFmt.millisOf(date, dt.minuteOfDay)
                val r = records[key]
                if (sched < startedAt && (r == null || r.takenAt == 0L)) return@forEachIndexed
                val eff = if (r != null && r.shiftedTo > 0) r.shiftedTo else sched
                val status = when {
                    r != null && r.takenAt > 0 -> DoseStatus.TAKEN
                    r != null && r.missed -> DoseStatus.MISSED
                    else -> DoseStatus.PENDING
                }
                raw.add(
                    DoseInstance(
                        key = key, index = i, label = dt.label,
                        scheduledMillis = sched, effectiveMillis = eff,
                        takenAt = r?.takenAt ?: 0, method = r?.method ?: "",
                        status = status, snoozes = r?.snoozes ?: 0,
                        shiftReason = r?.shiftReason ?: "",
                    )
                )
            }
        }
        raw.sortBy { it.effectiveMillis }
        // Resolve lateness: a pending dose is missed once a later dose has become due.
        return raw.mapIndexed { idx, d ->
            if (d.status == DoseStatus.PENDING) {
                val laterDue = raw.drop(idx + 1).any { it.effectiveMillis <= now }
                if (laterDue) d.copy(status = DoseStatus.MISSED) else d
            } else d
        }
    }

    fun window(now: Long): List<DoseInstance> {
        val today = TimeFmt.dateOf(now)
        return instances(listOf(today.minusDays(1), today, today.plusDays(1)), now)
    }

    fun todays(now: Long): List<DoseInstance> {
        val today = TimeFmt.dateOf(now)
        return window(now).filter { TimeFmt.dateOf(it.scheduledMillis) == today }
    }

    /** The dose that should be ringing right now: pending, effective time reached, not superseded. */
    fun dueDose(now: Long): DoseInstance? =
        window(now).lastOrNull { it.status == DoseStatus.PENDING && it.effectiveMillis <= now }

    /** The next pending dose strictly in the future. */
    fun nextUpcoming(now: Long): DoseInstance? =
        window(now).firstOrNull { it.status == DoseStatus.PENDING && it.effectiveMillis > now }

    /** Most recently taken dose (by time taken). */
    fun lastTaken(now: Long): DoseInstance? =
        window(now).filter { it.status == DoseStatus.TAKEN && it.takenAt <= now }.maxByOrNull { it.takenAt }

    fun eatStatus(now: Long): EatStatus {
        val due = dueDose(now)
        val last = lastTaken(now)
        val next = nextUpcoming(now)
        val eatAfter = settings.eatAfterMin * minute
        val eatBefore = settings.eatBeforeMin * minute

        if (due != null) {
            return EatStatus(
                EatKind.DOSE_DUE, 0,
                "Take your ${due.label.lowercase()} medication first",
                "It was due at ${TimeFmt.hm(due.effectiveMillis)}. Scan the pill container, then wait ${settings.eatAfterMin} minutes before eating.",
            )
        }
        if (last != null && now < last.takenAt + eatAfter) {
            val until = last.takenAt + eatAfter
            return EatStatus(
                EatKind.WAIT_AFTER_DOSE, until,
                "Don't eat yet",
                "Wait until ${TimeFmt.hm(until)} (${settings.eatAfterMin} min after your ${last.label.lowercase()} dose).",
            )
        }
        if (next != null && now >= next.effectiveMillis - eatBefore) {
            return EatStatus(
                EatKind.TOO_CLOSE_TO_NEXT, next.effectiveMillis + eatAfter,
                "Don't eat now",
                "Your ${next.label.lowercase()} dose is at ${TimeFmt.hm(next.effectiveMillis)}. You need ${settings.eatBeforeMin} min with no food before it.",
            )
        }
        val closes = next?.let { it.effectiveMillis - eatBefore } ?: 0L
        val detail = if (next != null)
            "Finish eating by ${TimeFmt.hm(closes)} — next dose (${next.label.lowercase()}) at ${TimeFmt.hm(next.effectiveMillis)}."
        else "No more doses scheduled."
        return EatStatus(EatKind.OK, closes, if (next != null) "OK to eat until ${TimeFmt.hm(closes)}" else "OK to eat", detail)
    }

    /**
     * Result of eating at [ateAt]: which dose (if any) must move, and to when.
     * Only the next pending dose after the meal is shifted; later doses stay put.
     */
    fun mealShift(ateAt: Long, now: Long): Pair<DoseInstance, Long>? {
        val eatBefore = settings.eatBeforeMin * minute
        val candidate = window(now).firstOrNull { it.status == DoseStatus.PENDING && it.effectiveMillis > ateAt }
            ?: return null
        val earliest = ateAt + eatBefore
        return if (candidate.effectiveMillis < earliest) candidate to earliest else null
    }

    /** True if a dose shifted to [newTime] would land within [gapMin] of the following pending dose. */
    fun crowdsFollowing(shifted: DoseInstance, newTime: Long, now: Long, gapMin: Int = 60): DoseInstance? {
        val following = window(now).firstOrNull {
            it.status == DoseStatus.PENDING && it.key != shifted.key && it.effectiveMillis > shifted.effectiveMillis
        } ?: return null
        return if (following.effectiveMillis - newTime < gapMin * minute) following else null
    }
}
