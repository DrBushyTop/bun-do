package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.identity.ValidatedIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WelcomeStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alice = ValidatedIdentity("urn:bun-do:local", "alice")
    private val bob = alice.copy(subject = "bob")
    private fun link() = "bundo://join#${UUID.randomUUID()}/${UUID.randomUUID()}/${UUID.randomUUID()}/${"A".repeat(64)}"

    private fun test(existing: Boolean = false, block: suspend (String, WelcomeStore) -> Unit) = runBlocking {
        val name = "welcome-test-${UUID.randomUUID()}"
        val store = WelcomeStore(context, name, existing)
        try { block(name, store) }
        finally {
            store.awaitSaved(); store.close()
            File(context.noBackupFilesDir, name).deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("bundo.$name") }
        }
    }

    @Test fun firstLaunchSurvivesRestartEvenAfterAnonymousDatabaseIsCreated() = test { name, store ->
        assertTrue(store.state.value.visible)
        val reopened = WelcomeStore(context, name, existingInstall = true)
        try { assertEquals(store.state.value, reopened.state.value) } finally { reopened.close() }
    }

    @Test fun existingInstallationStartsAtInboxAndCanOpenSetupLater() = test(existing = true) { name, store ->
        assertFalse(store.state.value.visible)
        store.reopen(); store.awaitSaved()
        val reopened = WelcomeStore(context, name, true)
        try {
            assertTrue(reopened.state.value.visible)
            assertEquals(WelcomeStep.FAMILY, reopened.state.value.step)
        } finally { reopened.close() }
    }

    @Test fun encryptedInvitationAndEditsSurviveRestartAndStayOutOfPlaintext() = test { name, store ->
        val invitation = link()
        store.incoming(invitation)
        repeat(30) { index -> store.update { it.copy(displayName = "Private name $index") } }
        store.awaitSaved()
        val reopened = WelcomeStore(context, name, true)
        try {
            assertEquals(invitation, reopened.state.value.link)
            assertEquals("Private name 29", reopened.state.value.displayName)
            assertEquals(WelcomeStep.JOIN, reopened.state.value.step)
            val bytes = File(context.noBackupFilesDir, "$name/state").readText()
            assertFalse(bytes.contains("A".repeat(64)))
            assertFalse(bytes.contains("Private name"))
        } finally { reopened.close() }
    }

    @Test fun firstAccountBindingPreservesInvitationButSwitchAndSignOutClearPrivateSetup() = test { _, store ->
        val invitation = link()
        store.incoming(invitation)
        store.bind(alice)
        assertEquals(invitation, store.state.value.link)
        store.update { it.copy(name = "Alice family", homeId = "private-home", displayName = "Alice") }
        val createId = store.state.value.createId
        store.bind(alice)
        assertEquals(createId, store.state.value.createId)
        store.bind(bob)
        assertEquals("", store.state.value.homeId)
        assertEquals("", store.state.value.link)
        assertEquals("", store.state.value.name)
        assertNotEquals(createId, store.state.value.createId)
        store.update { it.copy(displayName = "Bob") }
        store.bind(null)
        assertEquals("", store.state.value.displayName)
        assertEquals("", store.state.value.owner)
    }

    @Test fun dismissalKeepsPendingJoinForSettingsAndRejectsUntrustedLinks() = test { name, store ->
        store.incoming(link())
        store.update { it.copy(step = WelcomeStep.HOME, homeId = "pending-home", link = "") }
        store.dismiss(); store.awaitSaved()
        val reopened = WelcomeStore(context, name, true)
        try {
            assertFalse(reopened.state.value.visible)
            reopened.incoming("https://example.com/join#secret")
            assertFalse(reopened.state.value.visible)
            reopened.reopen(); reopened.awaitSaved()
            assertEquals("pending-home", reopened.state.value.homeId)
            assertEquals(WelcomeStep.HOME, reopened.state.value.step)
        } finally { reopened.close() }
    }

    @Test fun damagedSetupNeverTouchesExistingTasks() = test(existing = true) { name, store ->
        store.awaitSaved()
        File(context.noBackupFilesDir, "$name/state").writeText("not encrypted state")
        val reopened = WelcomeStore(context, name, true)
        try { assertFalse(reopened.state.value.visible) } finally { reopened.close() }
    }

    @Test fun oldCreateAndRedeemRepliesCannotReplaceNewInvitationOrAccount() = test { _, store ->
        store.bind(alice)
        store.update { it.copy(step = WelcomeStep.CREATE, name = "Family") }
        val create = store.state.value
        val invitationA = link()
        store.incoming(invitationA)
        store.accepted(create, "old-created-home")
        assertEquals(invitationA, store.state.value.link)
        val redeemA = store.state.value
        val invitationB = link()
        store.incoming(invitationB)
        store.accepted(redeemA, "old-invited-home")
        assertEquals(invitationB, store.state.value.link)
        assertEquals(WelcomeStep.JOIN, store.state.value.step)
        val redeemB = store.state.value
        store.bind(bob)
        store.accepted(redeemB, "alice-home")
        assertEquals("", store.state.value.homeId)
        store.update { it.copy(step = WelcomeStep.CREATE) }
        store.accepted(store.state.value, "bob-home")
        assertEquals("bob-home", store.state.value.homeId)
    }
}
