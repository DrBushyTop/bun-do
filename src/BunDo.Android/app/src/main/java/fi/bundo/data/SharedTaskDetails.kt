package fi.bundo.data

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Saved deadlines keep nominal values and a pinned zone. Intent never rebases on a remote edit. */
internal object SharedTaskDetails {
    fun zone(state: SharedWorkspace) = state.membership?.let(::JSONObject)?.optString("timeZoneId", "Europe/Helsinki") ?: "Europe/Helsinki"
    fun nominal(due: JSONObject?): Any = due?.let { value -> JSONObject().put("kind", value.getString("kind"))
        .put("localDate", value.getString("localDate")).put("localTime", value.opt("localTime") ?: JSONObject.NULL)
        .put("zoneId", value.getString("zoneId")) } ?: JSONObject.NULL
    fun normalize(due: JSONObject): JSONObject {
        val result = nominal(due) as JSONObject
        val zone = ZoneId.of(result.getString("zoneId"))
        val date = LocalDate.parse(result.getString("localDate"))
        require(date.year in 1900..9998)
        if (result.getString("kind") == "DATE_ONLY") {
            require(result.isNull("localTime"))
            return result.put("instant", JSONObject.NULL).put("adjustment", JSONObject.NULL)
        }
        require(result.getString("kind") == "DATE_TIME")
        val time = LocalTime.parse(result.getString("localTime"))
        require(result.getString("localTime").length == 5)
        val local = LocalDateTime.of(date, time)
        val offsets = zone.rules.getValidOffsets(local)
        val resolved = local.atZone(zone).withEarlierOffsetAtOverlap()
        return result.put("instant", resolved.toInstant().toString()).put("adjustment", when {
            offsets.isEmpty() -> "GAP_FORWARD"; offsets.size > 1 -> "OVERLAP_EARLIER"; else -> JSONObject.NULL
        })
    }
    fun valid(details: String?): Boolean = runCatching {
        details?.let(::JSONObject)?.optJSONObject("due")?.let(::normalize)
    }.isSuccess
    fun values(task: JSONObject?, state: SharedWorkspace): JSONObject = JSONObject()
        .put("due", nominal(task?.optJSONObject("due"))).put("urgent", task?.optBoolean("urgent") ?: false)
        .put("zoneId", task?.optJSONObject("due")?.getString("zoneId") ?: zone(state))
        .put("householdZone", zone(state))
    fun expedited(details: JSONObject, now: Instant = Instant.now()): Boolean {
        if (details.optBoolean("urgent")) return true
        val due = details.optJSONObject("due")?.let(::normalize) ?: return false
        val zone = ZoneId.of(details.optString("householdZone", "Europe/Helsinki"))
        val date = if (due.getString("kind") == "DATE_ONLY") LocalDate.parse(due.getString("localDate"))
            else Instant.parse(due.getString("instant")).atZone(zone).toLocalDate()
        return date <= now.atZone(zone).toLocalDate().plusDays(1)
    }
    fun importedCapture(capturedAt: Long?, context: String?): JSONObject {
        val original = context?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { value -> runCatching {
            val instant = Instant.parse(value.getString("capturedInstant"))
            val local = LocalDateTime.parse(value.getString("capturedLocal"))
            val zone = ZoneId.of(value.getString("captureZoneId"))
            require(value.getString("capturedLocal").matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}")))
            require(instant.atZone(zone).toLocalDateTime().truncatedTo(java.time.temporal.ChronoUnit.MILLIS) == local)
            require(instant.atZone(zone).offset.totalSeconds == value.getInt("captureOffsetSeconds"))
            require(value.getString("zoneSource") in listOf("DEVICE", "WORKSPACE_FALLBACK"))
            require(value.getString("clockConfidence") in listOf("HIGH", "UNKNOWN", "SUSPECT"))
            require(value.getString("locale").length in 1..64)
        }.isSuccess }?.let { value -> JSONObject().apply {
            for (key in listOf("capturedInstant", "capturedLocal", "captureZoneId", "captureOffsetSeconds", "zoneSource", "locale", "clockConfidence")) put(key, value.get(key))
            put("capturedInstant", Instant.parse(value.getString("capturedInstant")).toString())
        } }
        return JSONObject().put("capturedAt", original?.opt("capturedInstant") ?:
            capturedAt?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
            .put("context", original ?: JSONObject.NULL)
    }
    fun intent(draft: EditorDraft, old: JSONObject?, basis: JSONObject?, orderedTasks: List<JSONObject>, state: SharedWorkspace): String? {
        val value = draft.details?.let(::JSONObject) ?: values(old, state)
        val create = old == null
        val dueChanged = create || !sameJson(nominal(old?.optJSONObject("due")), value.opt("due") ?: JSONObject.NULL)
        val urgentChanged = create || old!!.optBoolean("urgent") != value.optBoolean("urgent")
        if (!create && !dueChanged && !urgentChanged && old!!.getString("title") == draft.title &&
            old.nullableString("description").orEmpty() == draft.description) return null
        val result = JSONObject().put("actor", state.membership?.let(::JSONObject)?.opt("me") ?: JSONObject.NULL)
        val observed = JSONObject(); val after = JSONObject()
        if (dueChanged) {
            result.put("due", value.opt("due") ?: JSONObject.NULL)
            observed.put("due", old?.human("due") ?: "0")
            basis?.nullableString("dueAfterSequence")?.let { after.put("due", it) }
        }
        if (urgentChanged) {
            result.put("urgent", value.optBoolean("urgent"))
            observed.put("urgent", old?.optString("urgencyVersion", "0") ?: "0")
            basis?.nullableString("urgentAfterSequence")?.let { after.put("urgent", it) }
        }
        if (create) {
            result.put("anonymousCapture", value.optBoolean("anonymousCapture"))
            if (value.has("originalCapture")) result.put("originalCapture", value.get("originalCapture"))
            if (expedited(value)) {
                val roots = orderedTasks.filter { it.isNull("parentId") && it.isNull("deletion") && it.optString("lifecycle", "OPEN") == "OPEN" }
                val ids = roots.map { it.getString("id") }
                val firstOrdinary = roots.indexOfFirst { !expedited(values(it, state)) }
                val index = if (firstOrdinary < 0) ids.size else firstOrdinary
                result.put("placement", JSONObject().put("afterTaskId", ids.getOrNull(index - 1) ?: JSONObject.NULL)
                    .put("beforeTaskId", ids.getOrNull(index) ?: JSONObject.NULL))
            }
        }
        return result.put("observed", observed).put("after", after).toString()
    }
    fun authoredDraft(details: String?, original: JSONObject?): String? = details?.let(::JSONObject)?.let { value ->
        if (original != null && sameJson(value.opt("due"), nominal(original.optJSONObject("due")))) value.remove("due")
        if (original != null && value.optBoolean("urgent") == original.optBoolean("urgent")) value.remove("urgent")
        value.remove("zoneId"); value.remove("householdZone")
        value.toString()
    }
    fun validateReceipt(intent: SharedIntent, task: JSONObject) {
        val details = intent.details?.let(::JSONObject) ?: return
        if (details.has("due")) require(sameJson(nominal(task.optJSONObject("due")), details.get("due")))
        if (details.has("urgent")) require(task.optBoolean("urgent") == details.getBoolean("urgent"))
    }
    fun reapply(retained: SharedIntent, task: JSONObject, state: SharedWorkspace): String? = retained.details?.let(::JSONObject)?.let { details ->
        val observed = JSONObject()
        if (details.has("due")) observed.put("due", task.human("due"))
        if (details.has("urgent")) observed.put("urgent", task.optString("urgencyVersion", "0"))
        details.put("observed", observed).put("after", JSONObject())
            .put("actor", state.membership?.let(::JSONObject)?.opt("me") ?: JSONObject.NULL).toString()
    }
    fun dependencies(intent: SharedIntent): List<String> = intent.details?.let(::JSONObject)?.optJSONObject("after")?.let { after ->
        after.keys().asSequence().map(after::getString).toList()
    }.orEmpty()
    fun writes(intent: SharedIntent) = intent.details?.let(::JSONObject)?.let { details ->
        listOf("due", "urgent").filter(details::has)
    }.orEmpty()
    fun freeze(intent: SharedIntent, receipts: Map<String, JSONObject>, payload: JSONObject, observed: JSONObject) {
        val details = JSONObject(checkNotNull(intent.details))
        val after = details.getJSONObject("after")
        for (group in writes(intent)) {
            payload.put(group, details.get(group))
            if (intent.kind != "CreateTask") {
                val previous = if (after.has(group)) SharedChecklistActions.receiptTask(checkNotNull(receipts[after.getString(group)]), intent.taskId) else null
                val version = if (previous == null) details.getJSONObject("observed").getString(group)
                    else if (group == "due") previous.human(group) else previous.optString("urgencyVersion", "0")
                observed.put(group, JSONObject().put(if (group == "due") "humanVersion" else "fieldVersion", version))
            }
        }
        if (intent.kind == "CreateTask") {
            payload.put("anonymousCapture", details.optBoolean("anonymousCapture"))
            if (details.has("originalCapture")) payload.put("originalCapture", details.get("originalCapture"))
            if (details.has("placement")) payload.put("placement", details.get("placement"))
        }
    }
    fun projectCreate(task: JSONObject, intent: SharedIntent) {
        val details = intent.details?.let(::JSONObject)
        val imported = details?.optBoolean("anonymousCapture") == true
        val original = details?.optJSONObject("originalCapture")
        val captured = if (imported) original?.opt("capturedAt") ?: JSONObject.NULL else JSONObject(intent.captureContext).get("capturedInstant")
        if (imported) task.getJSONObject("capture").put("context", original?.opt("context") ?: JSONObject.NULL)
        task.put("due", details?.optJSONObject("due")?.let(::normalize) ?: JSONObject.NULL)
            .put("dueVersion", JSONObject().put("fieldVersion", "0").put("humanVersion", "0"))
            .put("urgent", details?.optBoolean("urgent") ?: false).put("urgencyVersion", "0")
            .put("creation", JSONObject().put("actorId", if (details?.optBoolean("anonymousCapture") == true) JSONObject.NULL
                else details?.opt("actor") ?: intent.taskAction?.let(::JSONObject)?.opt("actor") ?: JSONObject.NULL).put("capturedAt", captured).put("acceptedAt", JSONObject.NULL))
            .put("initialPlacement", if (details?.has("placement") == true) "EXPEDITED" else "APPENDED")
    }
    fun problem(task: JSONObject, intent: SharedIntent, intents: List<SharedIntent>, applied: Set<String>): String? {
        val details = intent.details?.let(::JSONObject) ?: return null
        val after = details.getJSONObject("after")
        for (group in writes(intent)) {
            val dependency = if (after.has(group)) intents.find { it.sequence == after.getString(group) } else null
            if (dependency?.status in listOf("REJECTED", "QUARANTINED", "BLOCKED_DEPENDENCY", "DISMISSED")) return "BLOCKED_DEPENDENCY"
            val receipt = dependency?.receipt?.let { SharedChecklistActions.receiptTask(JSONObject(it), intent.taskId) }
            fun version(value: JSONObject) = if (group == "due") value.human("due") else value.optString("urgencyVersion", "0")
            val expected = if (receipt != null) version(receipt) else if (after.has(group)) {
                if (dependency?.sequence !in applied) return "BLOCKED_DEPENDENCY"
                version(task)
            } else details.getJSONObject("observed").getString(group)
            if (version(task) != expected) return "FIELD_CONFLICT"
        }
        return null
    }
    fun projectEdit(task: JSONObject, intent: SharedIntent) {
        val details = intent.details?.let(::JSONObject)
        if (details?.has("due") == true) task.put("due", details.optJSONObject("due")?.let(::normalize) ?: JSONObject.NULL)
        if (details?.has("urgent") == true) task.put("urgent", details.getBoolean("urgent"))
        markPending(task, intent)
    }
    fun markPending(task: JSONObject, intent: SharedIntent) {
        val actor = intent.details?.let(::JSONObject)?.opt("actor") ?: intent.taskAction?.let(::JSONObject)?.opt("actor") ?: JSONObject.NULL
        task.put("lastChange", JSONObject().put("actorId", actor).put("source", "LOCAL")
            .put("at", JSONObject(intent.captureContext).optString("capturedInstant")))
    }
}
