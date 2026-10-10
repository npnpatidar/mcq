package com.mcqapp.domain

import kotlin.random.Random

/**
 * Passage blocks for test sessions (D1/D2 in PASSAGE-QUESTIONS.md).
 *
 * Togetherness is a correctness requirement in a test: a comprehension
 * question cannot be answered without its passage on screen. So members stay
 * contiguous, shuffling moves the whole block, and a drill samples whole
 * blocks — the last one may overshoot the requested count rather than split
 * a passage across the cut.
 */
object PassageBlocks {

    /**
     * Groups a question list into blocks: standalone questions become
     * single-question blocks, passage members share one block. Authored order
     * inside a block is preserved (D2: member order is stable by design, the
     * shuffle story lives in the block sequence).
     */
    fun group(questions: List<Question>): List<List<Question>> {
        if (questions.none { it.passageId != null }) return questions.map { listOf(it) }
        val out = mutableListOf<List<Question>>()
        var i = 0
        while (i < questions.size) {
            val passageId = questions[i].passageId
            if (passageId == null) {
                out.add(listOf(questions[i]))
                i++
            } else {
                val end = (i + 1 until questions.size).firstOrNull { idx ->
                    questions[idx].passageId != passageId
                } ?: questions.size
                out.add(questions.subList(i, end).toList())
                i = end
            }
        }
        return out
    }

    /**
     * Block-aware shuffle: blocks are shuffled like items, member order
     * inside each block stays as authored. Same seed story as [Shuffle]:
     * one stream, reproducible from logs.
     */
    fun shuffleAttempt(
        questions: List<Question>,
        seed: Long,
        shuffleQuestions: Boolean,
        shuffleOptions: Boolean
    ): List<Question> {
        if (!shuffleQuestions && !shuffleOptions) return questions
        val random = Random(seed)
        val grouped = group(questions)
        val blocks = if (shuffleQuestions) grouped.shuffled(random) else grouped
        val ordered = blocks.flatten()
        if (!shuffleOptions) return ordered
        return ordered.map { q -> q.copy(options = q.options.shuffled(random)) }
    }

    /**
     * Block-aware drill sample (D1: all-or-nothing with overshoot). Blocks
     * are shuffled whole and taken until [count] is met; the last block may
     * overshoot, because excluding a passage that does not fit could drop its
     * easiest questions and break contiguity.
     */
    fun sample(questions: List<Question>, count: Int, seed: Long): List<Question> {
        if (count <= 0 || count >= questions.size) return questions
        if (questions.none { it.passageId != null }) {
            return Drill.sample(questions, count, seed)
        }
        val random = Random(seed)
        val blocks = group(questions).shuffled(random)
        val out = mutableListOf<Question>()
        for (block in blocks) {
            if (out.size >= count) break
            out.addAll(block)
        }
        return out
    }
}
