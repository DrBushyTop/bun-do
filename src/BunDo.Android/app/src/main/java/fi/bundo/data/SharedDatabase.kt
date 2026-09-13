package fi.bundo.data

import androidx.room.Dao
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
)

@Entity(tableName = "shared_base", primaryKeys = ["scope", "id"])
data class SharedBase(val scope: String, val id: String, val snapshot: String)

@Entity(tableName = "shared_projection", primaryKeys = ["scope", "id"])
data class SharedProjection(val scope: String, val id: String, val snapshot: String)

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
)

@Entity(tableName = "shared_drafts", primaryKeys = ["scope", "key"])
data class SharedDraft(val scope: String, val key: String, val title: String, val description: String, val savedAt: Long,
    val basis: String? = null)

@Dao
interface SharedDao {
    @Query("SELECT * FROM shared_workspaces WHERE scope = :scope")
    suspend fun workspace(scope: String): SharedWorkspace?
    @Query("SELECT * FROM shared_workspaces WHERE registration = :registration")
    suspend fun workspaces(registration: String): List<SharedWorkspace>
    @Query("SELECT * FROM shared_workspaces WHERE selected = 1 AND registration = :registration LIMIT 1")
    fun selected(registration: String): Flow<SharedWorkspace?>
    @Query("SELECT * FROM shared_workspaces WHERE scope = :scope")
    fun observeWorkspace(scope: String): Flow<SharedWorkspace?>
    @Query("UPDATE shared_workspaces SET selected = 0 WHERE selected = 1")
    suspend fun clearSelection()
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWorkspace(value: SharedWorkspace)
    @Query("SELECT * FROM shared_base WHERE scope = :scope")
    suspend fun base(scope: String): List<SharedBase>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBase(value: SharedBase)
    @Query("DELETE FROM shared_base WHERE scope = :scope")
    suspend fun clearBase(scope: String)
    @Query("SELECT * FROM shared_projection WHERE scope = :scope ORDER BY id")
    fun projection(scope: String): Flow<List<SharedProjection>>
    @Query("SELECT * FROM shared_projection WHERE scope = :scope AND id = :id")
    suspend fun task(scope: String, id: String): SharedProjection?
    @Query("DELETE FROM shared_projection WHERE scope = :scope")
    suspend fun clearProjection(scope: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProjection(value: SharedProjection)
    @Query("SELECT * FROM shared_intents WHERE scope = :scope ORDER BY length(sequence), sequence")
    suspend fun intents(scope: String): List<SharedIntent>
    @Query("SELECT * FROM shared_intents WHERE status != 'ACCEPTED' OR problem IS NOT NULL ORDER BY scope, length(sequence), sequence")
    suspend fun recovery(): List<SharedIntent>
    @Query("SELECT * FROM shared_intents WHERE scope = :scope AND problem IS NOT NULL ORDER BY length(sequence), sequence")
    fun problems(scope: String): Flow<List<SharedIntent>>
    @Query("UPDATE shared_intents SET status = 'QUARANTINED', problem = 'REGISTRATION_REPLACED' WHERE scope IN (SELECT scope FROM shared_workspaces WHERE registration != :registration) AND status IN ('PENDING', 'SUBMITTED')")
    suspend fun quarantineOtherRegistrations(registration: String)
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
