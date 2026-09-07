package uk.gorim.pillguard

/** One sound's volume behaviour: ceiling, where it starts, and how long it takes to climb. */
data class VolumeProfile(val maxPct: Int, val startPct: Int, val rampMin: Int)

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

    fun pill(s: Settings) = VolumeProfile(s.alarmVolumePct, s.alarmStartVolumePct, s.volumeRampMin)
    fun meal(s: Settings) = VolumeProfile(s.mealVolumePct, s.mealStartVolumePct, s.mealVolumeRampMin)

    /** Full loudness this sound is allowed to reach. */
    fun maxAmp(p: VolumeProfile): Float = amp(p.maxPct)

    /** Where it starts. */
    fun startAmp(p: VolumeProfile): Float = maxAmp(p) * amp(p.startPct).coerceAtLeast(0.02f)

    /** Amplitude [elapsedMs] into the ring, climbing from start to full over rampMin. */
    fun rampAmp(p: VolumeProfile, elapsedMs: Long): Float {
        val start = startAmp(p)
        val max = maxAmp(p)
        val rampMs = p.rampMin.coerceAtLeast(0) * 60_000L
        if (rampMs <= 0L) return max
        if (elapsedMs >= rampMs) return max   // exact, not start+(max-start)*1f which rounds short
        val progress = (elapsedMs.toFloat() / rampMs).coerceIn(0f, 1f)
        return start + (max - start) * progress
    }
}
