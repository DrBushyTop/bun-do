package fi.bundo.identity

import android.app.Activity

/** Only token acquisition varies. API verification and session transitions stay shared. */
internal interface TokenProvider {
    val issuer: String
    val choices: List<String> get() = emptyList()
    suspend fun restore(): Boolean
    suspend fun signIn(activity: Activity, choice: String?): String
    suspend fun refresh(): String
    suspend fun signOut()
}
internal class SignInCancelled : Exception()
internal class TokenFailure(val code: String, val retryable: Boolean = false) : Exception()

internal suspend fun TokenProvider.requestToken(): String {
    if (!restore()) throw TokenFailure("no_current_account")
    return refresh()
}
