package com.mcqapp.domain

import com.mcqapp.data.local.SessionRef

import java.io.ByteArrayOutputStream

/**
 * A test session's presented order, held as one string pool plus offsets.
 *
 * A session needs its ordered question ids and their passage membership, and
 * nothing else: the question on screen is resolved by id, the palette and the
 * review sheet read status by index. As a list of pairs that was one object and
 * one String per question — ~90 MB of heap at a million questions — and the
 * shuffle and the drill cut each built another copy of it.
 *
 * Here the ids live in one UTF-8 pool in authored order, [starts] holds each
 * id's offset in it, and [order] maps a presented index to an authored row.
 * Block-shuffling copies four bytes per question instead of ninety, so a
 * shuffled million-question session is ~20 MB rather than ~200.
 *
 * Passage membership is one index per row into [passages] (-1 for none), which
 * is what keeps a comprehension set together through the shuffle and the cut.
 * Both are [PassageBlocks]' rule read over these indices — the same blocks, the
 * same seed draw — so a seed from a snapshot still rebuilds the order a session
 * presented.
 */
class SessionOrder private constructor(
    /** Every id, concatenated in authored order, UTF-8. */
    private val pool: ByteArray,
    /** Authored row -> its offset in [pool]; `starts[size]` is the pool size. */
    private val starts: IntArray,
    /** Presented row -> authored row. */
    private val order: IntArray,
    private val passages: List<String>,
    /** Authored row -> index into [passages], or -1 for a standalone question. */
    private val passageOf: IntArray
) {

    val size: Int get() = order.size

    val isEmpty: Boolean get() = order.isEmpty()

    /** The id presented at [index]. */
    fun idAt(index: Int): String {
        val row = order[index]
        return String(pool, starts[row], starts[row + 1] - starts[row], Charsets.UTF_8)
    }

    /** The id presented at [index], or null when the order does not reach it. */
    fun idAtOrNull(index: Int): String? =
        if (index in 0 until size) idAt(index) else null

    /** The index [id] is presented at, or -1 when the order does not hold it. */
    fun indexOfId(id: String): Int = firstIndexOfAny(java.util.Collections.singleton(id))

    /** The first presented index holding one of [ids], or -1. */
    fun firstIndexOfAny(ids: Set<String>): Int {
        if (ids.isEmpty()) return -1
        // Compares bytes rather than materialising an id per row: this runs
        // over a million presented rows to find one flagged question.
        val targets = ids.map { it.toByteArray(Charsets.UTF_8) }
        for (index in 0 until size) {
            for (target in targets) if (matches(order[index], target)) return index
        }
        return -1
    }

    /** Every id in presented order. For the attempt write, not for a screen. */
    fun idsInOrder(): List<String> = List(size) { idAt(it) }

    /**
     * Block-aware shuffle: passage members move as one block, authored order
     * inside a block is preserved. A no-op when the session does not shuffle,
     * and a view over the same pool either way.
     */
    fun shuffled(seed: Long, shuffle: Boolean): SessionOrder {
        if (!shuffle || size <= 1) return this
        return permuted(seed, cut = Int.MAX_VALUE)
    }

    /**
     * Block-aware sample (D1: all-or-nothing with overshoot): blocks are
     * shuffled whole and taken until [count] is met, so the last one may pass
     * the count rather than split a passage across the cut.
     */
    fun sampled(count: Int, seed: Long): SessionOrder {
        if (count <= 0 || count >= size) return this
        return permuted(seed, cut = count)
    }

    /**
     * Re-presents the saved order, dropping ids that no longer exist. Null
     * means nothing overlapped — the paper changed too much to resume.
     */
    fun reorderedTo(savedIds: List<String>): SessionOrder? {
        if (savedIds.isEmpty()) return this
        val rowById = HashMap<String, Int>(size * 2)
        for (row in 0 until size) rowById[idOfRow(row)] = row
        val out = IntArray(size)
        var written = 0
        val seen = java.util.BitSet(size)
        for (id in savedIds) {
            val row = rowById[id] ?: continue
            // A malformed order can repeat an id or name one twice; without the
            // `seen` guard the repeat would write the same row again (or run
            // `written` past `size` and throw on the fill loop below).
            if (seen.get(row)) continue
            out[written++] = row
            seen.set(row)
        }
        if (written == 0) return null
        for (row in 0 until size) if (!seen.get(row)) out[written++] = row
        return SessionOrder(pool, starts, out, passages, passageOf)
    }

    private fun idOfRow(row: Int): String =
        String(pool, starts[row], starts[row + 1] - starts[row], Charsets.UTF_8)

    private fun matches(row: Int, target: ByteArray): Boolean {
        val start = starts[row]
        val length = starts[row + 1] - start
        if (length != target.size) return false
        for (i in target.indices) if (pool[start + i] != target[i]) return false
        return true
    }

    /**
     * Shuffles whole blocks and re-presents the rows, stopping once [cut] rows
     * are placed (a block is added whole, so it may overshoot).
     */
    private fun permuted(seed: Long, cut: Int): SessionOrder {
        val bounds = PassageBlocks.blockBounds(size) { passageOf[order[it]] }
        val slots = PassageBlocks.shuffleBlocks(bounds.size - 1, seed)
        var take = 0
        for (slot in slots) {
            if (take >= cut) break
            take += bounds[slot + 1] - bounds[slot]
        }
        val out = IntArray(take)
        var written = 0
        for (slot in slots) {
            if (written >= cut) break
            for (row in bounds[slot] until bounds[slot + 1]) out[written++] = order[row]
        }
        return SessionOrder(pool, starts, out, passages, passageOf)
    }

    /**
     * Collects a session's rows as they stream out of the database, one
     * category at a time, without ever holding the refs it read.
     */
    class Builder(expectedSize: Int = 0) {

        private val ids = ByteArrayOutputStream(if (expectedSize > 0) expectedSize * 16 else 1024)
        private var starts = IntArray(if (expectedSize > 0) expectedSize + 1 else 64)
        private var passageOf = IntArray(if (expectedSize > 0) expectedSize else 64)
        private var rows = 0
        private val passages = ArrayList<String>()
        private val passageIndexOf = HashMap<String, Int>()

        fun add(id: String, passageId: String?) {
            if (rows + 1 >= starts.size) starts = starts.copyOf(starts.size * 2)
            if (rows >= passageOf.size) passageOf = passageOf.copyOf(passageOf.size * 2)
            ids.write(id.toByteArray(Charsets.UTF_8))
            rows++
            starts[rows] = ids.size()
            passageOf[rows - 1] = if (passageId == null) {
                -1
            } else {
                passageIndexOf.getOrPut(passageId) {
                    passages.add(passageId)
                    passages.size - 1
                }
            }
        }

        fun build(): SessionOrder = SessionOrder(
            pool = ids.toByteArray(),
            starts = starts.copyOf(rows + 1),
            order = IntArray(rows) { it },
            passages = passages.toList(),
            passageOf = passageOf.copyOf(rows)
        )
    }

    companion object {

        private val EMPTY = Builder().build()

        /** An order with no questions: the state a failed or empty load ends in. */
        fun empty(): SessionOrder = EMPTY

        /** An order over refs a reader already materialised (mistakes, tests). */
        fun of(refs: List<SessionRef>): SessionOrder {
            val builder = Builder(refs.size)
            for (ref in refs) builder.add(ref.id, ref.passageId)
            return builder.build()
        }
    }
}
