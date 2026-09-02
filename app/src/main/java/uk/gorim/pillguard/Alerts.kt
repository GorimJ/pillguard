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

    /** Called after a dose is confirmed; closes the loop if an alert had gone out. */
    fun onTaken(ctx: Context, key: String, method: String) {
        val store = Store.get(ctx)
        if (!store.settings.alertsEnabled) return
        val r = store.records[key] ?: return
        if (!r.alerted) return
        val label = store.labelFor(key)
        send(
            ctx, "$label medication now taken",
            "Confirmed at ${TimeFmt.hm(r.takenAt)} (${if (method == "scan") "QR scanned" else "carer override"}).",
            priority = 3,
        )
    }
}
