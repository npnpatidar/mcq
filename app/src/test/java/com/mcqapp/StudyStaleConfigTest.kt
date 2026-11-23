package com.mcqapp

import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.ui.study.STALE_CONFIG_CLOCK_MESSAGE
import com.mcqapp.ui.study.STALE_CONFIG_EXPOSURE_MESSAGE
import com.mcqapp.ui.study.STALE_CONFIG_GRADE_MESSAGE
import com.mcqapp.ui.study.studyConfigOrWarn
import com.mcqapp.util.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The stale-config fallbacks in Study must not degrade silently: each call
 * site logs a distinct warning through [Logger.warnOnce], which records it in
 * the error ring a JVM test can read. Pure JVM — no ViewModel or database.
 */
class StudyStaleConfigTest {

    @Before
    fun clearWarnings() {
        Logger.clearRecentErrors()
        Logger.resetWarnOnce()
    }

    @Test
    fun aNullConfigReturnsDefaultsAndRecordsTheWarningOnce() {
        val first = studyConfigOrWarn(null, STALE_CONFIG_CLOCK_MESSAGE)
        assertEquals(SchedulerConfig(), first)
        val warning = "W/STUDY: $STALE_CONFIG_CLOCK_MESSAGE"
        assertEquals(listOf(warning), Logger.recentErrors())

        // A repeated fallback reports once, not once per card pulled.
        studyConfigOrWarn(null, STALE_CONFIG_CLOCK_MESSAGE)
        assertEquals(listOf(warning), Logger.recentErrors())
    }

    @Test
    fun everyCallSiteGetsItsOwnDistinctWarning() {
        studyConfigOrWarn(null, STALE_CONFIG_CLOCK_MESSAGE)
        studyConfigOrWarn(null, STALE_CONFIG_EXPOSURE_MESSAGE)
        studyConfigOrWarn(null, STALE_CONFIG_GRADE_MESSAGE)
        val errors = Logger.recentErrors()
        assertEquals(3, errors.size)
        assertTrue(errors.contains("W/STUDY: $STALE_CONFIG_CLOCK_MESSAGE"))
        assertTrue(errors.contains("W/STUDY: $STALE_CONFIG_EXPOSURE_MESSAGE"))
        assertTrue(errors.contains("W/STUDY: $STALE_CONFIG_GRADE_MESSAGE"))
    }

    @Test
    fun aLoadedConfigIsReturnedUnchangedWithoutWarning() {
        val loaded = SchedulerConfig(leechThreshold = 7)
        assertSame(loaded, studyConfigOrWarn(loaded, STALE_CONFIG_CLOCK_MESSAGE))
        assertTrue("a healthy config must not warn: ${Logger.recentErrors()}", Logger.recentErrors().isEmpty())
    }
}
