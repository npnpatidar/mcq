package com.mcqapp.ui.stats.sections

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.stats.Calibration
import com.mcqapp.domain.stats.GroupRetention
import com.mcqapp.domain.stats.IntervalBand
import com.mcqapp.domain.stats.LadderChange
import com.mcqapp.domain.stats.LadderPolicy
import com.mcqapp.domain.stats.RetentionData
import com.mcqapp.ui.stats.charts.CalibrationChart
import com.mcqapp.ui.stats.charts.ChartCard
import com.mcqapp.ui.stats.charts.ChartEmpty
import com.mcqapp.ui.stats.charts.ChartSubtitle
import com.mcqapp.ui.stats.charts.ChartTitle
import com.mcqapp.ui.stats.charts.LegendDot
import com.mcqapp.ui.stats.charts.LineChart
import com.mcqapp.ui.stats.charts.LineSeries
import com.mcqapp.ui.stats.charts.MeterBar
import com.mcqapp.ui.stats.charts.formatPct
import kotlin.math.roundToInt

/**
 * Tab 2: retention over time with the desired-retention reference, FSRS
 * calibration (predicted vs actual recall), and retention broken down by
 * paper/category and weekday.
 */
@Composable
internal fun RetentionSection(
    bundle: RetentionData,
    onApplyLadderProposal: (LadderPolicy.Proposal) -> Unit = {},
    onDismissLadderProposal: (LadderPolicy.Proposal) -> Unit = {},
    onUndoLadderChange: () -> Unit = {},
    ladderBusy: Boolean = false,
    ladderError: String? = null
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(contentType = "trend") { TrendCard(bundle) }
        item(contentType = "calibration") {
            CalibrationCard(
                bundle = bundle,
                onApplyLadderProposal = onApplyLadderProposal,
                onDismissLadderProposal = onDismissLadderProposal,
                onUndoLadderChange = onUndoLadderChange,
                ladderBusy = ladderBusy,
                ladderError = ladderError
            )
        }
        if (bundle.groupRetention.isNotEmpty()) {
            item(contentType = "group-retention") { GroupRetentionCard(bundle) }
        }
        item(contentType = "interval") { IntervalCard(bundle) }
        if (bundle.tagRetention.isNotEmpty()) {
            item(contentType = "tag-retention") { SliceCard(bundle.tagRetention, R.string.stats_by_tag, R.string.stats_by_tag_help, "stats-tag-retention") }
        }
        if (bundle.categoryRetention.isNotEmpty()) {
            item(contentType = "category-retention") { SliceCard(bundle.categoryRetention, R.string.stats_by_category_all, R.string.stats_by_category_all_help, "stats-category-retention") }
        }
        item(contentType = "weekday") { WeekdayCard(bundle) }
    }
}

@Composable
private fun TrendCard(bundle: RetentionData) {
    val trend = bundle.retentionTrend
    val labels = trend.points.map { it.label }
    ChartCard(
        testTag = "stats-retention-trend",
        title = { ChartTitle(R.string.stats_retention_trend) },
        subtitle = { ChartSubtitle(R.string.stats_retention_trend_help) }
    ) {
        LineChart(
            labels = labels,
            series = listOf(
                LineSeries(
                    values = trend.points.map { it.value },
                    color = MaterialTheme.colorScheme.primary,
                    fill = true
                ),
                LineSeries(
                    values = trend.rolling.map { it.value },
                    color = MaterialTheme.colorScheme.tertiary
                )
            ),
            fixedMax = 1.0,
            referenceLine = trend.desiredRetention,
            yLabel = { formatPct(it) },
            emptyText = { ChartEmpty(R.string.stats_no_data) }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendEntry(MaterialTheme.colorScheme.primary, R.string.stats_actual)
            LegendEntry(MaterialTheme.colorScheme.tertiary, R.string.stats_rolling)
            LegendEntry(
                MaterialTheme.colorScheme.onSurfaceVariant,
                R.string.stats_desired_retention
            )
        }
    }
}

@Composable
private fun LegendEntry(color: Color, @StringRes labelRes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LegendDot(color)
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CalibrationCard(
    bundle: RetentionData,
    onApplyLadderProposal: (LadderPolicy.Proposal) -> Unit,
    onDismissLadderProposal: (LadderPolicy.Proposal) -> Unit,
    onUndoLadderChange: () -> Unit,
    ladderBusy: Boolean,
    ladderError: String?
) {
    val calibration = bundle.calibration
    ChartCard(
        testTag = "stats-calibration",
        title = { ChartTitle(R.string.stats_calibration) },
        subtitle = { ChartSubtitle(R.string.stats_calibration_help) }
    ) {
        if (calibration.samples == 0) {
            ChartEmpty(R.string.stats_calibration_empty)
            return@ChartCard
        }
        CalibrationChart(
            points = calibration.bins.map { bin ->
                Triple(bin.predictedMean, bin.actualRate ?: 0.0, bin.count)
            }
        )
        Text(
            stringResource(
                R.string.stats_calibration_summary,
                formatPct(calibration.predictedMean ?: 0.0),
                formatPct(calibration.actualMean ?: 0.0),
                calibration.samples,
                (calibration.brier ?: 0.0).roundTo3()
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        bundle.calibrationReport?.let { report ->
            CalibrationEvidence(
                report = report,
                bundle = bundle,
                onApplyLadderProposal = onApplyLadderProposal,
                onDismissLadderProposal = onDismissLadderProposal,
                onUndoLadderChange = onUndoLadderChange,
                ladderBusy = ladderBusy,
                ladderError = ladderError
            )
        }
    }
}

/**
 * The grade-level evidence (plan phase 2), inside the existing calibration
 * card: the per-grade table, the adjacent contrast with its interval, and the
 * honest "not enough evidence" lines. Deliberately plain text — the decision
 * this feeds is months away at normal volume, and the report must say so
 * rather than look like a dashboard that is failing.
 */
@Composable
private fun CalibrationEvidence(
    report: Calibration.Report,
    bundle: RetentionData,
    onApplyLadderProposal: (LadderPolicy.Proposal) -> Unit,
    onDismissLadderProposal: (LadderPolicy.Proposal) -> Unit,
    onUndoLadderChange: () -> Unit,
    ladderBusy: Boolean,
    ladderError: String?
) {
    Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!report.hasPairs) {
            Text(
                stringResource(R.string.stats_calibration_excluded, report.excluded),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        report.grades.filter { it.pairs > 0 }.forEach { grade ->
            Text(
                stringResource(
                    R.string.stats_calibration_grade_row,
                    grade.grade.stored,
                    grade.pairs,
                    grade.cards,
                    formatPct(grade.recall ?: 0.0),
                    formatPct(grade.predictedMean ?: 0.0)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ContrastLine(
            label = stringResource(R.string.stats_calibration_easy_vs_good),
            contrast = report.contrast1,
            insufficient = report.contrast1.low == null
        )
        ContrastLine(
            label = stringResource(R.string.stats_calibration_hard_vs_good),
            contrast = report.contrast2,
            insufficient = report.hardUnderpowered || report.contrast2.low == null
        )
        report.followUp.filter { it.grade != Calibration.LadderGrade.HARD }.forEach { follow ->
            Text(
                stringResource(
                    R.string.stats_calibration_follow_up,
                    follow.grade.stored,
                    formatPct(follow.rate ?: 0.0),
                    follow.due
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        report.firstExposure.filter { it.count > 0 }.forEach { exposure ->
            Text(
                stringResource(
                    R.string.stats_calibration_first_exposure,
                    exposure.grade.stored,
                    exposure.count,
                    formatPct(exposure.recall ?: 0.0)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            stringResource(
                if (report.followUpGatePassed) R.string.stats_calibration_follow_up_met
                else R.string.stats_calibration_follow_up_not_met
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val precision = report.readiness.halfWidth?.let { "±" + formatPp(it) + " pp" } ?: "—"
        Text(
            stringResource(
                R.string.stats_calibration_readiness,
                report.readiness.pairs,
                report.readiness.bindingCards,
                precision,
                report.readiness.calendarDays
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LadderDecision(
            report = report,
            evaluation = bundle.ladderEvaluation,
            canRollback = bundle.ladderCanRollback,
            lastChange = bundle.ladderLastChange,
            onApply = onApplyLadderProposal,
            onDismiss = onDismissLadderProposal,
            onUndo = onUndoLadderChange,
            busy = ladderBusy,
            error = ladderError
        )
    }
}

/**
 * Phase 4's controller line and controls. A proposal is the only state with
 * buttons; every other state says why no change is being made, so a stalled
 * controller and a patient one look different.
 */
@Composable
private fun LadderDecision(
    report: Calibration.Report,
    evaluation: LadderPolicy.Evaluation?,
    canRollback: Boolean,
    lastChange: LadderChange?,
    onApply: (LadderPolicy.Proposal) -> Unit,
    onDismiss: (LadderPolicy.Proposal) -> Unit,
    onUndo: () -> Unit,
    busy: Boolean,
    error: String?
) {
    Text(
        stringResource(R.string.stats_ladder_auto),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 6.dp)
    )
    val proposal = evaluation?.proposal
    when (evaluation?.status) {
        LadderPolicy.Status.PROPOSE -> {
            if (proposal != null) {
                Text(
                    stringResource(
                        R.string.stats_ladder_proposal,
                        proposal.currentEasySeconds,
                        proposal.easySeconds,
                        proposal.stepSeconds,
                        formatSignedPp(proposal.delta1),
                        formatSignedPp(proposal.delta1Low),
                        formatSignedPp(proposal.delta1High)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onApply(proposal) },
                        enabled = !busy,
                        modifier = Modifier.testTag("stats-ladder-apply")
                    ) { Text(stringResource(R.string.stats_ladder_apply)) }
                    OutlinedButton(
                        onClick = { onDismiss(proposal) },
                        enabled = !busy,
                        modifier = Modifier.testTag("stats-ladder-dismiss")
                    ) { Text(stringResource(R.string.stats_ladder_dismiss)) }
                }
            }
        }
        LadderPolicy.Status.NOT_READY -> Text(
            stringResource(
                R.string.stats_ladder_not_ready,
                report.readiness.pairs,
                Calibration.MIN_PAIRS,
                report.readiness.bindingCards,
                Calibration.MIN_CARDS_PER_CLASS,
                report.readiness.halfWidth?.let { formatPp(it) } ?: "—",
                formatPp(Calibration.MAX_HALF_WIDTH)
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LadderPolicy.Status.BLOCK_OPEN -> Text(
            stringResource(R.string.stats_ladder_block_open),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LadderPolicy.Status.BLOCK_EMPTY -> Text(
            stringResource(R.string.stats_ladder_block_empty),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LadderPolicy.Status.ALREADY_DECIDED -> Text(
            stringResource(R.string.stats_ladder_decided),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LadderPolicy.Status.NO_EFFECT -> Text(
            stringResource(
                R.string.stats_ladder_no_effect,
                formatSignedPp(report.contrast1.value ?: 0.0),
                formatSignedPp(report.contrast1.low ?: 0.0),
                formatSignedPp(report.contrast1.high ?: 0.0)
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LadderPolicy.Status.REFIT -> Text(
            stringResource(R.string.stats_ladder_refit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LadderPolicy.Status.BOUNDS -> Text(
            stringResource(R.string.stats_ladder_bounds),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        null -> Unit
    }
    if (lastChange != null) {
        Text(
            stringResource(
                R.string.stats_ladder_last_change,
                lastChange.fromEasySeconds,
                lastChange.toEasySeconds
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (report.hardUnderpowered) {
        Text(
            stringResource(
                R.string.stats_ladder_hard_note,
                report.grades.first { it.grade == Calibration.LadderGrade.HARD }.pairs
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (canRollback) {
        TextButton(
            onClick = onUndo,
            enabled = !busy,
            modifier = Modifier.testTag("stats-ladder-undo")
        ) { Text(stringResource(R.string.stats_ladder_undo)) }
    }
    if (error != null) {
        Text(
            stringResource(R.string.stats_ladder_error, error),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun ContrastLine(
    label: String,
    contrast: Calibration.Contrast,
    insufficient: Boolean
) {
    val decision = when {
        insufficient || contrast.low == null || contrast.high == null ->
            stringResource(R.string.stats_calibration_not_enough)
        contrast.direction == Calibration.Direction.LOWER ->
            stringResource(R.string.stats_calibration_lower)
        contrast.direction == Calibration.Direction.RAISE ->
            stringResource(R.string.stats_calibration_raise)
        else -> stringResource(R.string.stats_calibration_no_change)
    }
    Text(
        stringResource(
            R.string.stats_calibration_contrast,
            label,
            formatSignedPp(contrast.value ?: 0.0),
            formatSignedPp(contrast.low ?: 0.0),
            formatSignedPp(contrast.high ?: 0.0),
            decision
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** A probability as a one-decimal percentage point value. */
private fun formatPp(value: Double): String =
    String.format(java.util.Locale.ENGLISH, "%.1f", value * 100)

/** Signed percentage points, for a contrast or an interval bound. */
private fun formatSignedPp(value: Double): String {
    val text = String.format(java.util.Locale.ENGLISH, "%.1f", value * 100)
    return if (value >= 0) "+$text" else text
}

private fun Double.roundTo3(): String = String.format(java.util.Locale.ENGLISH, "%.3f", this)

@Composable
private fun GroupRetentionCard(bundle: RetentionData) {
    ChartCard(
        testTag = "stats-group-retention",
        title = {
            ChartTitle(
                if (bundle.groupIsPaper) R.string.stats_by_group else R.string.stats_by_category_all
            )
        },
        subtitle = { ChartSubtitle(R.string.stats_by_group_help) }
    ) {
        bundle.groupRetention.forEach { group ->
            RetentionBar(
                label = group.label,
                reviews = group.reviews,
                retention = group.retention
            )
        }
    }
}

@Composable
private fun IntervalCard(bundle: RetentionData) {
    ChartCard(
        testTag = "stats-interval-retention",
        title = { ChartTitle(R.string.stats_by_interval) },
        subtitle = { ChartSubtitle(R.string.stats_by_interval_help) }
    ) {
        bundle.intervalRetention.forEach { band ->
            RetentionBar(
                label = intervalLabel(band.band),
                reviews = band.reviews,
                retention = band.retention
            )
        }
    }
}

/** The plan's six bands, in their fixed order (plan §6.1). */
@Composable
private fun intervalLabel(band: IntervalBand): String = when (band) {
    IntervalBand.FIRST -> stringResource(R.string.stats_interval_first)
    IntervalBand.D1_7 -> stringResource(R.string.stats_interval_1_7)
    IntervalBand.D8_21 -> stringResource(R.string.stats_interval_8_21)
    IntervalBand.D22_60 -> stringResource(R.string.stats_interval_22_60)
    IntervalBand.D61_180 -> stringResource(R.string.stats_interval_61_180)
    IntervalBand.D181_PLUS -> stringResource(R.string.stats_interval_181_plus)
}

/** A weakest-first slice (tags or all-papers categories, plan §6.2/§6.3). */
@Composable
private fun SliceCard(
    rows: List<GroupRetention>,
    @StringRes titleRes: Int,
    @StringRes helpRes: Int,
    testTag: String
) {
    ChartCard(
        testTag = testTag,
        title = { ChartTitle(titleRes) },
        subtitle = { ChartSubtitle(helpRes) }
    ) {
        rows.forEach { row ->
            RetentionBar(
                label = row.label,
                reviews = row.reviews,
                retention = row.retention
            )
        }
    }
}

@Composable
private fun WeekdayCard(bundle: RetentionData) {
    ChartCard(
        testTag = "stats-weekday",
        title = { ChartTitle(R.string.stats_by_weekday) }
    ) {
        val dayLabels = listOf(
            R.string.weekday_mon, R.string.weekday_tue, R.string.weekday_wed,
            R.string.weekday_thu, R.string.weekday_fri, R.string.weekday_sat, R.string.weekday_sun
        )
        bundle.weekdayRetention.forEach { day ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(dayLabels[day.weekday]),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column(Modifier.weight(1f)) {
                    MeterBar(
                        fraction = day.retention ?: 0.0,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(0.dp))
                Text(
                    retentionValue(day.retention, day.reviews),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun RetentionBar(label: String, reviews: Int, retention: Double?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                retentionValue(retention, reviews),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        MeterBar(
            fraction = retention ?: 0.0,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** "92% · 148" or an em dash with the review count. */
private fun retentionValue(retention: Double?, reviews: Int): String =
    (retention?.let { formatPct(it) } ?: "—") + " · " + reviews
