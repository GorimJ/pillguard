package uk.gorim.pillguard

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * Carer alerts over ntfy.sh: the phone POSTs a message to a private topic; the carer's
 * ntfy app (subscribed to that topic) shows it as a push notification. No account needed.
 */
object Alerts {
    private const val BASE = "https://ntfy.sh/"

    fun topicUrl(topic: String) = BASE + topic

    fun newTopic(): String = "pillguard-" + Store.newSecret().lowercase()

    /** Sends on a background thread; failures are logged, never thrown. */
    fun send(ctx: Context, title: String, message: String, priority: Int = 4, onDone: ((Boolean) -> Unit)? = null) {
        val store = Store.get(ctx)
        val s = store.settings
        if (s.alertTopic.isEmpty()) { onDone?.invoke(false); return }
        Thread {
            val ok = runCatching {
                val c = URL(topicUrl(s.alertTopic)).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                c.doOutput = true
                c.setRequestProperty("Title", title)
                c.setRequestProperty("Priority", priority.toString())
                c.setRequestProperty("Tags", "pill")
                c.outputStream.use { it.write(message.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                c.disconnect()
                code in 200..299
            }.getOrDefault(false)
            store.log(if (ok) "Alert sent: $title" else "Alert FAILED to send: $title")
            onDone?.invoke(ok)
        }.start()
    }

    /** Called when the dose-unconfirmed alarm fires. Sends only if the dose is still pending. */
    fun onUnconfirmedCheck(ctx: Context, key: String) {
        val store = Store.get(ctx)
        val s = store.settings
        if (!s.alertsEnabled) return
        val now = System.currentTimeMillis()
        val inst = store.engine().window(now).firstOrNull { it.key == key } ?: return
        if (inst.status != DoseStatus.PENDING) return
        if (store.records[key]?.alerted == true) return
        store.updateRecord(key) { it.copy(alerted = true) }
        val mins = (now - inst.effectiveMillis) / 60_000
        send(
            ctx, "${inst.label} medication not confirmed",
            "Due ${TimeFmt.hm(inst.effectiveMillis)}, $mins min ago. Snoozed ${inst.snoozes} time(s) so far.",
            priority = 5,
        )
    }

    /** Red-triangle delay: always a loud ping, this is the one action that defers a dose. */
    fun onDelayed(ctx: Context, key: String, newTime: Long, delayNo: Int, clash: DoseInstance?) {
        val store = Store.get(ctx)
        if (!store.settings.alertsEnabled) return
        val label = store.labelFor(key)
        val body = StringBuilder("Pushed back to ${TimeFmt.hm(newTime)}. Delay $delayNo of ${DoseRecord.MAX_DELAYS}")
        body.append(if (delayNo >= DoseRecord.MAX_DELAYS) " — no more delays allowed." else ".")
        clash?.let { body.append("\nThis now sits close to the ${it.label.lowercase()} dose at ${TimeFmt.hm(it.effectiveMillis)}.") }
        send(ctx, "$label medication DELAYED an hour", body.toString(), priority = 5)
    }

    /** Called after every dose confirmation: a quiet "taken" note, louder if an alert had gone out first. */
    fun onTaken(ctx: Context, key: String, method: String) {
        val store = Store.get(ctx)
        if (!store.settings.alertsEnabled) return
        val r = store.records[key] ?: return
        val label = store.labelFor(key)
        val inst = store.engine().window(System.currentTimeMillis()).firstOrNull { it.key == key }
        val lateMin = inst?.let { (r.takenAt - it.effectiveMillis) / 60_000 } ?: 0
        val timing = when {
            lateMin > 2 -> "$lateMin min late"
            lateMin < -2 -> "${-lateMin} min early"
            else -> "on time"
        }
        send(
            ctx, "$label medication taken",
            "${TimeFmt.hm(r.takenAt)} — $timing (${if (method == "scan") "QR scanned" else "carer override"}).",
            priority = if (r.alerted) 3 else 2,
        )
    }
}
