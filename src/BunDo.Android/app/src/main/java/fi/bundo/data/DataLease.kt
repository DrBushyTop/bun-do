package fi.bundo.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Revocation is immediate. Closing waits for already-started local transactions. */
class DataLease(val owner: String = "anonymous", val generation: String = UUID.randomUUID().toString()) {
    private val mutex = Mutex()
    @Volatile var active = true
        private set

    fun check() {
        if (!active) throw CancellationException("Account session ended")
    }

    suspend fun <T> access(operation: suspend () -> T): T = mutex.withLock {
        check()
        operation()
    }

    fun revoke() { active = false }
    suspend fun drain() { mutex.withLock { } }
}
