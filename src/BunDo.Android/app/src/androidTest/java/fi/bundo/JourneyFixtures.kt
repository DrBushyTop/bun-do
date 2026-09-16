package fi.bundo

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun journeyFixture(credits: Int = 7, revision: String = "2"): JSONObject =
    progressFixture(UUID.randomUUID().toString(), UUID.randomUUID().toString(), revision).put("journey",
        JSONObject().put("enabledAt", "2026-09-15T07:00:00Z").put("credits", credits)
            .put("routeId", "dojo-garden").put("locationId", if (credits >= 25) "lantern-garden" else
                listOf("dojo-gate", "bamboo-path", "moss-bridge", "cedar-ridge", "lantern-garden")[credits / 5])
            .put("locationIndex", (credits / 5).coerceAtMost(4)).put("locationCount", 5)
            .put("locationCompletions", if (credits >= 25) 5 else credits % 5).put("completionsPerLocation", 5)
            .put("completedRouteIds", JSONArray(if (credits >= 25) listOf("dojo-garden") else emptyList<String>()))
            .put("resting", credits >= 25))
