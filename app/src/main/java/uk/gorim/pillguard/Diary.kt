package uk.gorim.pillguard

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Builds a day-by-day diary (text) and a CSV of every dose/meal/event, and shares them. */
object Diary {
    private val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")

    fun text(ctx: Context, days: Int): String {
        val store = Store.get(ctx)
        val s = store.settings
        val now = System.currentTimeMillis()
        val today = TimeFmt.dateOf(now)
        val engine = store.engine()
        val meals = store.meals
        val log = store.log
        val sb = StringBuilder()
        sb.append("PillGuard diary — ${TimeFmt.dayHm(now)}\n")
        sb.append("Doses: ${s.doseTimes.joinToString(", ") { "${it.label} ${TimeFmt.minuteOfDay(it.minuteOfDay)}" }}\n")
        sb.append("Rules: no food for ${s.eatAfterMin} min after a dose, none in the ${s.eatBeforeMin} min before one.\n")

        for (i in 0 until days) {
            val date = today.minusDays(i.toLong())
            val dayStart = TimeFmt.millisOf(date, 0)
            val dayEnd = TimeFmt.millisOf(date.plusDays(1), 0)
            val doses = engine.instances(listOf(date), now).filter { it.scheduledMillis >= store.startedAt - 1 }
            val dayMeals = meals.filter { it.atMillis in dayStart until dayEnd }.sortedBy { it.atMillis }
            val dayLog = log.filter { it.atMillis in dayStart until dayEnd }
            if (doses.isEmpty() && dayMeals.isEmpty() && dayLog.isEmpty()) continue

            sb.append("\n== ${date.format(dayFmt)} ==\n")
            val taken = doses.count { it.status == DoseStatus.TAKEN }
            val missed = doses.count { it.status == DoseStatus.MISSED }
            val pending = doses.count { it.status == DoseStatus.PENDING }
            sb.append("Doses: $taken taken, $missed missed${if (pending > 0) ", $pending still to come" else ""}\n")
            for (d in doses) {
                val line = StringBuilder("  ${TimeFmt.hm(d.scheduledMillis)} ${d.label}")
                if (d.isShifted) line.append(" (moved to ${TimeFmt.hm(d.effectiveMillis)}${if (d.shiftReason.isNotEmpty()) ", ${d.shiftReason}" else ""})")
                line.append(": ")
                when (d.status) {
                    DoseStatus.TAKEN -> {
                        val lateMin = (d.takenAt - d.effectiveMillis) / 60_000
                        line.append("taken ${TimeFmt.hm(d.takenAt)}")
                        line.append(
                            when {
                                lateMin > 2 -> " ($lateMin min late)"
                                lateMin < -2 -> " (${-lateMin} min early)"
                                else -> " (on time)"
                            }
                        )
                        if (d.method == "override") line.append(" — carer override")
                    }
                    DoseStatus.MISSED -> line.append("MISSED")
                    DoseStatus.PENDING -> line.append(if (d.effectiveMillis <= now) "DUE NOW" else "not yet due")
                }
                if (d.snoozes > 0) line.append(", alarm repeated ${d.snoozes}×")
                sb.append(line).append('\n')
            }
            if (dayMeals.isNotEmpty()) sb.append("Meals: ${dayMeals.joinToString(", ") { TimeFmt.hm(it.atMillis) }}\n")
            val notable = dayLog.filter { e ->
                e.text.contains("override", true) || e.text.contains("MISSED") || e.text.contains("moved") ||
                    e.text.contains("Alert", true) || e.text.contains("Settings", true) || e.text.contains("logged only")
            }
            if (notable.isNotEmpty()) {
                sb.append("Notes:\n")
                notable.forEach { sb.append("  ${TimeFmt.hm(it.atMillis)} ${it.text}\n") }
            }
        }
        return sb.toString()
    }

    fun csv(ctx: Context, days: Int): String {
        val store = Store.get(ctx)
        val now = System.currentTimeMillis()
        val today = TimeFmt.dateOf(now)
        val engine = store.engine()
        val sb = StringBuilder("date,type,label,scheduled,effective,actual,status,method,minutes_late,alarm_repeats,note\n")
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        for (i in (days - 1) downTo 0) {
            val date = today.minusDays(i.toLong())
            val dayStart = TimeFmt.millisOf(date, 0)
            val dayEnd = TimeFmt.millisOf(date.plusDays(1), 0)
            for (d in engine.instances(listOf(date), now).filter { it.scheduledMillis >= store.startedAt - 1 }) {
                val late = if (d.takenAt > 0) ((d.takenAt - d.effectiveMillis) / 60_000).toString() else ""
                sb.append(
                    listOf(
                        date.toString(), "dose", d.label, TimeFmt.hm(d.scheduledMillis), TimeFmt.hm(d.effectiveMillis),
                        if (d.takenAt > 0) TimeFmt.hm(d.takenAt) else "", d.status.name.lowercase(), d.method, late,
                        d.snoozes.toString(), d.shiftReason
                    ).joinToString(",") { q(it) }
                ).append('\n')
            }
            for (m in store.meals.filter { it.atMillis in dayStart until dayEnd }) {
                sb.append(listOf(date.toString(), "meal", "", "", "", TimeFmt.hm(m.atMillis), "", "", "", "", "").joinToString(",") { q(it) }).append('\n')
            }
            for (e in store.log.filter { it.atMillis in dayStart until dayEnd }) {
                sb.append(listOf(date.toString(), "event", "", "", "", TimeFmt.hm(e.atMillis), "", "", "", "", e.text).joinToString(",") { q(it) }).append('\n')
            }
        }
        return sb.toString()
    }

    fun share(ctx: Context, days: Int, asCsv: Boolean) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val stamp = LocalDate.now().toString()
        val f = File(dir, if (asCsv) "pillguard-$stamp.csv" else "pillguard-diary-$stamp.txt")
        f.writeText(if (asCsv) csv(ctx, days) else text(ctx, days))
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val i = Intent(Intent.ACTION_SEND)
            .setType(if (asCsv) "text/csv" else "text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "PillGuard ${if (asCsv) "data" else "diary"} $stamp")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!asCsv) i.putExtra(Intent.EXTRA_TEXT, text(ctx, minOf(days, 7)))
        ctx.startActivity(Intent.createChooser(i, "Share diary"))
    }
}
