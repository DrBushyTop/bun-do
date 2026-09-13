package fi.bundo.data

import org.json.JSONObject
import java.io.OutputStream
import java.time.Instant

/** Text-only recovery format, not a protocol command or a database backup. */
object RecoveryExport {
    const val MAX_BYTES = 50 * 1024 * 1024

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
        if (!plainText) text("{\"formatVersion\":1,\"records\":[")
        records.forEachIndexed { index, record ->
            if (plainText) text("$workspaceLabel\n${Instant.ofEpochMilli(record.capturedAt)}\n${record.title}\n${record.description}\n\n")
            else {
                if (index > 0) text(",")
                text(json(record, workspaceLabel))
            }
        }
        if (!plainText) text("]}")
        output.flush()
    }

    private fun json(record: RecoveryText, workspaceLabel: String) = JSONObject()
        .put("workspaceLabel", workspaceLabel)
        .put("capturedAt", Instant.ofEpochMilli(record.capturedAt).toString())
        .put("title", record.title).put("description", record.description)
        .put("variants", org.json.JSONArray()).toString()
}
