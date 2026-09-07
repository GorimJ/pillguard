package uk.gorim.pillguard

/**
 * Volume maths. Percentages in settings are "how loud it feels"; MediaPlayer.setVolume wants a
 * linear amplitude, so square the fraction — 25% feels like a quarter as loud rather than
 * being barely-below-full.
 *
 * Everything is relative to the phone's own alarm-stream volume, which is the ceiling.
 */
object Volume {
    fun amp(pct: Int): Float {
        val f = (pct.coerceIn(0, 100)) / 100f
        return f * f
    }

    /** Full loudness the app is allowed to reach. */
    fun maxAmp(s: Settings): Float = amp(s.alarmVolumePct)

    /** Where a ring starts. */
    fun startAmp(s: Settings): Float = maxAmp(s) * amp(s.alarmStartVolumePct).coerceAtLeast(0.02f)

    /** Amplitude [elapsedMs] into a ring, climbing from start to full over volumeRampMin. */
    fun rampAmp(s: Settings, elapsedMs: Long): Float {
        val start = startAmp(s)
        val max = maxAmp(s)
        val rampMs = s.volumeRampMin.coerceAtLeast(0) * 60_000L
        if (rampMs <= 0L) return max
        val progress = (elapsedMs.toFloat() / rampMs).coerceIn(0f, 1f)
        return start + (max - start) * progress
    }
}
