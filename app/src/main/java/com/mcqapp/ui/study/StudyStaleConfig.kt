package com.mcqapp.ui.study

import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.util.Logger

/**
 * Two distinct diagnostics for the two places Study falls back to default
 * scheduling settings when its loaded config is still null (a pull that beats
 * the settings read). Distinct messages tell a support reader *which* fallback
 * fired; the shared [Logger.warnOnce] keeps a repeated condition to one line.
 */
internal const val STALE_CONFIG_CLOCK_MESSAGE =
    "Study config was missing while preparing a card's clock/grade; using defaults"
internal const val STALE_CONFIG_EXPOSURE_MESSAGE =
    "Study config was missing while counting today's exposures; using defaults"
internal const val STALE_CONFIG_GRADE_MESSAGE =
    "Study config was missing while proposing a grade; using defaults"

/**
 * The null-config fallback, kept pure so a JVM test can assert the warning is
 * recorded without building a ViewModel or opening the database.
 */
internal fun studyConfigOrWarn(config: SchedulerConfig?, message: String): SchedulerConfig =
    config ?: SchedulerConfig().also { Logger.warnOnce("STUDY", message) }
