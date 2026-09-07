package uk.gorim.pillguard

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper

/** Plays the meal-reminder sound once on the alarm stream (so silent mode doesn't mute it). */
object MealSound {
    fun bundledUri(ctx: Context): Uri = Uri.parse("android.resource://${ctx.packageName}/${R.raw.mess_call}")

    fun uri(ctx: Context): Uri {
        val s = Store.get(ctx).settings.mealSoundUri
        return if (s.isEmpty()) bundledUri(ctx) else Uri.parse(s)
    }

    /**
     * Plays once at [amp] (0..1 linear amplitude; null = the configured full volume).
     * Returns the player so a caller can stop it early; calls [onDone] when finished (max 25 s).
     */
    fun play(ctx: Context, amp: Float? = null, onDone: () -> Unit = {}): MediaPlayer? {
        val app = ctx.applicationContext
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        fun finish(mp: MediaPlayer?) {
            if (finished) return
            finished = true
            runCatching { mp?.stop() }; runCatching { mp?.release() }
            onDone()
        }
        val mp = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                setDataSource(app, uri(app))
                isLooping = false
                val v = amp ?: Volume.maxAmp(Volume.meal(Store.get(app).settings))
                setVolume(v, v)
                setOnCompletionListener { finish(it) }
                setOnErrorListener { p, _, _ -> finish(p); true }
                prepare()
                start()
            }
        }.getOrElse {
            // Custom sound unreadable? Fall back to the bundled bugle.
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    )
                    setDataSource(app, bundledUri(app))
                    val v = amp ?: Volume.maxAmp(Volume.meal(Store.get(app).settings))
                    setVolume(v, v)
                    setOnCompletionListener { finish(it) }
                    prepare(); start()
                }
            }.getOrNull()
        }
        if (mp == null) { finish(null); return null }
        handler.postDelayed({ finish(mp) }, 25_000)
        return mp
    }
}
