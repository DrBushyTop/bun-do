package fi.bundo.identity

import android.app.Activity
import android.app.Application
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalServiceException
import fi.bundo.R
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun createTokenProvider(application: Application): TokenProvider = MicrosoftTokens(application)

private class MicrosoftTokens(private val application: Application) : TokenProvider {
    override val issuer = IdentityEndpoint.ISSUER
    private lateinit var client: ISingleAccountPublicClientApplication
    private var account: IAccount? = null

    override suspend fun restore(): Boolean {
        client = suspendCancellableCoroutine { continuation ->
            PublicClientApplication.createSingleAccountPublicClientApplication(application, R.raw.msal_config,
                object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                    override fun onCreated(app: ISingleAccountPublicClientApplication) {
                        if (continuation.isActive) continuation.resume(app)
                    }
                    override fun onError(exception: MsalException) {
                        if (continuation.isActive) continuation.resumeWithException(microsoftTokenFailure(exception))
                    }
                })
        }
        account = suspendCancellableCoroutine { continuation ->
            client.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                override fun onAccountLoaded(activeAccount: IAccount?) {
                    if (continuation.isActive) continuation.resume(activeAccount)
                }
                override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) = Unit
                override fun onError(exception: MsalException) {
                    if (continuation.isActive) continuation.resumeWithException(microsoftTokenFailure(exception))
                }
            })
        }
        return account != null
    }

    override suspend fun signIn(activity: Activity, choice: String?): String = suspendCancellableCoroutine { continuation ->
        client.signIn(SignInParameters.builder().withActivity(activity).withScopes(SCOPES)
            .withPrompt(Prompt.SELECT_ACCOUNT).withCallback(object : AuthenticationCallback {
                override fun onSuccess(result: IAuthenticationResult) {
                    if (continuation.isActive) {
                        account = result.account
                        continuation.resume(result.accessToken)
                    }
                }
                override fun onError(exception: MsalException) {
                    if (continuation.isActive) continuation.resumeWithException(microsoftTokenFailure(exception))
                }
                override fun onCancel() {
                    if (continuation.isActive) continuation.resumeWithException(SignInCancelled())
                }
            }).build())
    }

    override suspend fun refresh(): String = suspendCancellableCoroutine { continuation ->
        val current = account
        if (current == null) {
            continuation.resumeWithException(TokenFailure("no_current_account"))
            return@suspendCancellableCoroutine
        }
        client.acquireTokenSilentAsync(AcquireTokenSilentParameters.Builder().forAccount(current)
            .fromAuthority("https://login.microsoftonline.com/consumers").withScopes(SCOPES).forceRefresh(true)
            .withCallback(object : SilentAuthenticationCallback {
                override fun onSuccess(result: IAuthenticationResult) {
                    if (continuation.isActive) continuation.resume(result.accessToken)
                }
                override fun onError(exception: MsalException) {
                    if (continuation.isActive) continuation.resumeWithException(microsoftTokenFailure(exception))
                }
            }).build())
    }

    override suspend fun signOut(): Unit = suspendCancellableCoroutine { continuation ->
        client.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() {
                account = null
                if (continuation.isActive) continuation.resume(Unit)
            }
            override fun onError(exception: MsalException) {
                if (continuation.isActive) continuation.resumeWithException(microsoftTokenFailure(exception))
            }
        })
    }

    companion object {
        private val SCOPES = listOf("api://d33b7867-41ed-45c3-805a-b5e841323d20/access_as_user")
    }
}

internal fun microsoftTokenFailure(exception: MsalException) = TokenFailure(
    exception.errorCode.orEmpty().filter { it.isLetterOrDigit() || it == '_' }.take(80),
    retryable = when (exception) {
        is MsalClientException -> exception.errorCode in setOf(
            MsalClientException.DEVICE_NETWORK_NOT_AVAILABLE, MsalClientException.IO_ERROR)
        is MsalServiceException -> exception.httpStatusCode == 429 || exception.httpStatusCode >= 500 ||
            exception.errorCode in setOf(MsalServiceException.SERVICE_NOT_AVAILABLE, MsalServiceException.REQUEST_TIMEOUT)
        else -> false
    },
)
