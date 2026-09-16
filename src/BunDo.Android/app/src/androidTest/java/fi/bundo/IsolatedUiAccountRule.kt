package fi.bundo

import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.identity.ValidatedIdentity
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.UUID

/** Native UI tests must not replace a person's unfinished draft or append fixtures to their inbox. */
class IsolatedUiAccountRule : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BunDoApplication
            val accounts = app.accounts
            val previousWelcome = app.welcome.state.value
            app.welcome.dismiss()
            val previousIdentity = accounts.active.value?.identity
            val previousRegistration = accounts.active.value?.registrationId
            val previousRemoval = accounts.credentialsNeedRemoval
            val fixture = ValidatedIdentity("urn:bun-do:ui-test", UUID.randomUUID().toString())
            runBlocking {
                accounts.authentication.accept(accounts.authentication.beginSignIn(), fixture)
                accounts.unlock(fixture, UUID.randomUUID().toString())
            }
            try { base.evaluate() }
            finally {
                runBlocking {
                    check(accounts.active.value?.identity == fixture)
                    accounts.authentication.signOut()
                    accounts.signOut(delete = true)
                    if (previousIdentity != null) {
                        accounts.authentication.accept(accounts.authentication.beginSignIn(), previousIdentity)
                        accounts.unlock(previousIdentity, checkNotNull(previousRegistration))
                    } else if (!previousRemoval) accounts.credentialsRemoved()
                    app.welcome.update { previousWelcome }
                    app.welcome.awaitSaved()
                }
            }
        }
    }
}
