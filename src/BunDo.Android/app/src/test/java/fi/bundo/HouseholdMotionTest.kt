package fi.bundo

import fi.bundo.ui.HouseholdMotion
import org.junit.Assert.*
import org.junit.Test

class HouseholdMotionTest {
    @Test fun claimsDoNotReplayOnRecompositionOrAfterAnOffscreenReturn() {
        val motion = HouseholdMotion()
        assertTrue(motion.observeClaim("one", "alice"))
        assertFalse(motion.observeClaim("one", "alice"))
        assertTrue(motion.observeClaim("two", "bob"))
        assertFalse(motion.observeClaim("one", "alice"))
        assertFalse(motion.observeClaim("one", null))
        assertTrue(motion.observeClaim("one", "alice"))
    }

    @Test fun systemReducedMotionOverridesPreferenceWithoutLosingStaticClaimState() {
        val motion = HouseholdMotion()
        motion.preferred = true; motion.systemEnabled = false
        assertFalse(motion.enabled)
        assertTrue(motion.observeClaim("one", "alice"))
        motion.systemEnabled = true
        assertTrue(motion.enabled)
        assertFalse(motion.observeClaim("one", "alice"))
        motion.preferred = false
        assertFalse(motion.enabled)
    }
}
