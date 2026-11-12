package com.mcqapp

import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.stats.Calibration
import com.mcqapp.domain.stats.LadderPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ladder's decision guards (plan phase 4), pure JVM. Readiness is not
 * re-derived here — phase 2 owns it — so each case isolates one reason the
 * controller must decline or one step it may offer.
 */
class LadderPolicyTest {

    private fun grade(
        grade: Calibration.LadderGrade,
        pairs: Int = 100,
        cards: Int = 80
    ) = Calibration.GradeStat(
        grade = grade,
        pairs = pairs,
        cards = cards,
        recall = 0.9,
        predictedMean = 0.9,
        meanResidual = 0.0
    )

    private fun report(
        delta: Double = -0.08,
        low: Double = -0.13,
        high: Double = -0.03,
        halfWidth: Double = 0.05,
        pairs: Int = 400,
        cards: Int = 120,
        days: Int = 120,
        refit: Boolean = false,
        followUpPassed: Boolean = true,
        blockClosed: Boolean = true
    ) = Calibration.Report(
        pairs = pairs,
        excluded = 0,
        exclusions = emptyList(),
        grades = listOf(
            grade(Calibration.LadderGrade.HARD, pairs = 40, cards = 35),
            grade(Calibration.LadderGrade.GOOD),
            grade(Calibration.LadderGrade.EASY)
        ),
        contrast1 = Calibration.Contrast(delta, low, high, Calibration.IntervalMethod.BCA, cards),
        contrast2 = Calibration.Contrast(-0.01, -0.09, 0.07, Calibration.IntervalMethod.BCA, 35),
        followUp = listOf(
            Calibration.FollowUp(Calibration.LadderGrade.HARD, 40, 38),
            Calibration.FollowUp(Calibration.LadderGrade.GOOD, 100, 92),
            Calibration.FollowUp(Calibration.LadderGrade.EASY, 100, 94)
        ),
        followUpGatePassed = followUpPassed,
        followUpGap = 0.02,
        firstExposure = emptyList(),
        blocks = listOf(
            Calibration.Block(
                index = 0,
                startAt = 1_000L,
                endAt = 1_000L + 28L * 86_400_000L,
                pairs = pairs,
                easyPairs = 100,
                goodPairs = 100,
                hardPairs = 40,
                closed = blockClosed
            )
        ),
        epochStart = 1_000L,
        epochDays = days,
        readiness = Calibration.Readiness(
            pairs = pairs,
            bindingCards = cards,
            halfWidth = halfWidth,
            calendarDays = days,
            refitInEpoch = refit
        ),
        hardUnderpowered = true
    )

    private val now = 1_000L + 120L * 86_400_000L

    private fun evaluate(
        report: Calibration.Report = report(),
        config: SchedulerConfig = SchedulerConfig(ladderVersion = 1),
        decided: Set<Long> = emptySet()
    ) = LadderPolicy.evaluate(report, config, decided, now)

    @Test
    fun `an underperforming Easy window proposes one lower step`() {
        val evaluation = evaluate()

        assertEquals(LadderPolicy.Status.PROPOSE, evaluation.status)
        val proposal = evaluation.proposal!!
        assertEquals(7L, proposal.easySeconds)
        assertEquals(8L, proposal.currentEasySeconds)
        assertEquals(1L, proposal.stepSeconds)
        assertEquals(Calibration.Direction.LOWER, proposal.direction)
        assertEquals("lower-c1", proposal.outcome)
        assertEquals(1_000L, proposal.block.startAt)
        assertEquals(100, proposal.easyPairs)
    }

    @Test
    fun `an overperforming Easy window proposes one raise`() {
        val evaluation = evaluate(
            report(delta = 0.08, low = 0.03, high = 0.13)
        )

        assertEquals(LadderPolicy.Status.PROPOSE, evaluation.status)
        assertEquals(9L, evaluation.proposal!!.easySeconds)
        assertEquals("raise-c1", evaluation.proposal!!.outcome)
    }

    @Test
    fun `an effect below five points is left alone`() {
        val evaluation = evaluate(
            report(delta = -0.04, low = -0.06, high = -0.02, halfWidth = 0.02)
        )
        assertEquals(LadderPolicy.Status.NO_EFFECT, evaluation.status)
        assertNull(evaluation.proposal)
    }

    @Test
    fun `an interval that includes zero is left alone`() {
        val evaluation = evaluate(
            report(delta = -0.08, low = -0.15, high = 0.03)
        )
        assertEquals(LadderPolicy.Status.NO_EFFECT, evaluation.status)
    }

    @Test
    fun `a volume or card shortfall blocks a proposal`() {
        assertEquals(
            LadderPolicy.Status.NOT_READY,
            evaluate(report(pairs = 299)).status
        )
        assertEquals(
            LadderPolicy.Status.NOT_READY,
            evaluate(report(cards = 39)).status
        )
    }

    @Test
    fun `a refit inside the epoch freezes the ladder`() {
        assertEquals(LadderPolicy.Status.REFIT, evaluate(report(refit = true)).status)
    }

    @Test
    fun `a failed follow-up gate blocks a proposal`() {
        assertEquals(
            LadderPolicy.Status.NOT_READY,
            evaluate(report(followUpPassed = false)).status
        )
    }

    @Test
    fun `an open block or a decided block proposes nothing`() {
        assertEquals(
            LadderPolicy.Status.BLOCK_OPEN,
            evaluate(report(blockClosed = false)).status
        )
        assertEquals(
            LadderPolicy.Status.ALREADY_DECIDED,
            evaluate(decided = setOf(1_000L)).status
        )
    }

    @Test
    fun `an empty latest block does not let an older block stand in`() {
        assertEquals(
            LadderPolicy.Status.BLOCK_EMPTY,
            evaluate(report().copy(blocks = emptyList())).status
        )
    }

    @Test
    fun `an unversioned ladder cannot propose`() {
        assertEquals(
            LadderPolicy.Status.NOT_READY,
            evaluate(config = SchedulerConfig(ladderVersion = 0)).status
        )
    }

    @Test
    fun `the step is ten percent, at least one second, capped near the default`() {
        assertEquals(1L, LadderPolicy.stepSeconds(8L))
        assertEquals(1L, LadderPolicy.stepSeconds(10L))
        assertEquals(2L, LadderPolicy.stepSeconds(20L))
        assertEquals(3L, LadderPolicy.stepSeconds(30L))
        assertEquals(6L, LadderPolicy.stepSeconds(60L))
        assertEquals(1L, LadderPolicy.stepSeconds(3L))
    }

    @Test
    fun `a step outside the allowed cut range is withheld`() {
        assertEquals(
            LadderPolicy.Status.BOUNDS,
            evaluate(
                report(delta = -0.08, low = -0.13, high = -0.03),
                config = SchedulerConfig(fastSeconds = 3L, slowSeconds = 30L, ladderVersion = 1)
            ).status
        )
        assertEquals(
            LadderPolicy.Status.BOUNDS,
            evaluate(
                report(delta = 0.08, low = 0.03, high = 0.13),
                config = SchedulerConfig(fastSeconds = 60L, slowSeconds = 90L, ladderVersion = 1)
            ).status
        )
        assertEquals(
            LadderPolicy.Status.BOUNDS,
            evaluate(
                report(delta = 0.08, low = 0.03, high = 0.13),
                config = SchedulerConfig(fastSeconds = 27L, slowSeconds = 30L, ladderVersion = 1)
            ).status
        )
    }

    @Test
    fun `a proposal carries the version and the evidence for its block row`() {
        val evaluation = evaluate(
            config = SchedulerConfig(fastSeconds = 20L, slowSeconds = 45L, ladderVersion = 7, fsrsFitVersion = 3)
        )
        val proposal = evaluation.proposal!!

        assertEquals(7, proposal.ladderVersion)
        assertEquals(3, proposal.fsrsFitVersion)
        assertEquals(2L, proposal.stepSeconds)
        assertEquals(18L, proposal.easySeconds)
        assertEquals(45L, proposal.currentSlowSeconds)
        assertEquals(0.92, proposal.followUpGood)
    }
}
