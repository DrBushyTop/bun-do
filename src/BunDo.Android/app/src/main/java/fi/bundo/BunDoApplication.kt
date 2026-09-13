package fi.bundo

import android.app.Application
import fi.bundo.data.AccountStore
import fi.bundo.data.AccountData
import fi.bundo.identity.SharedTokens
import fi.bundo.identity.createTokenProvider
import fi.bundo.identity.IdentityEndpoint
import kotlinx.coroutines.CancellationException

class BunDoApplication : Application() {
    val accounts by lazy { AccountStore(this) }
    internal val tokens by lazy { SharedTokens(createTokenProvider(this)) }
    internal suspend fun <T> withAccountToken(data: AccountData, operation: suspend (String) -> T): T {
        val request = accounts.authentication.beginRefresh()
        fun checkCurrent() {
            data.lease.check()
            if (accounts.active.value !== data || !accounts.authentication.isCurrent(request) ||
                request.expected != data.identity) throw CancellationException("Account session ended")
        }
        checkCurrent()
        check(tokens.restore())
        val token = tokens.refresh()
        checkCurrent()
        check(IdentityEndpoint(tokens.issuer).verify(token) == data.identity)
        checkCurrent()
        return operation(token).also { checkCurrent() }
    }
    val inbox get() = checkNotNull(accounts.active.value).inbox
    val voice get() = checkNotNull(accounts.active.value).voice
}
