package fi.bundo.ui

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Presentation only, from canonical roots rather than pending commands or filtered queue rows. */
internal fun sceneActivity(tasks: List<JSONObject>, now: Instant): String {
    val roots = tasks.filter { it.has("title") && it.optString("entityType") != "PURGED_TASK" && it.isNull("parentId") &&
        it.isNull("deletion") && it.optString("lifecycle", "OPEN") == "OPEN" }
    if (roots.isEmpty()) return "rest"
    val overdue = roots.count { task -> task.optJSONObject("due")?.let { due -> runCatching {
        if (due.getString("kind") == "DATE_ONLY") LocalDate.parse(due.getString("localDate")) < now.atZone(ZoneId.of(due.getString("zoneId"))).toLocalDate()
        else Instant.parse(due.getString("instant")) < now
    }.getOrDefault(false) } ?: false }
    return if (overdue >= 6) "paperwork" else "dojo"
}
