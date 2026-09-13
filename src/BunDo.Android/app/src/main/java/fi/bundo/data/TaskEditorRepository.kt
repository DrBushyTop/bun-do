package fi.bundo.data

import kotlinx.coroutines.flow.Flow

/** Local editor durability is the same for the inbox and registered household commands. */
interface TaskEditorRepository {
    val tasks: Flow<List<InboxTask>>
    val drafts: Flow<List<EditorDraft>>
    suspend fun draft(key: String): EditorDraft
    suspend fun saveDraft(draft: EditorDraft)
    suspend fun commit(draft: EditorDraft): String
}
