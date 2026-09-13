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

@RunWith(AndroidJUnit4::class)
class SignInModelTest {
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

    private class FakeTokens : TokenProvider {
        override val issuer = "urn:bun-do:local"
        override val choices = listOf("Alice", "Bob")
        override suspend fun restore() = false
        override suspend fun signIn(activity: Activity, choice: String?) = "synthetic-token"
        override suspend fun refresh() = "synthetic-refresh"
        override suspend fun signOut() = Unit
    }
}
