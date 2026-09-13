package fi.bundo.identity

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fi.bundo.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import fi.bundo.BunDoApplication
import fi.bundo.data.AccountStore
import fi.bundo.data.AccountData

/** Both providers use the same API-validated identity and request-generation guard. */
class SignInModel internal constructor(
    application: Application,
    private val provider: TokenProvider,
    private val verifyIdentity: suspend (String) -> ValidatedIdentity,
    private val accounts: AccountStore? = null,
) : AndroidViewModel(application) {
    private constructor(application: Application, provider: TokenProvider) :
        this(application, provider, IdentityEndpoint(provider.issuer)::verify, (application as BunDoApplication).accounts)

    constructor(application: Application) : this(application, (application as BunDoApplication).tokens)

    var status by mutableStateOf(R.string.identity_loading)
        private set
    var busy by mutableStateOf(true)
        private set
    var hasAccount by mutableStateOf(false)
        private set
    var errorCode by mutableStateOf("")
        private set
    var selectedAccount by mutableStateOf("")
        private set
    val choices: List<String> get() = provider.choices
    private val session = accounts?.authentication ?: VerifiedSession()
    private var requestedChoice = ""
    private var authJob: Job? = null
    var registrationChoices by mutableStateOf<List<String>>(emptyList())
        private set
    private var ready = false

    /** Network features share this provider and the account generation, never a second sign-in session. */
    suspend fun <T> withAccountToken(data: AccountData, operation: suspend (String, String) -> T): T {
        check(ready && hasAccount && !busy)
        val identity = checkNotNull(data.identity)
        val registration = checkNotNull(data.registrationId)
        val request = session.beginRefresh()
        fun checkCurrent() {
            data.lease.check()
            if (!session.isCurrent(request) || accounts?.active?.value !== data || request.expected != identity)
                throw CancellationException("Account session ended")
        }
        checkCurrent()
        val token = provider.refresh()
        checkCurrent()
        check(verifyIdentity(token) == identity) { "Account changed during refresh" }
        checkCurrent()
        val result = operation(token, registration)
        checkCurrent()
        return result
    }

    init {
        viewModelScope.launch {
            try {
                hasAccount = provider.restore()
                if (accounts?.credentialsNeedRemoval == true) {
                    provider.signOut()
                    accounts.credentialsRemoved()
                    hasAccount = false
                }
                ready = true
                status = if (hasAccount) R.string.identity_cached else R.string.identity_ready
            } catch (_: Exception) {
                status = R.string.identity_failed
            } finally { busy = false }
        }
    }

    fun signIn(activity: Activity, choice: String? = null) {
        if (!ready || busy) return
        val request = session.beginSignIn()
        accounts?.lockNow()
        requestedChoice = choice.orEmpty()
        selectedAccount = ""
        busy = true
        errorCode = ""
        status = R.string.identity_browser
        authJob = viewModelScope.launch {
            try {
                accounts?.signOut()
                if (hasAccount) provider.signOut()
                hasAccount = false
                val token = provider.signIn(activity, choice)
                if (!session.isCurrent(request)) return@launch
                hasAccount = true
                verify(token, request, false)
            } catch (error: Exception) {
                fail(error, request)
            } finally { if (session.isCurrent(request)) busy = false }
        }
    }

    fun refresh(revokeRegistration: String? = null, replaceRetired: Boolean = false) {
        if (!ready || busy || !hasAccount) return
        val request = session.beginRefresh()
        busy = true
        errorCode = ""
        status = R.string.identity_refreshing
        authJob = viewModelScope.launch {
            try { verify(provider.refresh(), request, true, revokeRegistration, replaceRetired) }
            catch (error: Exception) { fail(error, request) }
            finally { if (session.isCurrent(request)) busy = false }
        }
    }

    fun signOut(delete: Boolean = false) {
        session.signOut()
        authJob?.cancel()
        accounts?.lockNow()
        requestedChoice = ""
        selectedAccount = ""
        registrationChoices = emptyList()
        busy = true
        errorCode = ""
        val request = session.beginRefresh()
        viewModelScope.launch {
            try {
                accounts?.signOut(delete)
                hasAccount = false
                status = R.string.identity_signed_out
                provider.signOut()
                accounts?.credentialsRemoved()
            } catch (error: Exception) { fail(error, request) }
            finally { busy = false }
        }
    }

    private suspend fun verify(token: String, request: VerifiedSession.Request, refreshed: Boolean,
        revokeRegistration: String? = null, replaceRetired: Boolean = false) {
        if (!session.isCurrent(request)) return
        status = R.string.identity_verifying
        val identity = verifyIdentity(token)
        if (!session.isCurrent(request)) return
        check(request.expected == null || request.expected == identity) { "Account changed during refresh" }
        if (accounts != null) {
            val installation = accounts.registrationInstallation(identity, replaceRetired)
            if (!session.isCurrent(request)) return
            val registration = IdentityEndpoint(provider.issuer).register(token, installation, revokeRegistration)
            if (!session.isCurrent(request)) return
            if (accounts.active.value?.identity != identity || accounts.active.value?.registrationId != registration) {
                accounts.unlock(identity, registration) { session.isCurrent(request) }
            }
        }
        if (session.accept(request, identity)) {
            registrationChoices = emptyList()
            selectedAccount = requestedChoice
            status = if (refreshed) R.string.identity_refreshed else R.string.identity_verified
        }
    }

    private fun fail(error: Exception, request: VerifiedSession.Request) {
        if (error is CancellationException) throw error
        if (!session.isCurrent(request)) return
        status = when (error) {
            is RegistrationRejected -> R.string.identity_registration_failed
            is SignInCancelled -> R.string.identity_cancelled
            is IdentityRejected -> R.string.identity_api_rejected
            is TokenFailure -> R.string.identity_failed
            else -> R.string.identity_api_unavailable
        }
        errorCode = when (error) {
            is RegistrationRejected -> error.code
            is IdentityRejected -> error.status.toString()
            is TokenFailure -> error.code
            else -> ""
        }
        registrationChoices = (error as? RegistrationRejected)?.active.orEmpty()
    }
}
