package uk.gorim.pillguard

/**
 * How a dose was confirmed, in one place so the diary, the log and the carer alert all say the
 * same words for it.
 */
object Methods {
    const val SCAN = "scan"
    const val OVERRIDE = "override"

    /** A dose slot that does not require the container's code: one tap on the alarm screen. */
    const val TAP = "tap"

    fun describe(method: String): String = when (method) {
        SCAN -> "QR scanned"
        TAP -> "tapped, no scan needed"
        OVERRIDE -> "carer override"
        else -> method.ifEmpty { "unknown" }
    }
}
