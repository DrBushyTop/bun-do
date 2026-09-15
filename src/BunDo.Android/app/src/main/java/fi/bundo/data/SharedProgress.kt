package fi.bundo.data

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Only server snapshots enter this cache. Optimistic task changes never alter household counts. */
internal object SharedProgress {
    fun accept(previous: String?, snapshot: JSONObject?): String? {
        if (snapshot == null) return previous
        validate(snapshot)
        val old = previous?.let(::JSONObject)
        if (old != null && (old.decimal("revision") > snapshot.decimal("revision") ||
                Instant.parse(old.getString("asOf")) > Instant.parse(snapshot.getString("asOf")))) return previous
        return snapshot.toString()
    }
    fun validate(value: JSONObject) {
        val revision = value.decimal("revision")
        val asOf = Instant.parse(value.getString("asOf"))
        val stats = value.getJSONObject("statistics")
        val zone = ZoneId.of(stats.getString("zoneId"))
        val today = LocalDate.parse(stats.getString("today"))
        require(asOf.atZone(zone).toLocalDate() == today)
        for (key in listOf("weekCount", "monthCount", "lifetimeCount", "streak", "reachedMilestone")) require(stats.getInt(key) >= 0)
        require(stats.getInt("weekCount") <= stats.getInt("lifetimeCount") && stats.getInt("monthCount") <= stats.getInt("lifetimeCount"))
        require(stats.getInt("reachedMilestone") <= stats.getInt("lifetimeCount"))
        if (!stats.isNull("nextMilestone")) require(stats.getInt("nextMilestone") > stats.getInt("lifetimeCount"))
        for ((key, count) in listOf("weekDays" to "weekCount", "monthWeeks" to "monthCount")) {
            val buckets = stats.getJSONArray(key)
            require(if (key == "weekDays") buckets.length() == 7 else buckets.length() in 4..6)
            var sum = 0L
            var end: LocalDate? = null
            for (index in 0 until buckets.length()) {
                val bucket = buckets.getJSONObject(index)
                val start = LocalDate.parse(bucket.getString("start"))
                val until = LocalDate.parse(bucket.getString("end"))
                require(until >= start && (end == null || start == end.plusDays(1)))
                require(bucket.getInt("count") >= 0)
                sum += bucket.getInt("count"); end = until
            }
            require(sum == stats.getInt(count).toLong())
        }
        val activity = value.getJSONArray("activity")
        require(activity.length() <= 60)
        var last: ULong? = null
        for (index in 0 until activity.length()) {
            val event = activity.getJSONObject(index)
            require(event.decimal("revision") <= revision && (last == null || event.decimal("revision") < last))
            last = event.decimal("revision")
            UUID.fromString(event.getString("taskId"))
            event.nullableString("actorId")?.let(UUID::fromString)
            Instant.parse(event.getString("acceptedAt"))
            require(event.getString("action").length in 1..80)
        }
    }
}
