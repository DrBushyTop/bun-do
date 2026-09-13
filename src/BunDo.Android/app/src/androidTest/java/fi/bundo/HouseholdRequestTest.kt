package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.bundo.household.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HouseholdRequestTest {
    @Test fun eachMembershipActionUsesItsOwnCanonicalVersion() {
        val member = HouseholdMember("member", "3", false, "Bob")
        val invitation = HouseholdInvitation("invitation", "2", "Pending", "", "123456", "Bob")
        val home = Household("home", "epoch", "0", "owner", true, true, "Home", false,
            listOf(member), listOf(invitation))
        assertEquals("0", membershipRequest("transfer", home, member = member).getString("expectedVersion"))
        assertEquals("member", membershipRequest("transfer", home, member = member).getString("memberId"))
        assertEquals("0", membershipRequest("delete", home).getString("expectedVersion"))
        for (action in listOf("remove", "leave"))
            assertEquals("3", membershipRequest(action, home, member = member).getString("expectedVersion"))
        for (action in listOf("approve", "cancel"))
            assertEquals("2", membershipRequest(action, home, invitation = invitation).getString("expectedVersion"))
    }
}
