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
     * Bounded block sample for Test (section 9.6): shuffle the blocks first,
     * then find an exact fit up to [target] with a 0/1 knapsack over the
     * shuffled order, and add at most one overshooting block within
     * `min(max, target + max(1, ceil(0.25 * target)))`.
     *
     * The shuffle comes first so exactness does not cost randomness: the DP is
     * deterministic over a shuffled block order, and the first block to reach a
     * sum wins, which is the "earlier in shuffled order" tie-break. Passages
     * stay whole, and the result never exceeds [max].
     *
     * Refusal is a value, not an empty order: a caller that cannot render a
     * reason would otherwise start a session with nothing on screen.
     */
    fun sampledBounded(target: Int, max: Int = 150, seed: Long): BoundedSample {
        require(target > 0) { "target must be positive" }
        require(max > 0) { "max must be positive" }
        if (size == 0) return BoundedSample.Refused(BoundedSample.Reason.EMPTY_SCOPE, max)

        val bounds = PassageBlocks.blockBounds(size) { passageOf[order[it]] }
        val blockCount = bounds.size - 1
        val slots = PassageBlocks.shuffleBlocks(blockCount, seed)

        // Pass 1: 0/1 knapsack over the shuffled blocks, capacity = target.
        // Descending sums visit each block at most once; first reach wins.
        val capacity = minOf(target, max, size)
        val reachable = BooleanArray(capacity + 1)
        reachable[0] = true
        val predSum = IntArray(capacity + 1) { -1 }
        val predBlock = IntArray(capacity + 1) { -1 }
        for (slotIndex in slots.indices) {
            val block = slots[slotIndex]
            val blockSize = bounds[block + 1] - bounds[block]
            if (blockSize > capacity) continue
            for (sum in capacity - blockSize downTo 0) {
                if (!reachable[sum] || reachable[sum + blockSize]) continue
                reachable[sum + blockSize] = true
                predSum[sum + blockSize] = sum
                predBlock[sum + blockSize] = slotIndex
            }
        }
        var best = capacity
        while (best > 0 && !reachable[best]) best--

        val chosen = BooleanArray(blockCount)
        var cursor = best
        while (cursor > 0) {
            val slotIndex = predBlock[cursor]
            chosen[slots[slotIndex]] = true
            cursor = predSum[cursor]
        }

        // Pass 2: one overshoot, the smallest remaining block within the limit.
        if (best < target) {
            val overshoot = maxOf(1, (target - 1) / 4 + 1)
            val limit = minOf(max.toLong(), target.toLong() + overshoot).toInt()
            var pick = -1
            var pickSize = Int.MAX_VALUE
            for (slotIndex in slots.indices) {
                val block = slots[slotIndex]
                if (chosen[block]) continue
                val blockSize = bounds[block + 1] - bounds[block]
                if (best + blockSize > limit || blockSize >= pickSize) continue
                pick = block
                pickSize = blockSize
            }
            if (pick >= 0) chosen[pick] = true
        }

        var shown = 0
        for (block in 0 until blockCount) {
            if (chosen[block]) shown += bounds[block + 1] - bounds[block]
        }
        if (shown == 0) {
            val anyFits = (0 until blockCount).any { bounds[it + 1] - bounds[it] <= max }
            val reason = if (anyFits) {
                BoundedSample.Reason.NO_PASSAGE_FITS
            } else {
                BoundedSample.Reason.PASSAGE_TOO_LARGE
            }
            return BoundedSample.Refused(reason, max)
        }

        val out = IntArray(shown)
        var written = 0
        for (slotIndex in slots.indices) {
            val block = slots[slotIndex]
            if (!chosen[block]) continue
            for (row in bounds[block] until bounds[block + 1]) out[written++] = order[row]
        }
        return BoundedSample.Selected(
            order = SessionOrder(pool, starts, out, passages, passageOf),
            short = shown < TestPlan.MIN_LENGTH && size >= TestPlan.MIN_LENGTH
        )
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

    /**
     * The exact sub-order named by [savedIds], in that order, dropping ids the
     * base no longer holds. Unlike [reorderedTo], the rest of the base is not
     * appended: a frozen bounded sample is exactly its saved ids, and adding
     * the unselected questions would silently turn a 20-question test into the
     * whole scope.
     */
    fun subsetInOrder(savedIds: List<String>): SessionOrder? {
        if (savedIds.isEmpty()) return this
        val rowById = HashMap<String, Int>(size * 2)
        for (row in 0 until size) rowById[idOfRow(row)] = row
        val out = IntArray(savedIds.size)
        var written = 0
        val seen = java.util.BitSet(size)
        for (id in savedIds) {
            val row = rowById[id] ?: continue
            if (seen.get(row)) continue
            out[written++] = row
            seen.set(row)
        }
        if (written == 0) return null
        return SessionOrder(pool, starts, out.copyOf(written), passages, passageOf)
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

/**
 * The outcome of [SessionOrder.sampledBounded].
 *
 * A refused sample is a value rather than an empty order so the setup sheet
 * can render the section 9.6 message and keep Start disabled; an empty
 * [SessionOrder] would look like a session that had already started.
 */
sealed interface BoundedSample {

    data class Selected(
        val order: SessionOrder,
        /** A shown count below 20 from a scope of at least 20 (section 16 #1). */
        val short: Boolean
    ) : BoundedSample {
        val shown: Int get() = order.size
    }

    data class Refused(val reason: Reason, val max: Int) : BoundedSample {
        val message: String
            get() = when (reason) {
                Reason.EMPTY_SCOPE -> TestPlan.EMPTY_SCOPE_MESSAGE
                Reason.NO_PASSAGE_FITS ->
                    "No passage fits the requested length and overshoot limit; " +
                        "choose a longer test or split a passage."
                Reason.PASSAGE_TOO_LARGE ->
                    "Every passage is larger than $max; split one or change the scope."
            }
    }

    enum class Reason {
        /** The scope holds no questions at all. */
        EMPTY_SCOPE,

        /** Blocks exist within the cap, but none fit the overshoot limit. */
        NO_PASSAGE_FITS,

        /** Every block is larger than the hard cap. */
        PASSAGE_TOO_LARGE
    }
}
