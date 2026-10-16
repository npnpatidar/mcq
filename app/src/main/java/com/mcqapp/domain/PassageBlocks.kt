package com.mcqapp.domain

import kotlin.random.Random

/**
 * Passage blocks for test sessions.
 *
 * Togetherness is a correctness requirement in a test: a comprehension
 * question cannot be answered without its passage on screen. So members stay
 * contiguous, shuffling moves the whole block, and a drill samples whole
 * blocks — the last one may overshoot the requested count rather than split
 * a passage across the cut.
 */
object PassageBlocks {

    /**
     * Groups items into blocks by passage membership: standalone items become
     * single-item blocks, members of one passage share one block. Authored
     * order inside a block is preserved (D2: member order is stable by design,
     * the shuffle story lives in the block sequence).
     *
     * Generic so a test session can order its lightweight refs — ids and
     * passage membership, never question content — and resolve the one
     * question on screen by id.
     */
    fun <T> group(items: List<T>, passageId: (T) -> String?): List<List<T>> {
        if (items.none { passageId(it) != null }) return items.map { listOf(it) }
        val out = mutableListOf<List<T>>()
        var i = 0
        while (i < items.size) {
            val current = passageId(items[i])
            if (current == null) {
                out.add(listOf(items[i]))
                i++
            } else {
                val end = (i + 1 until items.size).firstOrNull { idx ->
                    passageId(items[idx]) != current
                } ?: items.size
                out.add(items.subList(i, end).toList())
                i = end
            }
        }
        return out
    }

    fun group(questions: List<Question>): List<List<Question>> =
        group(questions) { it.passageId }

    /**
     * Block-aware shuffle of [items]: blocks are shuffled like items, member
     * order inside a block stays as authored. Same seed story as [Shuffle]:
     * one stream, reproducible from logs.
     */
    fun <T> shuffleAttempt(
        items: List<T>,
        seed: Long,
        shuffle: Boolean,
        passageId: (T) -> String?
    ): List<T> {
        if (!shuffle) return items
        return group(items, passageId).shuffled(Random(seed)).flatten()
    }

    fun shuffleAttempt(
        questions: List<Question>,
        seed: Long,
        shuffleQuestions: Boolean,
        @Suppress("UNUSED_PARAMETER") shuffleOptions: Boolean
    ): List<Question> =
        shuffleAttempt(questions, seed, shuffleQuestions) { it.passageId }

    /**
     * Block-aware drill sample (D1: all-or-nothing with overshoot). Blocks
     * are shuffled whole and taken until [count] is met; the last block may
     * overshoot, because excluding a passage that does not fit could drop its
     * easiest questions and break contiguity.
     */
    fun <T> sample(items: List<T>, count: Int, seed: Long, passageId: (T) -> String?): List<T> {
        if (count <= 0 || count >= items.size) return items
        if (items.none { passageId(it) != null }) {
            // Same shape as Drill.sample: shuffle once, take the cut.
            return items.shuffled(Random(seed)).take(count)
        }
        val blocks = group(items, passageId).shuffled(Random(seed))
        val out = mutableListOf<T>()
        for (block in blocks) {
            if (out.size >= count) break
            out.addAll(block)
        }
        return out
    }

    fun sample(questions: List<Question>, count: Int, seed: Long): List<Question> =
        sample(questions, count, seed) { it.passageId }
}
