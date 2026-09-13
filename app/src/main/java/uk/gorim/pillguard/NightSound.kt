package uk.gorim.pillguard

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import java.io.File
import kotlin.math.exp
import kotlin.math.sin

/**
 * The night pills' sound: a low four-strike bell figure, deliberately nothing like the daytime alarm
 * tone, so which container to fetch is clear before anything is read off the screen.
 *
 * It is synthesised into the cache on first use rather than shipped as an asset — one small piece of
 * arithmetic instead of half a megabyte in the repo, and it can be regenerated if the file is ever
 * cleared.
 */
object NightSound {
    private const val SR = 44100
    private const val FILE = "night_chime.wav"
    private const val SECONDS = 5.0

    /** G4, G4, E4, C4 — a descending figure that reads as a bell, not an alarm. */
    private val STRIKES = listOf(0.00 to 392.00, 0.55 to 392.00, 1.10 to 329.63, 1.90 to 261.63)
    /** Amplitude and frequency multiple of each partial of a struck bell. */
    private val PARTIALS = listOf(1.0 to 1.0, 0.55 to 2.0, 0.30 to 2.76, 0.18 to 4.07, 0.10 to 5.43)

    /** The generated file, written if it isn't there yet. Null if it could not be created. */
    fun bundledUri(ctx: Context): Uri? {
        val f = File(ctx.applicationContext.cacheDir, FILE)
        if (!f.exists() || f.length() < 1024) {
            val ok = runCatching { write(f) }.isSuccess
            if (!ok) return null
        }
        return Uri.fromFile(f)
    }

    fun uri(ctx: Context): Uri? {
        val s = Store.get(ctx).settings.nightSoundUri
        if (s.isEmpty()) return bundledUri(ctx)
        return runCatching { Uri.parse(s) }.getOrNull() ?: bundledUri(ctx)
    }

    private fun write(f: File) {
        val n = (SR * SECONDS).toInt()
        val buf = DoubleArray(n)
        for ((start, freq) in STRIKES) {
            val at = (start * SR).toInt()
            for (i in at until n) {
                val t = (i - at) / SR.toDouble()
                if (t > 2.6) break
                var v = 0.0
                for ((amp, mult) in PARTIALS) {
                    // Higher partials die away faster, which is what makes it sound struck.
                    v += amp * sin(2 * Math.PI * freq * mult * t) * exp(-t / (1.5 / Math.sqrt(mult)))
                }
                // Soft attack so each strike doesn't click.
                buf[i] += v * minOf(1.0, t / 0.006)
            }
        }
        var peak = 0.0
        for (v in buf) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
        val gain = if (peak > 0) 0.92 / peak else 0.0
        // Fade the tail so the loop join is silent.
        val fade = (SR * 0.15).toInt()

        val pcm = ByteArray(n * 2)
        for (i in 0 until n) {
            var v = buf[i] * gain
            if (i >= n - fade) v *= (n - i) / fade.toDouble()
            val s = (v * 32767).toInt().coerceIn(-32768, 32767)
            pcm[i * 2] = (s and 0xFF).toByte()
            pcm[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }

        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.outputStream().use { out ->
            out.write(wavHeader(pcm.size))
            out.write(pcm)
        }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }

    private fun wavHeader(dataBytes: Int): ByteArray {
        val byteRate = SR * 2
        fun le32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
        )
        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        return "RIFF".toByteArray() + le32(36 + dataBytes) + "WAVE".toByteArray() +
            "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) +
            le32(SR) + le32(byteRate) + le16(2) + le16(16) +
            "data".toByteArray() + le32(dataBytes)
    }

    /** Plays it once at [amp] — used for the Settings preview; the alarm itself loops its own player. */
    fun play(ctx: Context, amp: Float, onDone: () -> Unit = {}): MediaPlayer? {
        val app = ctx.applicationContext
        var finished = false
        fun finish(mp: MediaPlayer?) {
            if (finished) return
            finished = true
            runCatching { mp?.stop() }; runCatching { mp?.release() }
            onDone()
        }
        fun open(u: Uri?) = u?.let {
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    )
                    setDataSource(app, it)
                    setVolume(amp, amp)
                    setOnCompletionListener { p -> finish(p) }
                    setOnErrorListener { p, _, _ -> finish(p); true }
                    prepare(); start()
                }
            }.getOrNull()
        }
        val mp = open(uri(app)) ?: open(bundledUri(app))
        if (mp == null) { finish(null); return null }
        return mp
    }
}
