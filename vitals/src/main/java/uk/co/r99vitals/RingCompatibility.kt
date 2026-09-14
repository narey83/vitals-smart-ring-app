package uk.co.r99vitals

/**
 * Whether a connected ring is one the firmware updater may safely touch.
 *
 * Flashing is the one operation that can brick the ring, and the images are specific to this ring
 * (the JieLi AC632N R11M family — see PROTOCOL.md and [FirmwareUpdate]). Writing one to a device
 * that is not this ring, or that runs firmware from outside the family the images belong to, is
 * exactly how a wrong image ends up on the wrong hardware. So the update actions are gated on both:
 * the ring must identify as an R99, and its current firmware must be a version this app recognises.
 *
 * This is a gate on *updating*, on top of two other layers: the app only connects at all to a
 * device that answers the R99 command channel (see VitalsActivity's pairing check), and the JieLi
 * library validates the image against the chip before it writes. This is the belt to those braces.
 */
object RingCompatibility {

    /**
     * The ring advertises its model in its name — this ring and its images are the R99 family. A
     * name that is present must look like an R99; a missing one does not block, because Android
     * often will not resolve a bonded device's name, and the firmware check below is the real gate
     * (a version is only ever read from a ring that has already answered the R99 command channel).
     */
    fun isSupportedRing(name: String?): Boolean =
        name.isNullOrBlank() || name.trim().startsWith("R99", ignoreCase = true)

    /**
     * Whether the ring's current firmware is one this app recognises and can update from: a
     * `Vx.yy` in this ring's own major family. Firmware outside it is left alone rather than
     * risked, since the images here are not known to fit it.
     */
    fun isUpdatableFirmware(firmware: String?): Boolean {
        val parts = firmware?.trim()?.trimStart('V', 'v')?.split('.')?.mapNotNull { it.toIntOrNull() }
        return parts != null && parts.size >= 2 && parts[0] == SUPPORTED_MAJOR
    }

    /** Both together: only then may the app fetch or flash firmware for this ring. */
    fun canUpdate(name: String?, firmware: String?): Boolean =
        isSupportedRing(name) && isUpdatableFirmware(firmware)

    /** Why an update is not offered, for the wearer — null when it is. */
    fun reason(name: String?, firmware: String?): String? = when {
        !isSupportedRing(name) -> "Firmware updates are only for R99 rings; this device reports \"${name ?: "unknown"}\"."
        !isUpdatableFirmware(firmware) -> "This ring's firmware (${firmware ?: "unknown"}) isn't one this app recognises, so it won't risk updating it."
        else -> null
    }

    /** The firmware major version this app's images are known to fit — the R99's V2 family. */
    private const val SUPPORTED_MAJOR = 2
}
