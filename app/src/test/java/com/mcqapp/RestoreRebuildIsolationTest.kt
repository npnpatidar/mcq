package com.mcqapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.backup.BackupRestorer
import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.SettingsStore
import com.mcqapp.data.repository.dataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * A restore's outcome is decided by the data and the settings write, never by
 * the FSRS-7 rebuild that follows them.
 *
 * The rebuild used to run inline inside the restore's `applySettings` step,
 * after the wipe/replace and the settings write had both committed — so a
 * rebuild failure (an OOM on a big library, a disk error) surfaced as a failed
 * or partial restore even though the library was in place. It also stretched
 * the restore's own phase: a large backup spent most of "restoring…" replaying
 * review logs into memory state.
 *
 * These tests drive the real [BackupRestorer] with the real
 * [McqRepository.settingsRestore] callback; only the rebuild itself is
 * substituted, which is the smallest seam that can fail on purpose without
 * closing the database the restore needs. Robolectric + the real database, so
 * this runs on CI's x86_64 runners and not on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreRebuildIsolationTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext<Context>()
        context.dataStore.edit { it.clear() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsStore(context)
    }

    @After
    fun tearDown() {
        db.close()
        runBlocking { context.dataStore.edit { it.clear() } }
    }

    /**
     * The rebuild fails; the restore still returns its success report, the
     * restored preference is in force, and the rebuild was actually attempted.
     * The scope mirrors production's own ([SupervisorJob] + a dispatcher, only
     * unconfined here): [Dispatchers.Unconfined] runs the fire-and-forget body
     * to completion (the failure is synchronous), so the assertion cannot race
     * it.
     */
    @Test
    fun aFailedRebuildNeverFailsTheRestore() = runBlocking {
        val attempts = AtomicInteger()
        val repository = McqRepository(
            db,
            context,
            backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            rebuildAfterRestore = {
                attempts.incrementAndGet()
                throw IllegalStateException("rebuild died after the restore committed")
            }
        )

        val report = BackupRestorer(db).restore(
            backup(),
            applySettings = { repository.settingsRestore(it) }
        )

        assertTrue("the restore must report its own success: $report", report.startsWith("Restored"))
        assertFalse(
            "a rebuild failure is not a settings failure: $report",
            report.contains("not restored")
        )
        assertEquals("the restore must still start the rebuild", 1, attempts.get())
        assertEquals(0.95, settings.schedulerConfigNow().desiredRetention, 1e-9)
    }

    /** The success path is unchanged: the restore applies settings and the rebuild runs. */
    @Test
    fun aSuccessfulRestoreAppliesSettingsAndStartsTheRebuild() = runBlocking {
        val attempts = AtomicInteger()
        val repository = McqRepository(
            db,
            context,
            backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            rebuildAfterRestore = { attempts.incrementAndGet() }
        )

        val report = BackupRestorer(db).restore(
            backup(),
            applySettings = { repository.settingsRestore(it) }
        )

        assertTrue(report.startsWith("Restored"))
        assertEquals(0.95, settings.schedulerConfigNow().desiredRetention, 1e-9)
        assertEquals(1, attempts.get())
    }

    /**
     * The rebuild is still running when the restore returns: it is started, not
     * awaited, so a large backup's replay cannot stretch the restore's own
     * "restoring…" phase. The seam suspends on a gate the test completes
     * afterwards — the smallest "still streaming" body — and the restore call
     * is bounded, so an await would fail here instead of hanging.
     */
    @Test
    fun theRestoreReturnsWhileTheRebuildIsStillRunning() = runBlocking {
        val started = AtomicInteger()
        val finished = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val repository = McqRepository(
            db,
            context,
            backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            rebuildAfterRestore = {
                started.incrementAndGet()
                gate.await()
                finished.incrementAndGet()
            }
        )

        val report = withTimeout(5_000) {
            BackupRestorer(db).restore(
                backup(),
                applySettings = { repository.settingsRestore(it) }
            )
        }

        assertTrue(
            "the restore must report its own success: $report",
            report.startsWith("Restored")
        )
        assertEquals("the rebuild must have started", 1, started.get())
        assertEquals("but must not have been awaited", 0, finished.get())
        gate.complete(Unit)
        // Unconfined resumes the body inline, but tolerate a dispatch rather
        // than assuming it: the point is that the restore did not wait.
        val deadline = System.currentTimeMillis() + 2_000
        while (finished.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("completing the rebuild's body must let it finish", 1, finished.get())
    }

    /** A minimal backup: no bank, one preference, the default study file. */
    private fun backup() = McqBackupFormat.Backup(
        manifest = McqBackupFormat.Manifest(),
        content = McqFileDto(),
        study = McqBackupFormat.StudyFile(),
        settings = McqBackupFormat.SettingsFile(
            mapOf(
                "fsrs_desired_retention" to McqBackupFormat.PreferenceEntry(
                    "double",
                    JsonPrimitive(0.95)
                )
            )
        )
    )
}
