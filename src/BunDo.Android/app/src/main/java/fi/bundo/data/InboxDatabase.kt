package fi.bundo.data

import android.content.Context
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "inbox_tasks")
data class InboxTask(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val originalTitle: String,
    val originalDescription: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Local-only intent. It is never a registered workspace command or upload envelope. */
@Entity(tableName = "inbox_intents")
data class InboxIntent(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val taskId: String,
    val kind: String,
    val title: String,
    val description: String,
    val createdAt: Long,
)

@Entity(tableName = "editor_drafts")
data class EditorDraft(
    @PrimaryKey val key: String,
    val title: String = "",
    val description: String = "",
    @ColumnInfo(defaultValue = "0") val savedAt: Long = 0,
)

@Dao
interface InboxDao {
    @Query("SELECT * FROM inbox_tasks ORDER BY createdAt DESC, id ASC")
    fun observeTasks(): Flow<List<InboxTask>>

    @Query("SELECT * FROM editor_drafts")
    fun observeDrafts(): Flow<List<EditorDraft>>

    @Query("SELECT * FROM inbox_tasks WHERE id = :id")
    suspend fun task(id: String): InboxTask?

    @Query("SELECT * FROM editor_drafts WHERE `key` = :key")
    suspend fun draft(key: String): EditorDraft?

    @Query("SELECT * FROM inbox_intents ORDER BY sequence")
    suspend fun intents(): List<InboxIntent>

    @Query("SELECT * FROM inbox_tasks ORDER BY createdAt, id")
    suspend fun allTasks(): List<InboxTask>

    @Query("SELECT * FROM editor_drafts ORDER BY savedAt, `key`")
    suspend fun allDrafts(): List<EditorDraft>

    @Insert
    suspend fun insertTask(task: InboxTask)

    @Query("UPDATE inbox_tasks SET title = :title, description = :description, updatedAt = :now WHERE id = :id")
    suspend fun editTask(id: String, title: String, description: String, now: Long): Int

    @Insert
    suspend fun insertIntent(intent: InboxIntent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDraft(draft: EditorDraft)

    @Query("DELETE FROM editor_drafts WHERE `key` = :key")
    suspend fun deleteDraft(key: String)
}

@Database(
    entities = [InboxTask::class, InboxIntent::class, EditorDraft::class, VoiceRecording::class,
        SharedWorkspace::class, SharedBase::class, SharedProjection::class, SharedIntent::class, SharedDraft::class,
        SharedRecovery::class],
    version = 6,
    exportSchema = true,
)
abstract class InboxDatabase : RoomDatabase() {
    abstract fun inbox(): InboxDao
    abstract fun recordings(): RecordingDao
    abstract fun shared(): SharedDao

    companion object {
        // Deliberately not derived from a future active account or workspace selection.
        const val FILE_NAME = "anonymous-inbox.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE editor_drafts ADD COLUMN savedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS voice_recordings (id TEXT NOT NULL PRIMARY KEY, createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL, state TEXT NOT NULL, reason TEXT NOT NULL)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS shared_workspaces (scope TEXT NOT NULL PRIMARY KEY, workspaceId TEXT NOT NULL, epoch TEXT NOT NULL, registration TEXT NOT NULL, name TEXT NOT NULL, selected INTEGER NOT NULL, nextSequence TEXT NOT NULL, revision TEXT NOT NULL, cursor TEXT, acknowledged TEXT NOT NULL, blocked TEXT, worker TEXT, workerUntil INTEGER NOT NULL, workerBoot INTEGER NOT NULL)")
                for (table in listOf("shared_base", "shared_projection"))
                    db.execSQL("CREATE TABLE IF NOT EXISTS $table (scope TEXT NOT NULL, id TEXT NOT NULL, snapshot TEXT NOT NULL, PRIMARY KEY(scope, id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS shared_intents (scope TEXT NOT NULL, sequence TEXT NOT NULL, taskId TEXT NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, description TEXT, titleChanged INTEGER NOT NULL, descriptionChanged INTEGER NOT NULL, observedTitle TEXT NOT NULL, observedDescription TEXT NOT NULL, observedDeletion TEXT NOT NULL, afterSequence TEXT, captureContext TEXT NOT NULL, frozen TEXT, receipt TEXT, status TEXT NOT NULL, problem TEXT, PRIMARY KEY(scope, sequence))")
                db.execSQL("CREATE TABLE IF NOT EXISTS shared_drafts (scope TEXT NOT NULL, `key` TEXT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL, savedAt INTEGER NOT NULL, PRIMARY KEY(scope, `key`))")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (column in listOf("titleAfterSequence", "descriptionAfterSequence", "deletionAfterSequence"))
                    db.execSQL("ALTER TABLE shared_intents ADD COLUMN $column TEXT")
                db.execSQL("ALTER TABLE shared_drafts ADD COLUMN basis TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (column in listOf("baseGeneration", "projectionGeneration"))
                    db.execSQL("ALTER TABLE shared_workspaces ADD COLUMN $column TEXT NOT NULL DEFAULT 'initial'")
                db.execSQL("ALTER TABLE shared_workspaces ADD COLUMN snapshotRevision TEXT NOT NULL DEFAULT '0'")
                db.execSQL("ALTER TABLE shared_workspaces ADD COLUMN journalVersion INTEGER NOT NULL DEFAULT 0")
                for (table in listOf("shared_base", "shared_projection")) {
                    db.execSQL("CREATE TABLE ${table}_new (scope TEXT NOT NULL, id TEXT NOT NULL, snapshot TEXT NOT NULL, generation TEXT NOT NULL DEFAULT 'initial', PRIMARY KEY(scope, generation, id))")
                    db.execSQL("INSERT INTO ${table}_new (scope, id, snapshot) SELECT scope, id, snapshot FROM $table")
                    db.execSQL("DROP TABLE $table")
                    db.execSQL("ALTER TABLE ${table}_new RENAME TO $table")
                }
                db.execSQL("CREATE TABLE shared_recovery (scope TEXT NOT NULL PRIMARY KEY, snapshotId TEXT NOT NULL, manifest TEXT, nextChunk INTEGER NOT NULL, phase TEXT NOT NULL, afterId TEXT NOT NULL, replayVersion INTEGER NOT NULL, problem TEXT)")
            }
        }

        fun open(context: Context, name: String = FILE_NAME, passphrase: ByteArray? = null): InboxDatabase {
            val builder = Room.databaseBuilder(context, InboxDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                // AccountStore owns the connection used by both UI and workers.
                // Workers cannot independently open a signed-out account.
            if (passphrase != null) {
                System.loadLibrary("sqlcipher")
                net.zetetic.database.Logger.setTarget(net.zetetic.database.NoopTarget())
                builder.openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase))
            }
            return builder.build()
        }
    }
}
