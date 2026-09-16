package fi.bundo

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.EditorDraft
import fi.bundo.data.InboxDatabase
import fi.bundo.data.InboxRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InboxStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()
    private val databases = mutableListOf<InboxDatabase>()

    @get:Rule val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        InboxDatabase::class.java,
    )

    @Test fun voiceReviewMigrationPreservesLegacyRecoveryPolicy() {
        val name = "migration-voice-${UUID.randomUUID()}.db"
        names += name
        migrations.createDatabase(name, 12).apply {
            execSQL("INSERT INTO voice_recordings (id, createdAt, expiresAt, state, reason) VALUES ('legacy', 1, 9999999999999, 'FAILED', 'INTERRUPTED')")
            close()
        }
        migrations.runMigrationsAndValidate(name, 13, true, InboxDatabase.MIGRATION_12_13).apply {
            query("SELECT keepAudio, reviewRequired, review, reason FROM voice_recordings WHERE id = 'legacy'").use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertEquals(0, it.getInt(1))
                assertTrue(it.isNull(2)); assertEquals("INTERRUPTED", it.getString(3))
            }
            close()
        }
    }

    @Test fun bothIntermediateVoiceSchemasUpgradeWithoutDeletingReviewText() {
        for (contextPresent in listOf(false, true)) {
            val name = "migration-voice-context-${UUID.randomUUID()}.db"
            names += name
            migrations.createDatabase(name, 13).apply {
                execSQL("INSERT INTO voice_recordings (id, createdAt, expiresAt, state, reason, keepAudio, reviewRequired, review) VALUES ('draft', 1, 2, 'REVIEW', '', 0, 1, 'keep exact text')")
                if (contextPresent) execSQL("ALTER TABLE voice_recordings ADD COLUMN captureContext TEXT")
                close()
            }
            migrations.runMigrationsAndValidate(name, 14, true, InboxDatabase.MIGRATION_13_14).apply {
                query("SELECT review, keepAudio, captureContext FROM voice_recordings WHERE id = 'draft'").use {
                    assertTrue(it.moveToFirst()); assertEquals("keep exact text", it.getString(0))
                    assertEquals(0, it.getInt(1)); assertTrue(it.isNull(2))
                }
                close()
            }
        }
    }

    private fun open(name: String = "test-${UUID.randomUUID()}.db"): InboxDatabase {
        names += name
        return InboxDatabase.open(context, name).also { databases += it }
    }

    @After fun close() {
        databases.forEach { it.close() }
        names.forEach { context.deleteDatabase(it) }
    }

    @Test fun captureEditAndDraftSurviveDatabaseReopen() = runBlocking {
        val name = "test-${UUID.randomUUID()}.db"
        val db = open(name)
        val repository = InboxRepository(db)
        val source = EditorDraft("new", "Järjestä varasto", "Keep the original language")
        repository.saveDraft(source)
        val id = repository.commit(source)
        repository.commit(EditorDraft(id, "Järjestä hyllyt", "Updated description"))
        repository.saveDraft(EditorDraft("new", "Unfinished task", "Recover me"))
        db.close()

        val reopened = open(name)
        val task = reopened.inbox().task(id)!!
        assertEquals("Järjestä hyllyt", task.title)
        assertEquals("Järjestä varasto", task.originalTitle)
        assertEquals("Keep the original language", task.originalDescription)
        assertEquals(listOf("CaptureInboxTask", "EditInboxTask"), reopened.inbox().intents().map { it.kind })
        assertEquals("Unfinished task", reopened.inbox().draft("new")!!.title)
        assertTrue(reopened.inbox().draft("new")!!.savedAt > 0)
    }

    @Test fun remindersMigrationPreservesExistingDrafts() {
        val name = "test-${UUID.randomUUID()}.db"
        names += name
        migrations.createDatabase(name, 9).apply {
            execSQL("INSERT INTO editor_drafts (`key`, title, description, savedAt) VALUES ('new', 'Keep me', '', 123)")
            close()
        }
        migrations.runMigrationsAndValidate(name, 10, true, InboxDatabase.MIGRATION_9_10).apply {
            query("SELECT title FROM editor_drafts WHERE `key` = 'new'").use {
                assertTrue(it.moveToFirst()); assertEquals("Keep me", it.getString(0))
            }
            close()
        }
    }

    @Test fun failedIntentInsertRollsBackProjectionAndPreservesDraft() = runBlocking {
        val db = open()
        val repository = InboxRepository(db)
        val draft = EditorDraft("new", "Must survive")
        repository.saveDraft(draft)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_intent BEFORE INSERT ON inbox_intents BEGIN SELECT RAISE(ABORT, 'test failure'); END",
        )
        assertTrue(runCatching { repository.commit(draft) }.isFailure)
        assertTrue(db.inbox().intents().isEmpty())
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM inbox_tasks").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        assertEquals("Must survive", db.inbox().draft("new")!!.title)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_intent")
        val id = repository.commit(draft)
        assertNotNull(db.inbox().task(id))
        assertNull(db.inbox().draft("new"))
        assertEquals(1, db.inbox().intents().size)
    }

    @Test fun failedEditRollsBackAndKeepsOriginalAndEditorDraft() = runBlocking {
        val db = open()
        val repository = InboxRepository(db)
        val id = repository.commit(EditorDraft("new", "Original"))
        val edit = EditorDraft(id, "Edited")
        repository.saveDraft(edit)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_edit BEFORE INSERT ON inbox_intents BEGIN SELECT RAISE(ABORT, 'test failure'); END",
        )
        assertTrue(runCatching { repository.commit(edit) }.isFailure)
        assertEquals("Original", db.inbox().task(id)!!.title)
        assertEquals("Edited", db.inbox().draft(id)!!.title)
        assertEquals(1, db.inbox().intents().size)
    }

    @Test fun anonymousInboxIsNotReadFromAnAccountDatabase() = runBlocking {
        val anonymous = open()
        val account = open()
        val id = InboxRepository(anonymous).commit(EditorDraft("new", "Anonymous task"))
        assertNull(account.inbox().task(id))
        assertTrue(account.inbox().intents().isEmpty())
        assertEquals("anonymous-inbox.db", InboxDatabase.FILE_NAME)
    }

    @Test fun migrationPreservesTaskIntentAndDraft() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        names += name
        migrations.createDatabase(name, 1).apply {
            execSQL("INSERT INTO inbox_tasks VALUES ('task', 'Title', 'Description', 'Original', 'Original description', 1, 2)")
            execSQL("INSERT INTO inbox_intents VALUES (7, 'task', 'CaptureInboxTask', 'Title', 'Description', 1)")
            execSQL("INSERT INTO editor_drafts VALUES ('new', 'Kesken', 'Retained')")
            close()
        }
        migrations.runMigrationsAndValidate(name, 2, true, InboxDatabase.MIGRATION_1_2).close()
        val upgraded = open(name)
        assertEquals("Original", upgraded.inbox().task("task")!!.originalTitle)
        assertEquals(7L, upgraded.inbox().intents().single().sequence)
        assertEquals("Kesken", upgraded.inbox().draft("new")!!.title)
        assertEquals(0L, upgraded.inbox().draft("new")!!.savedAt)
    }

    @Test fun speechMigrationKeepsExistingOfflineWork() = runBlocking {
        val name = "speech-migration-${UUID.randomUUID()}.db"
        names += name
        migrations.createDatabase(name, 2).apply {
            execSQL("INSERT INTO inbox_tasks VALUES ('task', 'Title', '', 'Original', '', 1, 2)")
            execSQL("INSERT INTO inbox_intents VALUES (7, 'task', 'CaptureInboxTask', 'Title', '', 1)")
            execSQL("INSERT INTO editor_drafts VALUES ('new', 'Kesken', 'Retained', 3)")
            close()
        }
        migrations.runMigrationsAndValidate(name, 3, true, InboxDatabase.MIGRATION_2_3).close()
        val upgraded = open(name)
        assertEquals("Original", upgraded.inbox().task("task")!!.originalTitle)
        assertEquals(7L, upgraded.inbox().intents().single().sequence)
        assertEquals("Kesken", upgraded.inbox().draft("new")!!.title)
        assertTrue(upgraded.recordings().all().isEmpty())
    }
}
