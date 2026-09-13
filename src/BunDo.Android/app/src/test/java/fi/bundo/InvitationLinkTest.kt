package fi.bundo

import fi.bundo.household.InvitationLink
import org.junit.Assert.*
import org.junit.Test

class InvitationLinkTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val fragment = "$id/$id/$id/${"A".repeat(64)}"
    @Test fun acceptsOnlyKnownDestinationsAndFragmentSecrets() {
        assertNotNull(InvitationLink.parse("bundo://join#$fragment"))
        assertNotNull(InvitationLink.parse("https://func-bun-do-dev-qrquvcgmhocc6.azurewebsites.net/api/join#$fragment"))
        listOf("https://evil.example/api/join", "bundo://join/", "bundo://join?secret=x", "bundo://user@join",
            "bundo://join:443", "http://func-bun-do-dev-qrquvcgmhocc6.azurewebsites.net/api/join").forEach {
            assertNull(InvitationLink.parse("$it#$fragment"))
        }
        assertNull(InvitationLink.parse("bundo://join#1-1-1-1-1/$id/$id/${"A".repeat(64)}"))
        assertNull(InvitationLink.parse("bundo://join#$fragment/extra"))
        assertNull(InvitationLink.parse("bundo://join#${fragment.lowercase()}"))
    }
}
