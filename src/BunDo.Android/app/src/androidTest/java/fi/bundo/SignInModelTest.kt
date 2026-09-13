package fi.bundo

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.identity.SignInModel
import fi.bundo.identity.TokenProvider
import fi.bundo.identity.ValidatedIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import fi.bundo.data.AccountStore
import kotlinx.coroutines.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SignInModelTest {
    @Test fun householdResponseCannotCrossAnAccountSwitch() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val store = AccountStore(application, "household-session-test-${UUID.randomUUID()}")
        val identity = ValidatedIdentity("urn:bun-do:local", "alice")
        store.authentication.accept(store.authentication.beginSignIn(), identity)
        store.unlock(identity, UUID.randomUUID().toString())
        val data = store.active.value!!
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        var applied = false
        try {
            val model = withContext(Dispatchers.Main) {
                SignInModel(application, FakeTokens(cached = true), { identity }, store)
            }
            val operation = launch(Dispatchers.Main) {
                model.withAccountToken(data) { _, _ ->
                    started.complete(Unit)
                    response.await()
                }
                applied = true
            }
            started.await()
            withContext(Dispatchers.Main) {
                store.authentication.signOut()
                store.lockNow()
            }
            response.complete(Unit)
            operation.join()
            assertTrue(operation.isCancelled)
            assertFalse(applied)
        } finally { store.signOut(delete = true) }
    }

    @Test fun failedInitialVerificationThenRefreshShowsTheVerifiedAccount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val provider = FakeTokens()
            var attempts = 0
            val model = SignInModel(ApplicationProvider.getApplicationContext<Application>(), provider, verifyIdentity = {
                if (++attempts == 1) throw java.io.IOException("Synthetic unavailable API")
                ValidatedIdentity(provider.issuer, "alice")
            })
            assertFalse(model.busy)
            model.signIn(Activity(), "Alice")
            assertEquals(R.string.identity_api_unavailable, model.status)
            assertTrue(model.hasAccount)
            assertEquals("", model.selectedAccount)

            model.refresh()
            assertEquals(R.string.identity_refreshed, model.status)
            assertEquals("Alice", model.selectedAccount)

            model.signOut()
            assertEquals("", model.selectedAccount)
            assertFalse(model.hasAccount)
            assertEquals(R.string.identity_signed_out, model.status)
        }
    }

    private class FakeTokens(private val cached: Boolean = false) : TokenProvider {
        override val issuer = "urn:bun-do:local"
        override val choices = listOf("Alice", "Bob")
        override suspend fun restore() = cached
        override suspend fun signIn(activity: Activity, choice: String?) = "synthetic-token"
        override suspend fun refresh() = "synthetic-refresh"
        override suspend fun signOut() = Unit
    }
}
