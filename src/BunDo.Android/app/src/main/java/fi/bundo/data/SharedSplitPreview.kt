package fi.bundo.data

import org.json.JSONArray
import org.json.JSONObject

/** AI output stays a local editable draft; acceptance is the ordinary atomic split command. */
internal object SharedSplitPreview {
    fun items(draft: ChecklistDraft): List<String> {
        val rows = draft.details?.let(::JSONObject)?.optJSONArray("rows")
        return if (rows == null) draft.text.lines().map(String::trim).filter(String::isNotEmpty)
        else (0 until rows.length()).map(rows::getJSONObject).filter { it.optBoolean("selected", true) }.map { it.getString("text").trim() }
    }
    fun valid(items: List<String>) = items.size in 1..SharedChecklistActions.MAX_ITEMS &&
        items.all { InboxLimits.valid(it, "") && it.none(Char::isISOControl) } &&
        items.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == items.size

    fun recovery(draft: SharedDraft, workspace: String?): List<RecoveryText> {
        val source = "shared-draft:${draft.scope}:${draft.key}"
        val records = mutableListOf<RecoveryText>()
        if (draft.title.isNotBlank() || draft.description.isNotBlank())
            records += RecoveryText(source, draft.title, draft.description, draft.savedAt, workspaceLabel = workspace)
        if (!draft.key.startsWith("checklist:")) return records
        val details = draft.details?.let(::JSONObject) ?: return records
        val instructions = details.optString("instructions")
        if (instructions.isNotBlank()) records += RecoveryText("$source:instructions", "", instructions, draft.savedAt, workspaceLabel = workspace)
        val rows = details.optJSONArray("rows")
        val edited = (0 until (rows?.length() ?: 0)).map { rows!!.getJSONObject(it) }
            .filter { it.getString("text") != it.optString("originalText") }.map { it.getString("text") }
        if (edited.any(String::isNotBlank)) records += RecoveryText("$source:steps", "", edited.joinToString("\n"), draft.savedAt, workspaceLabel = workspace)
        return records
    }

    fun forbiddenDraft(draft: SharedDraft): SharedDraft {
        val details = draft.details?.let(::JSONObject) ?: return draft
        val rows = details.optJSONArray("rows")
        val edited = (0 until (rows?.length() ?: 0)).map { rows!!.getJSONObject(it) }
            .filter { it.getString("text") != it.optString("originalText") }.map { it.getString("text") }
        val retained = JSONObject().put("instructions", details.optString("instructions"))
        return draft.copy(description = (listOf(draft.description) + edited).filter(String::isNotBlank).joinToString("\n"),
            details = retained.toString())
    }
    fun forbiddenAction(action: String?): String? = action?.let(::JSONObject)?.let { value ->
        value.remove("sourceText")
        value.optJSONObject("preview")?.let { preview ->
            preview.remove("sourceDescription")
            val rows = preview.optJSONArray("rows")
            if (rows != null) preview.put("rows", JSONArray((0 until rows.length()).map(rows::getJSONObject)
                .filter { it.optBoolean("selected", true) }.map { JSONObject().put("text", it.getString("text")).put("selected", true) }))
        }
        value.toString()
    }

    fun adopt(saved: SharedDraft, task: JSONObject, actor: String, registration: String): SharedDraft {
        val request = task.getJSONObject("cleanup")
        check(request.getString("mode") == "SPLIT" && request.getString("status") == "READY")
        val source = request.getJSONObject("splitSource")
        val proposed = request.getJSONObject("proposal").getJSONArray("items")
        val items = (0 until proposed.length()).map(proposed::getString)
        require(valid(items))
        val details = saved.details?.let(::JSONObject) ?: JSONObject()
        if (details.optString("proposalId") == request.getString("id")) return saved
        // Freeze observations from the generation source, including AI-only text changes.
        val basisTask = JSONObject(task.toString()).put("titleVersion", source.getJSONObject("title"))
            .put("descriptionVersion", source.getJSONObject("description"))
        for (group in listOf("lifecycle", "claim", "hierarchy", "deletion", "subtree", "snooze"))
            basisTask.put("${group}Version", source.getJSONObject("state").getString(group))
        val action = JSONObject(SharedTaskActions.capture("SplitTask", basisTask, emptyList(),
            JSONObject().put("taskId", task.getString("id")), actor, emptyMap(), registration))
        action.put("sourceText", JSONObject().put("title", source.getString("sourceTitle")).put("description", source.opt("sourceDescription") ?: JSONObject.NULL))
        action.put("exactText", JSONObject().put("title", source.getJSONObject("title").getString("fieldVersion"))
            .put("description", source.getJSONObject("description").getString("fieldVersion")))
        details.put("proposalId", request.getString("id")).put("rows", JSONArray(items.map { text ->
            JSONObject().put("text", text).put("originalText", text).put("selected", true)
        })).put("sourceDescription", source.opt("sourceDescription") ?: JSONObject.NULL)
        return saved.copy(title = source.getString("sourceTitle"), details = details.toString(),
            basis = JSONObject().put("kind", "SplitTask").put("action", action).toString())
    }
}
