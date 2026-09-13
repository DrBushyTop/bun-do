package fi.bundo.identity

import android.app.Activity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** UI and background sync share one provider; restore does not create another sign-in session. */
internal class SharedTokens(private val delegate: TokenProvider) : TokenProvider {
    private val gate = Mutex()
    private var restored: Boolean? = null
    override val issuer get() = delegate.issuer
    override val choices get() = delegate.choices
    override suspend fun restore(): Boolean = gate.withLock {
        restored ?: delegate.restore().also { restored = it }
    }
    override suspend fun signIn(activity: Activity, choice: String?): String = gate.withLock {
        delegate.signIn(activity, choice).also { restored = true }
    }
    override suspend fun refresh(): String = gate.withLock { delegate.refresh() }
    override suspend fun signOut() = gate.withLock { delegate.signOut(); restored = false }
}
