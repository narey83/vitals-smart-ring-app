package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest logic, checked against the ring's real R11M.plist: an empty general offer and a
 * V2.34 gated to a MAC allowlist. The point of these is that a ring not on the list is told it is
 * current rather than offered a flash it must not take.
 */
class FirmwareUpdateTest {

    // Trimmed from the real manifest: empty general url, a MAC-gated V2.34, two addresses allowed.
    private val manifest = """
        <plist><dict>
        <key>mac_url</key><string>https://staticpage.ycaviation.com/firmware/R11M-APP-DFU-KEY1-V2.34.zip</string>
        <key>mac_bNo</key><string>2</string>
        <key>mac_sNo</key><string>34</string>
        <key>mac</key><string>07:29:00:13:D1:BC,AA:BB:CC:DD:EE:FF</string>
        <key>url</key><string></string>
        <key>bNo</key><string>1</string>
        <key>sNo</key><string>0</string>
        </dict></plist>
    """.trimIndent()

    @Test fun `a higher minor is newer, an equal or lower one is not`() {
        assertTrue(FirmwareUpdate.isNewer(2 to 34, "V2.32"))
        assertFalse(FirmwareUpdate.isNewer(2 to 32, "V2.32"))
        assertFalse(FirmwareUpdate.isNewer(2 to 30, "2.32"))
        assertTrue(FirmwareUpdate.isNewer(3 to 0, "V2.99"))
    }

    @Test fun `a version that will not parse is never treated as newer`() {
        assertFalse(FirmwareUpdate.isNewer(9 to 9, "unknown"))
    }

    @Test fun `the plist flattens to its key-string pairs`() {
        val map = FirmwareUpdate.parsePlist(manifest)
        assertEquals("2", map["mac_bNo"])
        assertEquals("", map["url"])
    }

    @Test fun `a ring on the allowlist is offered the MAC-gated build`() {
        val choice = FirmwareUpdate.chooseUpgrade(FirmwareUpdate.parsePlist(manifest), "AA:BB:CC:DD:EE:FF", "V2.32")
        assertEquals("2.34", choice?.first)
        assertTrue(choice!!.second.endsWith("V2.34.zip"))
    }

    @Test fun `a ring off the allowlist is told it is current, because the general offer is empty`() {
        assertNull(FirmwareUpdate.chooseUpgrade(FirmwareUpdate.parsePlist(manifest), "11:22:33:44:55:66", "V2.32"))
    }

    @Test fun `an allowlisted ring already on that build is not offered it again`() {
        assertNull(FirmwareUpdate.chooseUpgrade(FirmwareUpdate.parsePlist(manifest), "AA:BB:CC:DD:EE:FF", "V2.34"))
    }
}
