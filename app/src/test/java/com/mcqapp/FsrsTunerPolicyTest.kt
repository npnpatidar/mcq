package com.mcqapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.FsrsTuner
import com.mcqapp.data.repository.SettingsStore
import com.mcqapp.data.repository.dataStore
import com.mcqapp.domain.FsrsFitResult
import com.mcqapp.domain.FsrsScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FSRS-011: "Optimize" must not replace the learner's weights with a worse set.
 *
 * The tuner's decision is pinned here through [FsrsTuner.storeIfImproved] with
 * fabricated fit results — the one seam that can express a regressing, an
 * equal and an improving fit without rigging the optimizer's input data (CI
 * x86_64; this host cannot run the SQLite-backed store).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FsrsTunerPolicyTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var tuner: FsrsTuner

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.dataStore.edit { it.clear() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsStore(context)
        tuner = FsrsTuner(db, settings)
    }

    @After
    fun tearDown() {
        db.close()
        runBlocking {
            ApplicationProvider.getApplicationContext<Context>().dataStore.edit { it.clear() }
        }
    }

    private fun result(
        lossBefore: Double,
        lossAfter: Double,
        parameters: List<Double> = FsrsScheduler.DEFAULT_FSRS_PARAMETERS
    ) = FsrsFitResult(
        parameters = parameters,
        predictions = 400,
        items = 420,
        cards = 80,
        lossBefore = lossBefore,
        lossAfter = lossAfter,
        steps = 9
    )

    /** A regressing fit leaves both the weights and the fit version untouched. */
    @Test
    fun aRegressingFitIsNotStored() = runBlocking {
        val candidate = FsrsScheduler.DEFAULT_FSRS_PARAMETERS.mapIndexed { index, value ->
            if (index == 10) value * 1.4 else value
        }
        val before = settings.schedulerConfigNow()

        assertFalse(tuner.storeIfImproved(result(0.52, 0.61, candidate)))

        val after = settings.schedulerConfigNow()
        assertEquals(before.fsrsParameters, after.fsrsParameters)
        assertEquals(before.fsrsFitVersion, after.fsrsFitVersion)
    }

    /**
     * Equal loss is explicitly *not* an improvement: the learner keeps what they
     * have rather than having a fresh fit bump the provenance counter.
     */
    @Test
    fun anEqualLossIsNotAnImprovement() = runBlocking {
        val candidate = FsrsScheduler.DEFAULT_FSRS_PARAMETERS.mapIndexed { index, value ->
            if (index == 10) value * 1.4 else value
        }
        val before = settings.schedulerConfigNow()

        assertFalse(tuner.storeIfImproved(result(0.44, 0.44, candidate)))
        assertEquals(before.fsrsParameters, settings.schedulerConfigNow().fsrsParameters)
        assertEquals(before.fsrsFitVersion, settings.schedulerConfigNow().fsrsFitVersion)
    }

    /** A strictly better fit is stored, and the fit version advances with it. */
    @Test
    fun aStrictlyImprovingFitIsStored() = runBlocking {
        val candidate = FsrsScheduler.DEFAULT_FSRS_PARAMETERS.mapIndexed { index, value ->
            if (index == 10) value * 1.4 else value
        }
        val before = settings.schedulerConfigNow()

        assertTrue(tuner.storeIfImproved(result(0.52, 0.41, candidate)))

        val after = settings.schedulerConfigNow()
        assertEquals(candidate, after.fsrsParameters)
        assertTrue(after.fsrsFitVersion > before.fsrsFitVersion)
    }

    /**
     * A non-finite loss cannot be "better": `NaN` fails every comparison, and a
     * fit that produced one must not overwrite working weights.
     */
    @Test
    fun aNonFiniteLossIsNeverStored() = runBlocking {
        val candidate = FsrsScheduler.DEFAULT_FSRS_PARAMETERS.mapIndexed { index, value ->
            if (index == 10) value * 1.4 else value
        }
        val before = settings.schedulerConfigNow()

        assertFalse(tuner.storeIfImproved(result(0.52, Double.NaN, candidate)))
        assertFalse(tuner.storeIfImproved(result(Double.NaN, 0.41, candidate)))
        assertFalse(tuner.storeIfImproved(result(Double.NaN, Double.NaN, candidate)))

        assertEquals(before.fsrsParameters, settings.schedulerConfigNow().fsrsParameters)
    }
}
