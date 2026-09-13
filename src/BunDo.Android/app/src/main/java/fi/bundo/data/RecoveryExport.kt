package fi.bundo.data

import org.json.JSONObject
import java.io.OutputStream
import java.io.InputStream
import java.time.Instant

/** Text-only recovery format, not a protocol command or a database backup. */
object RecoveryExport {
    const val MAX_BYTES = 50 * 1024 * 1024

    /** Import text only. Identity, command bytes and sequence fields in a file are never trusted or replayed. */
    fun read(input: InputStream): List<RecoveryText> {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES)
            output.write(buffer, 0, count)
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        val document = JSONObject(decoder.decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString())
        require(document.getInt("formatVersion") == 1)
        val records = document.getJSONArray("records")
        return (0 until records.length()).map { index ->
            val record = records.getJSONObject(index)
            val title = record.getString("title")
            val description = record.getString("description")
            require(InboxLimits.length(title) <= InboxLimits.TITLE && InboxLimits.length(description) <= InboxLimits.DESCRIPTION)
            RecoveryText("file:$index", title, description,
                record.nullableString("capturedAt")?.let { Instant.parse(it).toEpochMilli() } ?: 0,
                record.optString("workspaceLabel").takeIf { it.isNotBlank() })
        }
    }

    fun parts(records: List<RecoveryText>): List<List<RecoveryText>> {
        val result = mutableListOf<List<RecoveryText>>()
        var part = mutableListOf<RecoveryText>()
        var bytes = 1024
        for (record in records) {
            val size = json(record, "Account inbox").toByteArray(Charsets.UTF_8).size + 128
            require(size < MAX_BYTES - 1024) { "One record exceeds the export size limit" }
            if (bytes + size > MAX_BYTES) {
                result += part
                part = mutableListOf()
                bytes = 1024
            }
            part += record
            bytes += size
        }
        if (part.isNotEmpty() || result.isEmpty()) result += part
        return result
    }

    fun write(output: OutputStream, records: List<RecoveryText>, workspaceLabel: String, plainText: Boolean, lease: DataLease) {
        var bytes = 0
        fun text(value: String) {
            lease.check()
            val encoded = value.toByteArray(Charsets.UTF_8)
            bytes += encoded.size
            require(bytes <= MAX_BYTES)
            output.write(encoded)
        }
        if (plainText) text("Bun Do text recovery · format 1\n\n")
        else text("{\"formatVersion\":1,\"records\":[")
        records.forEachIndexed { index, record ->
            if (plainText) text("${record.workspaceLabel ?: workspaceLabel}\n${timestamp(record) ?: "Unknown capture time"}\n${record.title}\n${record.description}\n\n")
            else {
                if (index > 0) text(",")
                text(json(record, workspaceLabel))
            }
        }
        if (!plainText) text("]}")
        output.flush()
    }

    private fun json(record: RecoveryText, workspaceLabel: String) = JSONObject()
        .put("workspaceLabel", record.workspaceLabel ?: workspaceLabel)
        .put("capturedAt", timestamp(record) ?: JSONObject.NULL)
        .put("captureContext", record.captureContext?.let(::JSONObject) ?: JSONObject.NULL)
        .put("title", record.title).put("description", record.description)
        .put("variants", org.json.JSONArray().apply {
            if (record.reason != null) put(JSONObject().put("reason", record.reason)
                .put("title", record.title).put("description", record.description))
        }).toString()

    private fun timestamp(record: RecoveryText): String? =
        record.capturedAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() }
}
