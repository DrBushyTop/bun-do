package fi.bundo.identity

/** Account identity comes only from a successful authenticated API response. */
data class ValidatedIdentity(val issuer: String, val subject: String)

/** A request carries its generation through token acquisition and API verification. */
class VerifiedSession {
    class Request internal constructor(val generation: Long, val expected: ValidatedIdentity?)
    var identity: ValidatedIdentity? = null
        private set
    private var generation = 0L

    @Synchronized fun beginSignIn(): Request {
        generation++
        identity = null
        return Request(generation, null)
    }

    @Synchronized fun beginRefresh(): Request = Request(generation, identity)
    @Synchronized fun isCurrent(request: Request): Boolean = request.generation == generation

    @Synchronized fun accept(request: Request, validated: ValidatedIdentity): Boolean {
        if (!isCurrent(request)) return false
        check(request.expected == null || request.expected == validated) { "Account changed during refresh" }
        identity = validated
        return true
    }

    @Synchronized fun signOut() {
        generation++
        identity = null
    }
}
