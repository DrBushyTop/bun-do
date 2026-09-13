package fi.bundo.data

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

internal fun JSONObject.decimal(key: String): ULong {
    val text = get(key) as? String ?: error("Expected decimal string")
    require(text == "0" || text.firstOrNull() in '1'..'9' && text.all { it in '0'..'9' })
    return text.toULong()
}

internal fun JSONObject.human(group: String) = getJSONObject("${group}Version").decimal("humanVersion").toString()
internal fun JSONObject.field(group: String) = getJSONObject("${group}Version").decimal("fieldVersion")
internal fun JSONObject.nullableString(key: String) = if (isNull(key)) null else getString(key)
internal fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun sameJson(left: Any?, right: Any?): Boolean = when {
    left is JSONObject && right is JSONObject -> {
        val keys = left.keys().asSequence().toSet()
        keys == right.keys().asSequence().toSet() && keys.all { sameJson(left.get(it), right.get(it)) }
    }
    left is JSONArray && right is JSONArray -> left.length() == right.length() &&
        (0 until left.length()).all { sameJson(left.get(it), right.get(it)) }
    else -> left == right
}

internal object SharedProtocol {
    fun taskId(device: String, sequence: String): String {
        val namespace = UUID.fromString(device)
        val bytes = ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array()
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes + "task/$sequence/0".toByteArray(Charsets.US_ASCII))
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(digest)
        return UUID(buffer.long, buffer.long).toString()
    }

    fun context(): String {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val local = now.atZone(zone)
        return JSONObject().put("capturedInstant", now.toString())
            .put("capturedLocal", local.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS")))
            .put("captureZoneId", zone.id).put("captureOffsetSeconds", local.offset.totalSeconds)
            .put("zoneSource", "DEVICE").put("locale", Locale.getDefault().toLanguageTag())
            .put("clockConfidence", "UNKNOWN").toString()
    }

    fun dependencies(intent: SharedIntent): List<String> = listOfNotNull(intent.afterSequence,
        intent.titleAfterSequence, intent.descriptionAfterSequence, intent.deletionAfterSequence).distinct()

    fun freeze(workspace: SharedWorkspace, intent: SharedIntent, receipts: Map<String, JSONObject>): String {
        val required = dependencies(intent)
        val dependencies = JSONArray().apply { required.forEach { put("${workspace.registration}:$it") } }
        val rejected = required.firstOrNull { checkNotNull(receipts[it]).getString("code") != "ACCEPTED" }
        fun previous(sequence: String?): JSONObject? = sequence?.let { checkNotNull(receipts[it]).getJSONObject("task") }
        val payload = JSONObject()
        val observed = JSONObject()
        val command = when {
            rejected != null -> {
                payload.put("rejectedDependency", "${workspace.registration}:$rejected")
                "DiscardBlockedIntent"
            }
            intent.kind == "CreateTask" -> {
                payload.put("taskId", intent.taskId).put("title", intent.title)
                    .put("description", intent.description ?: JSONObject.NULL)
                "CreateTask"
            }
            else -> {
                payload.put("taskId", intent.taskId)
                if (intent.titleChanged) {
                    payload.put("title", intent.title)
                    observed.put("title", JSONObject().put("humanVersion",
                        previous(intent.titleAfterSequence)?.human("title") ?: intent.observedTitle))
                }
                if (intent.descriptionChanged) {
                    payload.put("description", intent.description ?: JSONObject.NULL)
                    observed.put("description", JSONObject().put("humanVersion",
                        previous(intent.descriptionAfterSequence)?.human("description") ?: intent.observedDescription))
                }
                observed.put("deletion", JSONObject().put("fieldVersion",
                    previous(intent.deletionAfterSequence)?.decimal("deletionVersion")?.toString() ?: intent.observedDeletion))
                "EditTask"
            }
        }
        return JSONObject().put("protocolVersion", 1).put("commandVersion", 1)
            .put("workspaceId", workspace.workspaceId).put("stateEpoch", workspace.epoch)
            .put("deviceId", workspace.registration).put("sequence", intent.sequence)
            .put("command", command).put("payload", payload).put("dependencies", dependencies)
            .put("observedVersions", observed).put("occurredAtContext", JSONObject(intent.captureContext)).toString()
            .also { require(it.toByteArray(Charsets.UTF_8).size <= 32 * 1024) }
    }

    fun optimistic(intent: SharedIntent): JSONObject = JSONObject().put("id", intent.taskId)
        .put("title", intent.title).put("description", intent.description ?: JSONObject.NULL)
        .put("titleVersion", JSONObject().put("fieldVersion", "0").put("humanVersion", "0"))
        .put("descriptionVersion", JSONObject().put("fieldVersion", "0").put("humanVersion", "0"))
        .put("deletionVersion", "0").put("capture", JSONObject().put("title", intent.title)
            .put("description", intent.description ?: JSONObject.NULL).put("context", JSONObject(intent.captureContext)))

    fun inbox(task: JSONObject): InboxTask {
        val capture = task.optJSONObject("capture")
        val created = runCatching { Instant.parse(capture?.getJSONObject("context")?.getString("capturedInstant")).toEpochMilli() }.getOrDefault(0)
        return InboxTask(task.getString("id"), task.getString("title"), task.nullableString("description").orEmpty(),
            capture?.optString("title") ?: task.getString("title"),
            capture?.nullableString("description").orEmpty(), created, created)
    }

    fun validateTask(task: JSONObject, revision: ULong) {
        require(UUID.fromString(task.getString("id")).toString() == task.getString("id"))
        require(InboxLimits.valid(task.getString("title"), task.nullableString("description").orEmpty()))
        for (group in listOf("title", "description")) {
            val field = task.field(group)
            val human = task.human(group).toULong()
            require(human > 0u && human <= field && field <= revision)
        }
        require(task.decimal("deletionVersion") in 1uL..revision)
    }

    fun containsEffect(current: JSONObject?, effect: JSONObject): Boolean = current != null &&
        listOf("title", "description").all { group ->
            current.field(group) >= effect.field(group) &&
                (current.field(group) != effect.field(group) || current.nullableString(group) == effect.nullableString(group))
        }
}
