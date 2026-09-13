package fi.bundo

import android.app.Application
import fi.bundo.data.AccountStore

class BunDoApplication : Application() {
    val accounts by lazy { AccountStore(this) }
    val inbox get() = checkNotNull(accounts.active.value).inbox
    val voice get() = checkNotNull(accounts.active.value).voice
}
