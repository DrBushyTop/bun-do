package fi.bundo.data

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "shared_workspaces")
data class SharedWorkspace(
    @PrimaryKey val scope: String,
    val workspaceId: String,
    val epoch: String,
    val registration: String,
    val name: String,
    val selected: Boolean = false,
    val nextSequence: String = "1",
    val revision: String = "0",
    val cursor: String? = null,
    val acknowledged: String = "0",
    val blocked: String? = null,
    val worker: String? = null,
    val workerUntil: Long = 0,
    val workerBoot: Int = 0,
    @ColumnInfo(defaultValue = "'initial'") val baseGeneration: String = "initial",
    @ColumnInfo(defaultValue = "'initial'") val projectionGeneration: String = "initial",
    @ColumnInfo(defaultValue = "'0'") val snapshotRevision: String = "0",
    @ColumnInfo(defaultValue = "0") val journalVersion: Long = 0,
    val membership: String? = null,
    val progress: String? = null,
    val adventure: String? = null,
    @ColumnInfo(defaultValue = "0") val adventureFetchedAt: Long = 0,
    val adventureBow: String? = null,
    val adventureSeal: String? = null,
    val adventureCreation: String? = null,
    val listLibrary: String? = null,
    @ColumnInfo(defaultValue = "0") val personal: Boolean = false,
)

@Entity(tableName = "shared_base", primaryKeys = ["scope", "generation", "id"])
data class SharedBase(val scope: String, val id: String, val snapshot: String,
    @ColumnInfo(defaultValue = "'initial'") val generation: String = "initial")

@Entity(tableName = "shared_projection", primaryKeys = ["scope", "generation", "id"])
data class SharedProjection(val scope: String, val id: String, val snapshot: String,
    @ColumnInfo(defaultValue = "'initial'") val generation: String = "initial")

/** A restart resumes only verified chunks. Neither the journal nor the old visible generation is removed. */
@Entity(tableName = "shared_recovery")
data class SharedRecovery(
    @PrimaryKey val scope: String,
    val snapshotId: String,
    val manifest: String? = null,
    val nextChunk: Int = 0,
    val phase: String = "DOWNLOAD",
    val afterId: String = "",
    val replayVersion: Long = -1,
    val problem: String? = null,
)

@Entity(tableName = "shared_intents", primaryKeys = ["scope", "sequence"])
data class SharedIntent(
    val scope: String, val sequence: String, val taskId: String, val kind: String,
    val title: String, val description: String?,
    val titleChanged: Boolean, val descriptionChanged: Boolean,
    val observedTitle: String, val observedDescription: String, val observedDeletion: String,
    val afterSequence: String?, val captureContext: String,
    val frozen: String? = null, val receipt: String? = null, val status: String = "PENDING",
    val problem: String? = null,
    val titleAfterSequence: String? = null, val descriptionAfterSequence: String? = null,
    val deletionAfterSequence: String? = null,
    val taskAction: String? = null,
    val details: String? = null,
)

@Entity(tableName = "shared_drafts", primaryKeys = ["scope", "key"])
data class SharedDraft(val scope: String, val key: String, val title: String, val description: String, val savedAt: Long,
    val basis: String? = null, val details: String? = null)

@Dao
interface SharedDao {
    @Query("SELECT * FROM shared_workspaces WHERE scope = :scope")
    suspend fun workspace(scope: String): SharedWorkspace?
    @Query("SELECT * FROM shared_workspaces WHERE registration = :registration")
    fun observeWorkspaces(registration: String): Flow<List<SharedWorkspace>>
    @Query("SELECT * FROM shared_workspaces WHERE registration = :registration")
    suspend fun workspaces(registration: String): List<SharedWorkspace>
    @Query("SELECT * FROM shared_workspaces")
    suspend fun allWorkspaces(): List<SharedWorkspace>
    @Query("SELECT * FROM shared_workspaces WHERE selected = 1 AND registration = :registration LIMIT 1")
    fun selected(registration: String): Flow<SharedWorkspace?>
    @Query("SELECT * FROM shared_workspaces WHERE scope = :scope")
    fun observeWorkspace(scope: String): Flow<SharedWorkspace?>
    @Query("UPDATE shared_workspaces SET selected = 0 WHERE selected = 1")
    suspend fun clearSelection()
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWorkspace(value: SharedWorkspace)
    @Query("SELECT * FROM shared_base WHERE scope = :scope AND generation = (SELECT baseGeneration FROM shared_workspaces WHERE scope = :scope)")
    suspend fun base(scope: String): List<SharedBase>
    @Query("SELECT * FROM shared_base WHERE scope = :scope AND generation = (SELECT baseGeneration FROM shared_workspaces WHERE scope = :scope)")
    fun observeBase(scope: String): Flow<List<SharedBase>>
    @Query("SELECT * FROM shared_base WHERE scope = :scope AND generation = :generation ORDER BY id")
    suspend fun generationBase(scope: String, generation: String): List<SharedBase>
    @Query("SELECT * FROM shared_base WHERE scope = :scope AND generation = :generation AND id > :after ORDER BY id LIMIT 64")
    suspend fun basePage(scope: String, generation: String, after: String): List<SharedBase>
    @Query("SELECT COUNT(*) FROM shared_base WHERE scope = :scope AND generation = :generation")
    suspend fun baseCount(scope: String, generation: String): Int
    @Query("SELECT * FROM shared_base WHERE scope = :scope AND generation = :generation AND id = :id")
    suspend fun baseTask(scope: String, generation: String, id: String): SharedBase?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBase(value: SharedBase)
    @Query("DELETE FROM shared_base WHERE scope = :scope")
    suspend fun clearBase(scope: String)
    @Query("SELECT * FROM shared_projection WHERE scope = :scope AND generation = (SELECT projectionGeneration FROM shared_workspaces WHERE scope = :scope) ORDER BY id")
    fun projection(scope: String): Flow<List<SharedProjection>>
    @Query("SELECT * FROM shared_projection WHERE scope = :scope AND generation = :generation ORDER BY id")
    suspend fun projectionRows(scope: String, generation: String): List<SharedProjection>
    @Query("SELECT * FROM shared_projection WHERE scope = :scope AND id = :id AND generation = (SELECT projectionGeneration FROM shared_workspaces WHERE scope = :scope)")
    suspend fun task(scope: String, id: String): SharedProjection?
    @Query("DELETE FROM shared_projection WHERE scope = :scope")
    suspend fun clearProjection(scope: String)
    @Query("DELETE FROM shared_projection WHERE scope = :scope AND generation = :generation")
    suspend fun clearProjectionGeneration(scope: String, generation: String)
    @Query("DELETE FROM shared_projection WHERE scope = :scope AND generation = :generation AND id = :id")
    suspend fun deleteProjectionTask(scope: String, generation: String, id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProjection(value: SharedProjection)
    @Query("SELECT * FROM shared_intents WHERE scope = :scope ORDER BY length(sequence), sequence")
    suspend fun intents(scope: String): List<SharedIntent>
    @Query("SELECT * FROM shared_intents WHERE scope = :scope AND taskId = :id ORDER BY length(sequence), sequence")
    suspend fun taskIntents(scope: String, id: String): List<SharedIntent>
    @Query("SELECT DISTINCT taskId FROM shared_intents WHERE scope = :scope AND taskId > :after ORDER BY taskId LIMIT 64")
    suspend fun intentTaskPage(scope: String, after: String): List<String>
    @Query("SELECT * FROM shared_recovery WHERE scope = :scope")
    suspend fun recoveryState(scope: String): SharedRecovery?
    @Query("SELECT * FROM shared_recovery WHERE scope = :scope")
    fun observeRecovery(scope: String): Flow<SharedRecovery?>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRecovery(value: SharedRecovery)
    @Query("DELETE FROM shared_recovery WHERE scope = :scope")
    suspend fun deleteRecovery(scope: String)
    @Query("DELETE FROM shared_base WHERE rowid IN (SELECT rowid FROM shared_base WHERE scope = :scope AND generation != :keep AND generation != :other LIMIT 64)")
    suspend fun reclaimBase(scope: String, keep: String, other: String): Int
    @Query("DELETE FROM shared_projection WHERE rowid IN (SELECT rowid FROM shared_projection WHERE scope = :scope AND generation != :keep AND generation != :other LIMIT 64)")
    suspend fun reclaimProjection(scope: String, keep: String, other: String): Int
    @Query("SELECT * FROM shared_intents WHERE status != 'ACCEPTED' OR problem IS NOT NULL ORDER BY scope, length(sequence), sequence")
    suspend fun recovery(): List<SharedIntent>
    @Query("SELECT * FROM shared_intents")
    fun observeAllIntents(): Flow<List<SharedIntent>>
    @Query("SELECT * FROM shared_drafts")
    fun observeAllDrafts(): Flow<List<SharedDraft>>
    @Query("SELECT * FROM shared_intents WHERE scope = :scope AND problem IS NOT NULL AND status != 'DISMISSED' ORDER BY length(sequence), sequence")
    fun problems(scope: String): Flow<List<SharedIntent>>
    suspend fun quarantineOtherRegistrations(registration: String, workspace: String? = null, epoch: String? = null) {
        for (state in allWorkspaces()) {
            val reason = if (state.registration != registration) "REGISTRATION_REPLACED"
                else if (state.workspaceId == workspace && state.epoch != epoch) "EPOCH_CHANGED" else continue
            saveWorkspace(state.copy(blocked = reason, worker = null))
            for (intent in intents(state.scope)) {
                val awaiting = intent.status == "ACCEPTED" && intent.receipt?.let {
                    org.json.JSONObject(it).decimal("effectRevision") > state.revision.toULong()
                } == true
                if (intent.status in listOf("PENDING", "SUBMITTED") || awaiting)
                    saveIntent(intent.copy(status = "QUARANTINED", problem = reason))
            }
        }
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveIntent(value: SharedIntent)
    @Query("DELETE FROM shared_intents WHERE scope = :scope AND sequence = :sequence")
    suspend fun deleteIntent(scope: String, sequence: String)
    @Query("SELECT * FROM shared_drafts WHERE scope = :scope")
    fun drafts(scope: String): Flow<List<SharedDraft>>
    @Query("SELECT * FROM shared_drafts")
    suspend fun allDrafts(): List<SharedDraft>
    @Query("SELECT * FROM shared_drafts WHERE scope = :scope AND `key` = :key")
    suspend fun draft(scope: String, key: String): SharedDraft?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDraft(value: SharedDraft)
    @Query("DELETE FROM shared_drafts WHERE scope = :scope AND `key` = :key")
    suspend fun deleteDraft(scope: String, key: String)
}
