package fi.bundo.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Stored inside the encrypted account database, never in global preferences. */
@Entity(tableName = "reminder_settings")
data class ReminderSettings(
    @PrimaryKey val id: Int = 1,
    val enabled: Boolean = false,
    val allTasks: Boolean = false,
    val dateOnlyTime: String = "09:00",
    val revision: Long = 0,
    val permissionAsked: Boolean = false,
)

@Entity(tableName = "reminder_deliveries")
data class ReminderDelivery(@PrimaryKey val key: String, val deliveredAt: Long)

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminder_settings WHERE id = 1")
    suspend fun settings(): ReminderSettings?
    @Query("SELECT * FROM reminder_settings WHERE id = 1")
    fun observeSettings(): Flow<ReminderSettings?>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(value: ReminderSettings)
    @Query("SELECT * FROM reminder_deliveries")
    suspend fun deliveries(): List<ReminderDelivery>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun delivered(values: List<ReminderDelivery>)
    // Task keys are hex digests. These disjoint namespaces retain the single account summary
    // and window acknowledgements without keeping household text or relying on notification extras.
    @Query("DELETE FROM reminder_deliveries WHERE `key` LIKE 'visible:%'")
    suspend fun clearVisible()
}
