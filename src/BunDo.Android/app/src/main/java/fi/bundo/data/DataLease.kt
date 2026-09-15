package fi.bundo.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Revocation is immediate. Closing waits for already-started local transactions. */
class DataLease(val owner: String = "anonymous", val generation: String = UUID.randomUUID().toString()) {
    private val mutex = Mutex()
    private val callbackGate = Any()
    @Volatile var active = true
        private set

    fun check() {
        if (!active) throw CancellationException("Account session ended")
    }

    suspend fun <T> access(operation: suspend () -> T): T = mutex.withLock {
        check()
        operation()
    }

    /** Serialize short external effects with revocation, not just their preceding check. */
    fun <T> whileActive(operation: () -> T): T = synchronized(callbackGate) { check(); operation() }
    fun revoke() = synchronized(callbackGate) { active = false }
    suspend fun drain() { mutex.withLock { } }
}
