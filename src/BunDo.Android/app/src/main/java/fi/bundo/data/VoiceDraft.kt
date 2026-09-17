package fi.bundo.data

import org.json.JSONArray
import org.json.JSONObject

/** Durable review text. No shared task exists until the user accepts this draft. */
data class VoiceDraft(val transcript: String, val title: String, val description: String = "", val items: List<String> = emptyList(), val revisionInstruction: String = "") {
    // Preserve raw lines while typing; trim only when displaying or submitting a complete draft.
    fun normalized() = copy(title = title.trim(), items = items.map(String::trim).filter(String::isNotEmpty))
    val valid: Boolean get() = InboxLimits.valid(title, description) && (items.isEmpty() || SharedSplitPreview.valid(items))
    fun json(): String = JSONObject().put("transcript", transcript).put("title", title)
        .put("description", description).put("items", JSONArray(items)).put("revisionInstruction", revisionInstruction).toString()
    companion object {
        fun from(text: String): VoiceDraft {
            val title = text.take(text.offsetByCodePoints(0, minOf(InboxLimits.length(text), InboxLimits.TITLE)))
                .replace('\n', ' ').replace('\r', ' ')
            return VoiceDraft(text, title, if (title == text) "" else text)
        }
        fun parse(json: String): VoiceDraft {
            val value = JSONObject(json)
            val rows = value.getJSONArray("items")
            return VoiceDraft(value.getString("transcript"), value.getString("title"), value.getString("description"),
                (0 until rows.length()).map(rows::getString), value.optString("revisionInstruction"))
        }
    }
}

internal fun VoiceRecording.recoveryTexts(prefix: String = ""): List<RecoveryText> {
    if (state != "REVIEW" || review == null) return emptyList()
    val draft = VoiceDraft.parse(review)
    var remaining = listOf(draft.title, draft.description, draft.items.joinToString("\n"), draft.transcript, draft.revisionInstruction)
        .filter(String::isNotBlank).distinct().joinToString("\n\n")
    val result = mutableListOf<RecoveryText>()
    while (remaining.isNotEmpty()) {
        val end = remaining.offsetByCodePoints(0, minOf(InboxLimits.length(remaining), InboxLimits.DESCRIPTION))
        result += RecoveryText("${prefix}voice-draft:$id:${result.size}", "", remaining.substring(0, end), createdAt)
        remaining = remaining.substring(end)
    }
    return result
}
