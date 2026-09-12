package fi.bundo

import android.app.Application
import fi.bundo.data.InboxDatabase
import fi.bundo.data.InboxRepository

class BunDoApplication : Application() {
    private val database by lazy { InboxDatabase.open(this) }
    val inbox by lazy { InboxRepository(database) }
}
