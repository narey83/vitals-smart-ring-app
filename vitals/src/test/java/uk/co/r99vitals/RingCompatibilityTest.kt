package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that keeps a firmware write off anything but a supported R99 on recognised firmware —
 * the check that stands between "Update" and a bricked device.
 */
class RingCompatibilityTest {

    @Test fun `an R99 or unnamed device is supported, a differently-named one is not`() {
        assertTrue(RingCompatibility.isSupportedRing("R99 8D43"))
        assertTrue(RingCompatibility.isSupportedRing("r99"))
        assertFalse(RingCompatibility.isSupportedRing("Galaxy Buds"))
        // A bonded ring often reports no name on Android; the firmware check is the real gate, so
        // a missing name must not block a ring that has already proved itself on the command channel.
        assertTrue(RingCompatibility.isSupportedRing(null))
    }

    @Test fun `firmware in the V2 family is updatable, anything else is left alone`() {
        assertTrue(RingCompatibility.isUpdatableFirmware("V2.32"))
        assertTrue(RingCompatibility.isUpdatableFirmware("2.34"))
        assertFalse(RingCompatibility.isUpdatableFirmware("V3.0"))
        assertFalse(RingCompatibility.isUpdatableFirmware("V1.9"))
        assertFalse(RingCompatibility.isUpdatableFirmware("unknown"))
        assertFalse(RingCompatibility.isUpdatableFirmware(null))
    }

    @Test fun `only a supported ring on recognised firmware may be updated`() {
        assertTrue(RingCompatibility.canUpdate("R99 8D43", "V2.32"))
        assertTrue(RingCompatibility.canUpdate(null, "V2.32"))          // bonded ring, name unresolved
        assertFalse(RingCompatibility.canUpdate("R99 8D43", "V3.0"))    // unfamiliar firmware
        assertFalse(RingCompatibility.canUpdate("Some Watch", "V2.32")) // wrong device
        assertFalse(RingCompatibility.canUpdate(null, null))            // nothing known
    }

    @Test fun `the reason is null when updatable, and explains which check failed otherwise`() {
        assertNull(RingCompatibility.reason("R99 8D43", "V2.32"))
        assertTrue(RingCompatibility.reason("Pixel Buds", "V2.32")!!.contains("R99"))
        assertTrue(RingCompatibility.reason("R99 8D43", "V9.9")!!.contains("recognise"))
    }
}
