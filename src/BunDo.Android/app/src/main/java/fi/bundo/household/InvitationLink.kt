package fi.bundo.household

import java.net.URI
import java.util.UUID

/** Secrets are accepted only in a fragment and sent only to the fixed authenticated API. */
class InvitationLink private constructor(val workspace: String, val epoch: String, val invitation: String, val secret: String) {
    companion object {
        fun parse(value: String): InvitationLink? = runCatching {
            require(value.length <= 512)
            val uri = URI(value.trim())
            require(uri.scheme == "bundo" && uri.host == "join" && uri.rawPath.isNullOrEmpty() ||
                uri.scheme == "https" && uri.host == "func-bun-do-dev-qrquvcgmhocc6.azurewebsites.net" && uri.rawPath == "/api/join")
            require(uri.rawQuery == null && uri.userInfo == null && uri.port == -1)
            val parts = checkNotNull(uri.rawFragment).split('/')
            require(parts.size == 4)
            parts.take(3).forEach { require(UUID.fromString(it).toString() == it) }
            require(parts[3].length == 64 && parts[3].all { it in '0'..'9' || it in 'A'..'F' })
            InvitationLink(parts[0], parts[1], parts[2], parts[3])
        }.getOrNull()
    }
}
