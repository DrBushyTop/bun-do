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
    val details: String? = null,
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
        SharedRecovery::class, ReminderSettings::class, ReminderDelivery::class],
    version = 17,
    exportSchema = true,
)
abstract class InboxDatabase : RoomDatabase() {
    abstract fun inbox(): InboxDao
    abstract fun recordings(): RecordingDao
    abstract fun shared(): SharedDao
    abstract fun reminders(): ReminderDao

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

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shared_workspaces ADD COLUMN membership TEXT")
                db.execSQL("ALTER TABLE shared_intents ADD COLUMN taskAction TEXT")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE editor_drafts ADD COLUMN details TEXT")
                db.execSQL("ALTER TABLE shared_drafts ADD COLUMN details TEXT")
                db.execSQL("ALTER TABLE shared_intents ADD COLUMN details TEXT")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE voice_recordings ADD COLUMN checklistScope TEXT")
                db.execSQL("ALTER TABLE voice_recordings ADD COLUMN checklistTaskId TEXT")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE reminder_settings (id INTEGER NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, allTasks INTEGER NOT NULL, dateOnlyTime TEXT NOT NULL, revision INTEGER NOT NULL, permissionAsked INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE reminder_deliveries (`key` TEXT NOT NULL PRIMARY KEY, deliveredAt INTEGER NOT NULL)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shared_workspaces ADD COLUMN progress TEXT")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE voice_recordings ADD COLUMN workspaceScope TEXT")
                db.execSQL("ALTER TABLE voice_recordings ADD COLUMN committedTaskId TEXT")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Existing recovery audio keeps its previous retention contract.
                db.execSQL("ALTER TABLE voice_recordings ADD COLUMN keepAudio INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE voice_recordings ADD COLUMN reviewRequired INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE voice_recordings ADD COLUMN review TEXT")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Both intermediate voice builds were installed during native verification.
                // Preserve those databases as well as the first review-draft schema.
                val hasContext = db.query("PRAGMA table_info(voice_recordings)").use { columns ->
                    var found = false
                    while (columns.moveToNext()) if (columns.getString(columns.getColumnIndexOrThrow("name")) == "captureContext") found = true
                    found
                }
                if (!hasContext) db.execSQL("ALTER TABLE voice_recordings ADD COLUMN captureContext TEXT")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (column in listOf("adventure", "adventureBow", "adventureSeal"))
                    db.execSQL("ALTER TABLE shared_workspaces ADD COLUMN $column TEXT")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shared_workspaces ADD COLUMN adventureCreation TEXT")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shared_workspaces ADD COLUMN listLibrary TEXT")
            }
        }

        fun open(context: Context, name: String = FILE_NAME, passphrase: ByteArray? = null): InboxDatabase {
            val builder = Room.databaseBuilder(context, InboxDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)
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
