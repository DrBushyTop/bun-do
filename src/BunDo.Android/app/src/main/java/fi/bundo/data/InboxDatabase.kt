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
    entities = [InboxTask::class, InboxIntent::class, EditorDraft::class, VoiceRecording::class],
    version = 3,
    exportSchema = true,
)
abstract class InboxDatabase : RoomDatabase() {
    abstract fun inbox(): InboxDao
    abstract fun recordings(): RecordingDao

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

        fun open(context: Context, name: String = FILE_NAME): InboxDatabase =
            Room.databaseBuilder(context, InboxDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                // The expiry worker owns a separate connection. Its removals must
                // invalidate the foreground recovery list too.
                .enableMultiInstanceInvalidation()
                .build()
    }
}
