package fi.bundo

import fi.bundo.identity.ValidatedIdentity
import fi.bundo.identity.VerifiedSession
import org.junit.Assert.*
import org.junit.Test

class VerifiedSessionTest {
    private val alice = ValidatedIdentity("urn:bun-do:local", "alice")
    private val bob = ValidatedIdentity("urn:bun-do:local", "bob")

    @Test fun lateAliceResponseCannotReplaceBob() {
        val session = VerifiedSession()
        val aliceRequest = session.beginSignIn()
        val bobRequest = session.beginSignIn()
        assertTrue(session.accept(bobRequest, bob))
        assertFalse(session.accept(aliceRequest, alice))
        assertEquals(bob, session.identity)
    }

    @Test fun signOutInvalidatesInFlightRefresh() {
        val session = VerifiedSession()
        session.accept(session.beginSignIn(), alice)
        val refresh = session.beginRefresh()
        session.signOut()
        assertFalse(session.accept(refresh, alice))
        assertNull(session.identity)
    }

    @Test fun refreshCannotSubstituteAnotherAccount() {
        val session = VerifiedSession()
        session.accept(session.beginSignIn(), alice)
        assertThrows(IllegalStateException::class.java) { session.accept(session.beginRefresh(), bob) }
        assertEquals(alice, session.identity)
    }

    @Test fun refreshFailureDoesNotDiscardValidatedOfflineIdentity() {
        val session = VerifiedSession()
        session.accept(session.beginSignIn(), alice)
        session.beginRefresh() // No successful response to accept.
        assertEquals(alice, session.identity)
    }

    @Test fun issuerIsPartOfIdentity() {
        val session = VerifiedSession()
        session.accept(session.beginSignIn(), alice)
        val otherIssuer = alice.copy(issuer = "https://another-issuer.example")
        assertThrows(IllegalStateException::class.java) { session.accept(session.beginRefresh(), otherIssuer) }
    }

    @Test fun sameAccountCanSignInAgainAfterSignOut() {
        val session = VerifiedSession()
        session.accept(session.beginSignIn(), alice)
        session.signOut()
        assertTrue(session.accept(session.beginSignIn(), alice))
    }
}
