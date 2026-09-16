package fi.bundo

import fi.bundo.data.WelcomeProgress
import fi.bundo.data.WelcomeStep
import fi.bundo.household.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WelcomeRecoveryTest {
    private val homeId = UUID.randomUUID().toString()
    private val epoch = UUID.randomUUID().toString()
    private val oldId = UUID.randomUUID().toString()
    private val newId = UUID.randomUUID().toString()
    private fun link(id: String, version: String = epoch) = "bundo://join#$homeId/$version/$id/${"A".repeat(64)}"
    private fun home(phase: String = "Pending", id: String = oldId) = Household(homeId, epoch, "v1", "me", false,
        false, "", false, emptyList(), listOf(HouseholdInvitation(id, "v1", phase, "", "123456", "")))

    @Test fun aNewInviteToTheSameFamilyIsNotAnOldCandidateRecord() {
        for (phase in listOf("Pending", "Cancelled", "Expired")) {
            val progress = WelcomeProgress(step = WelcomeStep.JOIN, link = link(newId))
            assertNull(recoverWelcomeHome(progress, listOf(home(phase))))
            assertEquals(homeId, recoverWelcomeHome(progress, listOf(home(phase).copy(active = true)))?.id)
        }
    }

    @Test fun lostRedemptionReplyRecoversOnlyItsOwnInvitationAndEpoch() {
        val progress = WelcomeProgress(step = WelcomeStep.JOIN, link = link(oldId))
        assertEquals(homeId, recoverWelcomeHome(progress, listOf(home()))?.id)
        assertNull(recoverWelcomeHome(progress.copy(link = link(oldId, UUID.randomUUID().toString())), listOf(home())))
    }

    @Test fun lostCreateReplyAndSavedPendingJoinRecoverByStableIds() {
        assertEquals(homeId, recoverWelcomeHome(WelcomeProgress(step = WelcomeStep.CREATE, createId = homeId), listOf(home()))?.id)
        assertEquals(homeId, recoverWelcomeHome(WelcomeProgress(step = WelcomeStep.HOME, homeId = homeId), listOf(home()))?.id)
        assertNull(recoverWelcomeHome(WelcomeProgress(step = WelcomeStep.FAMILY), listOf(home())))
    }
}
