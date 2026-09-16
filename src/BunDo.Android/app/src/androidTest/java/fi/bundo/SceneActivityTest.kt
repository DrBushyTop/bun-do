package fi.bundo

import fi.bundo.ui.sceneActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SceneActivityTest {
    private val now = Instant.parse("2026-09-16T10:00:00Z")
    private fun root() = JSONObject().put("title", "Task").put("lifecycle", "OPEN").put("due", JSONObject()
        .put("kind", "DATE_ONLY").put("localDate", "2026-09-15").put("zoneId", "Europe/Helsinki"))
    @Test fun clear_queue_rests_and_only_overdue_open_roots_prompt_paperwork() {
        assertEquals("rest", sceneActivity(emptyList(), now))
        assertEquals("rest", sceneActivity(listOf(JSONObject().put("entityType", "ROOT_ORDER")), now))
        assertEquals("dojo", sceneActivity(List(5) { root() }, now))
        assertEquals("paperwork", sceneActivity(List(6) { root() }, now))
        assertEquals("rest", sceneActivity(List(6) { root().put("lifecycle", "COMPLETED") }, now))
        assertEquals("dojo", sceneActivity(listOf(root()) + List(8) { root().put("parentId", "root") }, now))
        assertEquals("rest", sceneActivity(listOf(root().put("deletion", JSONObject())), now))
    }
    @Test fun date_only_is_not_overdue_until_the_next_day_in_its_pinned_zone() {
        assertEquals("dojo", sceneActivity(List(6) { root().apply { getJSONObject("due").put("localDate", "2026-09-16") } }, now))
        assertEquals("paperwork", sceneActivity(List(6) { root().apply { getJSONObject("due").put("kind", "DATE_TIME").put("instant", "2026-09-16T09:00:00Z") } }, now))
    }
}
