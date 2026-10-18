package com.mcqapp.domain

import kotlin.random.Random
import kotlin.random.asJavaRandom

/**
 * Passage blocks: the rule that keeps a comprehension set together in a test
 * session.
 *
 * Togetherness is a correctness requirement in a test: a comprehension
 * question cannot be answered without its passage on screen. So members stay
 * contiguous, shuffling moves the whole block, and a drill samples whole
 * blocks — the last one may overshoot the requested count rather than split a
 * passage across the cut.
 *
 * The session holds its rows as primitives ([SessionOrder]), so the rule is
 * expressed over presented indices and one membership lookup rather than over
 * lists of items: a million-question session must not build one list per block
 * to move them. Authored order inside a block never moves — the boundaries are
 * the only thing that does.
 */
object PassageBlocks {

    /**
     * Block boundaries over the presented rows: [member] names the passage the
     * row at `i` belongs to, or -1 for a standalone question. A run of rows
     * naming the same passage is one block, and every standalone row is a
     * block of its own. Returns `blocks + 1` offsets, so block `b` spans
     * `bounds[b] until bounds[b + 1]`.
     */
    fun blockBounds(size: Int, member: (Int) -> Int): IntArray {
        if (size <= 0) return intArrayOf(0)
        val bounds = IntArray(size + 1)
        var blocks = 0
        bounds[blocks++] = 0
        for (i in 1 until size) {
            val current = member(i)
            if (current < 0 || current != member(i - 1)) bounds[blocks++] = i
        }
        bounds[blocks] = size
        return bounds.copyOf(blocks + 1)
    }

    /**
     * The order a seed puts [blocks] blocks in — what a session shuffle and a
     * drill cut both read.
     *
     * The draw goes through `java.util.Random.nextInt` rather than
     * `Random.nextInt`, because that is what `List.shuffled(Random)` draws:
     * the seed a snapshot stores has to rebuild the order the session
     * presented, and `PassageBlocksTest` pins it against that very call.
     */
    fun shuffleBlocks(blocks: Int, seed: Long): IntArray {
        val slots = IntArray(blocks) { it }
        val random = Random(seed).asJavaRandom()
        for (i in blocks - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val swap = slots[i]
            slots[i] = slots[j]
            slots[j] = swap
        }
        return slots
    }
}
