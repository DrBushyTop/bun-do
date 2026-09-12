package fi.bundo

import fi.bundo.data.InboxLimits
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxLimitsTest {
    @Test fun blankTitleIsNotATask() {
        assertFalse(InboxLimits.valid(" \n\t", "Description"))
    }

    @Test fun countsUnicodeScalarsRatherThanUtf16Units() {
        assertTrue(InboxLimits.valid("🐇".repeat(160), "ä".repeat(4_000)))
        assertFalse(InboxLimits.valid("🐇".repeat(161), ""))
        assertFalse(InboxLimits.valid("Task", "x".repeat(4_001)))
    }

    @Test fun bothLanguagesAndMultilineContentAreValid() {
        assertTrue(InboxLimits.valid("Järjestä varasto\nSort the shed", "Säilytä tämä teksti."))
    }
}
