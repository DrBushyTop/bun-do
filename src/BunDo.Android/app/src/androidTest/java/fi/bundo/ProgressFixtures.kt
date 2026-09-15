package fi.bundo

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal fun progressFixture(taskId: String, member: String, revision: String = "1"): JSONObject {
    fun bucket(start: String, end: String, count: Int) = JSONObject().put("start", start).put("end", end).put("count", count)
    val week = JSONArray((0..6).map { i -> val day = LocalDate.parse("2026-09-14").plusDays(i.toLong()).toString(); bucket(day, day, if (i == 1) 3 else 0) })
    val month = JSONArray().put(bucket("2026-09-01", "2026-09-06", 2)).put(bucket("2026-09-07", "2026-09-13", 3))
        .put(bucket("2026-09-14", "2026-09-20", 3)).put(bucket("2026-09-21", "2026-09-27", 0)).put(bucket("2026-09-28", "2026-09-30", 0))
    return JSONObject().put("revision", revision).put("asOf", "2026-09-15T08:00:00Z")
        .put("statistics", JSONObject().put("zoneId", "Europe/Helsinki").put("today", "2026-09-15")
            .put("weekCount", 3).put("monthCount", 8).put("lifetimeCount", 25).put("streak", 2)
            .put("reachedMilestone", 25).put("nextMilestone", 50).put("weekDays", week).put("monthWeeks", month))
        .put("activity", JSONArray().put(JSONObject().put("revision", revision).put("taskId", taskId).put("actorId", member)
            .put("action", "CompleteTask").put("acceptedAt", "2026-09-15T07:00:00Z")))
}
