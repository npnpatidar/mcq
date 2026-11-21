package com.mcqapp.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.BlueprintQuotas
import com.mcqapp.domain.BlueprintSection
import com.mcqapp.domain.BlueprintSectionView
import com.mcqapp.domain.BlueprintSummary
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.Paper
import com.mcqapp.util.Logger
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Serialises the editor's parts to a String so `rememberSaveable` can restore
 * the learner's weights after a configuration change. Control characters
 * delimit fields; category ids never contain them. Exposed for the rotation
 * regression test.
 */
internal val BlueprintPartsSaver = Saver<Map<String, Int>, String>(
    save = { parts ->
        parts.entries
            .sortedBy { it.key }
            .joinToString("\u001E") { "${it.key}\u001F${it.value}" }
    },
    restore = { saved ->
        if (saved.isEmpty()) {
            emptyMap()
        } else {
            saved.split('\u001E').mapNotNull { record ->
                val cut = record.indexOf('\u001F')
                if (cut < 0) null
                else {
                    val count = record.substring(cut + 1).toIntOrNull()
                    if (count == null) null else record.substring(0, cut) to count
                }
            }.toMap()
        }
    }
)

/**
 * Whether the editor may be dismissed. A save in flight must not be cancelled
 * by Cancel or swipe: the old composition scope died with the sheet and could
 * commit the Room transaction while skipping `onSaved`, leaving the picker with
 * no selected blueprint and Start disabled (L3). Pure so both sides are tested.
 */
internal fun blueprintDismissAllowed(saving: Boolean): Boolean = !saving

/**
 * [nodes] with every node's count replaced by its **testable** count (its own
 * multiple-choice questions), recursively. The editor's pools roll up
 * `totalQuestionCount`, so this makes that roll-up the supply a test can
 * actually draw: a flashcard is study-only and never fills a quota.
 *
 * A category absent from [testableCounts] reads as zero, like an unsigned
 * section; it never falls back to the raw count.
 */
internal fun testableCategoryTree(
    nodes: List<CategoryNode>,
    testableCounts: Map<String, Int>
): List<CategoryNode> = nodes.map { node ->
    node.copy(
        questionCount = testableCounts[node.id] ?: 0,
        children = testableCategoryTree(node.children, testableCounts)
    )
}

/**
 * The editor's draft preview tree for [parts] over [categories]: each included
 * node's parts and the pool it can draw from (its whole subtree when no child
 * is included, else the included children's sum). Pure, so the pool and the
 * quota it produces can be tested without Compose.
 */
internal fun blueprintPreviewViews(
    categories: List<CategoryNode>,
    parts: Map<String, Int>
): List<BlueprintSectionView> {
    fun viewOf(node: CategoryNode): BlueprintSectionView {
        val childViews = node.children
            .filter { (parts[it.id] ?: 0) > 0 }
            .map { viewOf(it) }
        return BlueprintSectionView(
            categoryId = node.id,
            title = node.title,
            parts = parts[node.id] ?: 0,
            pool = if (childViews.isEmpty()) {
                node.totalQuestionCount
            } else {
                childViews.sumOf { it.pool }
            },
            available = true,
            children = childViews
        )
    }
    return categories
        .filter { (parts[it.id] ?: 0) > 0 }
        .map { viewOf(it) }
}

/**
 * The blueprint tree editor (plan §9.1): a name plus, for every paper
 * category, a parts stepper in the paper's own tree shape. Parts are relative
 * weights inside each sibling group; the editor shows the local share, the
 * share of the whole paper and the quota at the length the setup sheet
 * currently asks for. A child implies its parent, and setting a node to 0
 * removes its whole subtree.
 *
 * Hierarchy and name-uniqueness rules are enforced by the repository on save;
 * a refusal comes back as a message and is shown here, inline.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BlueprintEditorSheet(
    paper: Paper,
    /** The blueprint being edited, or null for a new one. */
    blueprint: BlueprintSummary?,
    /**
     * Saveable edit identity. The setup sheet passes its saveable edit id,
     * which does not blink to null while the list reloads after a rotation.
     * Keying drafts on [blueprint] instead would reset them in that window,
     * and it also drives the title / Delete visibility so an edit always
     * renders as an edit (H12).
     */
    editingId: Long? = blueprint?.id,
    /** The stored sections, as views: empty for a new blueprint. */
    initialSections: List<BlueprintSectionView> = emptyList(),
    /**
     * The paper's per-category **testable** (multiple-choice) counts. The
     * preview pools and the quotas they produce are built from these, never
     * from the raw [Paper.totalQuestions]: a flashcard is study-only, so a
     * card-heavy section must not promise a quota the run cannot fill. Empty
     * while the read is in flight, which reads as zero for every category.
     */
    testableCounts: Map<String, Int> = emptyMap(),
    /** The length the setup sheet currently asks for; quotas are read at it. */
    requestedCount: Int,
    /**
     * Saves the name and section tree and returns the blueprint id (the same
     * id for an edit). Throws with a user-facing message when the repository
     * refuses the save.
     */
    onSave: suspend (String, List<BlueprintSection>) -> Long,
    /** Returns failure instead of throwing so a refused delete keeps the row. */
    onDelete: suspend (Long) -> Result<Unit> = { Result.success(Unit) },
    onDismiss: () -> Unit,
    onSaved: (Long) -> Unit = {}
) {
    // Typed drafts are keyed on [editingId] and saved, not just remembered: a
    // rotation recreates this composable, and the old plain `remember` lost
    // the name/parts while the editor itself stayed open (H12).
    var name by rememberSaveable(editingId) { mutableStateOf(blueprint?.name ?: "") }
    var parts by rememberSaveable(editingId, stateSaver = BlueprintPartsSaver) {
        mutableStateOf(partsFromViews(initialSections))
    }
    var saveError by rememberSaveable(editingId) { mutableStateOf<String?>(null) }
    // Errors for an untouched form are noise: a new blueprint opens with an
    // empty name and no sections, and showing both on open makes the sheet look
    // broken before the learner has typed anything (L9).
    var formTouched by rememberSaveable(editingId) { mutableStateOf(false) }
    // In-flight progress is deliberately transient: a rotation cancels
    // rememberCoroutineScope, so restoring `saving = true` would leave Save
    // disabled forever with no coroutine running to clear it. Dismissal is
    // blocked while it is true for exactly that window (L3).
    var saving by remember(editingId) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(editingId) { mutableStateOf(false) }
    /** True when the repository refused the delete; the row stays in place. */
    var deleteFailed by rememberSaveable(editingId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // LocalResources (not LocalContext) resolves the error copy in the
    // selected language and follows a configuration change; the strings are
    // read outside the coroutine so they can be set from its failure branch.
    val resources = LocalResources.current
    val saveFailedText = resources.getString(R.string.r2_lib_blueprint_save_failed)
    val deleteFailedText = resources.getString(R.string.r2_lib_blueprint_delete_failed)
    val dismissAllowed = blueprintDismissAllowed(saving)

    val flat = remember(paper.categories) { CategorySelection.flatten(paper.categories) }
    val byId = remember(flat) { flat.associate { it.first.id to it.first } }

    // The quota shown per row is the one Start will allocate, not a rounded
    // share: BlueprintQuotas uses recursive floors, remainders, pool caps and
    // the min-one steal, which can differ from round(share × length). The
    // pools are testable-only, so a card-heavy section shows the supply the
    // run will actually use.
    val testableTree = remember(paper.categories, testableCounts) {
        testableCategoryTree(paper.categories, testableCounts)
    }
    val previewViews = remember(parts, testableTree) {
        blueprintPreviewViews(testableTree, parts)
    }
    val previewQuotas = remember(previewViews, requestedCount) {
        BlueprintQuotas.allocate(previewViews, requestedCount)
    }
    // The same count/share numbers the saved preview shows after Save, so the
    // figure does not change under the learner when they reopen the blueprint.
    val previewShares = remember(previewViews, previewQuotas) {
        BlueprintQuotas.shares(previewViews, previewQuotas)
    }
    // Uncapped ask: the only way to know a row is short, since a granted quota
    // can never exceed its pool.
    val previewDemand = remember(previewViews, requestedCount) {
        BlueprintQuotas.demand(previewViews, requestedCount)
    }
    fun nodeQuota(node: CategoryNode, perTerminal: Map<String, Int>): Int {
        val includedChildren = node.children.filter { (parts[it.id] ?: 0) > 0 }
        return if (includedChildren.isEmpty()) {
            perTerminal[node.id] ?: 0
        } else {
            includedChildren.sumOf { nodeQuota(it, perTerminal) }
        }
    }
    fun quotaOf(node: CategoryNode): Int = nodeQuota(node, previewQuotas.perTerminal)
    fun wantedOf(node: CategoryNode): Int = nodeQuota(node, previewDemand)
    // Include [id] and every ancestor: a child implies its parent.
    fun ancestorsOf(id: String): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var current = byId[id]?.parentId
        while (current != null && current in byId && seen.add(current)) {
            out += current
            current = byId[current]?.parentId
        }
        return out
    }

    fun subtreeIdsOf(id: String): List<String> {
        val index = flat.indexOfFirst { it.first.id == id }
        if (index < 0) return listOf(id)
        val depth = flat[index].second
        var end = index + 1
        while (end < flat.size && flat[end].second > depth) end++
        return flat.subList(index, end).map { it.first.id }
    }

    fun setParts(id: String, value: Int) {
        saveError = null
        formTouched = true
        parts = if (value <= 0) {
            // Setting a node to 0 removes it and its whole subtree.
            val removed = subtreeIdsOf(id).toSet()
            parts - removed
        } else {
            val next = parts.toMutableMap()
            next[id] = value
            for (ancestor in ancestorsOf(id)) {
                if ((next[ancestor] ?: 0) <= 0) next[ancestor] = 1
            }
            next
        }
    }

    fun siblingTotalOf(id: String): Int {
        val parentId = byId[id]?.parentId
        return parts.entries
            .filter { (candidate, value) -> value > 0 && byId[candidate]?.parentId == parentId }
            .sumOf { it.value }
    }

    fun localShareOf(id: String): Double {
        val total = siblingTotalOf(id)
        return if (total > 0) (parts[id] ?: 0).toDouble() / total else 0.0
    }

    fun globalShareOf(id: String): Double {
        var share = localShareOf(id)
        for (ancestor in ancestorsOf(id)) share *= localShareOf(ancestor)
        return share
    }

    // Only categories still in this paper can be saved. A section whose
    // category moved away is shown as a warning below instead of being
    // silently dropped when the payload is rebuilt.
    val included = parts.filter { (id, value) -> value > 0 && byId.containsKey(id) }
    val topLevel = included.keys.filter { byId[it]?.parentId == null }
    val totalParts = topLevel.sumOf { parts[it] ?: 0 }
    val orphans = remember(initialSections) { orphanedSections(initialSections) }

    ModalBottomSheet(onDismissRequest = { if (dismissAllowed) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                stringResource(
                    if (editingId == null) {
                        R.string.blueprint_editor_new_title
                    } else {
                        R.string.blueprint_editor_title
                    }
                ),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(12.dp))
            if (paper.categories.isEmpty()) {
                // No tree at all: the empty state replaces the list, so the
                // sheet cannot crash on a paper with no categories.
                Text(
                    stringResource(R.string.blueprint_editor_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                return@Column
            }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    saveError = null
                    formTouched = true
                    name = it.take(60)
                },
                label = { Text(stringResource(R.string.blueprint_editor_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("blueprint-name")
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.blueprint_editor_parts_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.blueprint_editor_share_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (orphans.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        R.string.blueprint_editor_orphans,
                        orphans.joinToString(", ") { it.title }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(8.dp))
            for ((node, depth) in flat) {
                val value = parts[node.id] ?: 0
                // Resolve copy in the composable scope: `semantics {}` is not
                // composable, so stringResource cannot be called inside it.
                val decreaseLabel = stringResource(R.string.r2_lib_decrease_parts, node.title)
                val increaseLabel = stringResource(R.string.r2_lib_increase_parts, node.title)
                Column(modifier = Modifier.padding(start = (depth * 16).dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            node.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(
                            enabled = value > 0,
                            onClick = { setParts(node.id, value - 1) },
                            modifier = Modifier
                                .testTag("blueprint-dec-${node.id}")
                                .semantics { contentDescription = decreaseLabel }
                        ) {
                            Text("−")
                        }
                        Text(
                            value.toString(),
                            modifier = Modifier.testTag("blueprint-parts-${node.id}"),
                            style = MaterialTheme.typography.labelLarge
                        )
                        TextButton(
                            enabled = value < 999,
                            onClick = { setParts(node.id, value + 1) },
                            modifier = Modifier
                                .testTag("blueprint-inc-${node.id}")
                                .semantics { contentDescription = increaseLabel }
                        ) {
                            Text("+")
                        }
                    }
                    if (value > 0) {
                        val local = localShareOf(node.id)
                        val global = globalShareOf(node.id)
                        val quota = quotaOf(node)
                        val wanted = wantedOf(node)
                        val share = previewShares[node.id]
                        Text(
                            stringResource(
                                R.string.blueprint_editor_share,
                                value,
                                siblingTotalOf(node.id),
                                (local * 100).roundToInt(),
                                (global * 100).roundToInt(),
                                quota
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (share != null) {
                            Text(
                                stringResource(
                                    R.string.blueprint_editor_questions,
                                    share.quota,
                                    share.pool,
                                    share.percent
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (node.children.isEmpty() && wanted > quota) {
                            Text(
                                stringResource(
                                    R.string.blueprint_editor_short,
                                    wanted - quota
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (totalParts > 0) {
                val top = topLevel.joinToString(", ") { id ->
                    "${byId[id]?.title.orEmpty()} ${(localShareOf(id) * 100).roundToInt()}%"
                }
                Text(
                    stringResource(R.string.blueprint_editor_total, totalParts, top),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            val nameBlank = name.isBlank()
            val noSections = included.isEmpty()
            if (formTouched && nameBlank) {
                Text(
                    stringResource(R.string.blueprint_editor_error_name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (formTouched && noSections) {
                Text(
                    stringResource(R.string.blueprint_editor_error_no_sections),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            saveError?.let { error ->
                Spacer(Modifier.height(4.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (deleteFailed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    deleteFailedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    saveError = null
                    deleteFailed = false
                    formTouched = true
                    saving = true
                    scope.launch {
                        try {
                            // The paper's own child order: children are the
                            // included category children, recursively.
                            fun build(nodes: List<CategoryNode>): List<BlueprintSection> =
                                nodes.mapNotNull { node ->
                                    val nodeParts = parts[node.id]?.takeIf { it > 0 }
                                        ?: return@mapNotNull null
                                    BlueprintSection(
                                        categoryId = node.id,
                                        title = node.title,
                                        parts = nodeParts,
                                        children = build(node.children)
                                    )
                                }
                            val id = onSave(name.trim(), build(paper.categories))
                            saving = false
                            onSaved(id)
                            onDismiss()
                        } catch (e: Exception) {
                            // The repository's detail goes to the log only; the
                            // learner gets one localized, friendly sentence.
                            Logger.e("LIBVM", "Blueprint save failed", e)
                            saveError = saveFailedText
                            saving = false
                        }
                    }
                },
                enabled = !saving && !nameBlank && !noSections,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("blueprint-save")
            ) {
                Text(stringResource(R.string.save))
            }
            if (editingId != null) {
                TextButton(
                    enabled = dismissAllowed,
                    onClick = { confirmDelete = true },
                    modifier = Modifier.testTag("blueprint-delete")
                ) {
                    Text(stringResource(R.string.delete))
                }
            }
            TextButton(enabled = dismissAllowed, onClick = { if (dismissAllowed) onDismiss() }) {
                Text(stringResource(R.string.cancel))
            }
        }
    }

    val deleteId = editingId
    if (confirmDelete && deleteId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.test_setup_blueprint_delete)) },
            text = {
                Text(
                    stringResource(
                        R.string.blueprint_editor_delete_confirm,
                        blueprint?.name ?: name
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        deleteFailed = false
                        scope.launch {
                            val result = onDelete(deleteId)
                            if (result.isSuccess) {
                                onDismiss()
                            } else {
                                // Keep the row and the sheet; only the failed
                                // delete is reported, with localized copy.
                                result.exceptionOrNull()?.let { e ->
                                    Logger.e("LIBVM", "Blueprint delete failed", e)
                                }
                                deleteFailed = true
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/** Sections whose category is no longer part of [views]' paper. */
internal fun orphanedSections(views: List<BlueprintSectionView>): List<BlueprintSectionView> =
    views.flatMap { view ->
        (if (view.available) emptyList() else listOf(view)) + orphanedSections(view.children)
    }

/** The parts of every node in a stored section-view tree, flattened. */
private fun partsFromViews(sections: List<BlueprintSectionView>): Map<String, Int> {
    val out = mutableMapOf<String, Int>()
    fun walk(views: List<BlueprintSectionView>) {
        for (view in views) {
            out[view.categoryId] = view.parts
            walk(view.children)
        }
    }
    walk(sections)
    return out
}
