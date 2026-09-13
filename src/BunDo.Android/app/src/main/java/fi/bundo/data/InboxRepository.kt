package fi.bundo.data

import androidx.room.withTransaction
import java.util.UUID

object InboxLimits {
    const val TITLE = 160
    const val DESCRIPTION = 4_000

    fun length(text: String): Int = text.codePointCount(0, text.length)

    fun valid(title: String, description: String): Boolean =
        title.isNotBlank() && length(title) <= TITLE && length(description) <= DESCRIPTION
}

class InboxRepository(private val database: InboxDatabase, private val lease: DataLease = DataLease()) : TaskEditorRepository {
    private val dao = database.inbox()
    override val tasks = dao.observeTasks()
    override val drafts = dao.observeDrafts()

    override suspend fun draft(key: String): EditorDraft = lease.access { dao.draft(key) ?: if (key == NEW_DRAFT) {
        EditorDraft(key)
    } else {
        val task = checkNotNull(dao.task(key)) { "Inbox task does not exist" }
        EditorDraft(key, task.title, task.description)
    } }

    override suspend fun saveDraft(draft: EditorDraft) = lease.access {
        // Invalid task content can still be recovered as a draft.
        dao.saveDraft(draft.copy(savedAt = System.currentTimeMillis()))
    }

    override suspend fun commit(draft: EditorDraft): String = lease.access {
        require(InboxLimits.valid(draft.title, draft.description)) { "Invalid inbox task" }
        database.withTransaction {
            lease.check()
            val now = System.currentTimeMillis()
            val isNew = draft.key == NEW_DRAFT
            val id = if (isNew) UUID.randomUUID().toString() else draft.key
            if (isNew) {
                dao.insertTask(
                    InboxTask(id, draft.title, draft.description, draft.title, draft.description, now, now),
                )
            } else {
                check(dao.editTask(id, draft.title, draft.description, now) == 1)
            }
            dao.insertIntent(
                InboxIntent(
                    taskId = id,
                    kind = if (isNew) "CaptureInboxTask" else "EditInboxTask",
                    title = draft.title,
                    description = draft.description,
                    createdAt = now,
                ),
            )
            dao.deleteDraft(draft.key)
            id
        }
    }

    companion object {
        const val NEW_DRAFT = "new"
    }
}
