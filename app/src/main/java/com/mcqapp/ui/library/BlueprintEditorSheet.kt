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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    /** The stored sections, as views: empty for a new blueprint. */
    initialSections: List<BlueprintSectionView> = emptyList(),
    /** The length the setup sheet currently asks for; quotas are read at it. */
    requestedCount: Int,
    /**
     * Saves the name and section tree and returns the blueprint id (the same
     * id for an edit). Throws with a user-facing message when the repository
     * refuses the save.
     */
    onSave: suspend (String, List<BlueprintSection>) -> Long,
    onDelete: suspend (Long) -> Unit = {},
    onDismiss: () -> Unit,
    onSaved: (Long) -> Unit = {}
) {
    var name by rememberSaveable(blueprint?.id) { mutableStateOf(blueprint?.name ?: "") }
    var parts by remember(blueprint?.id, initialSections) {
        mutableStateOf(partsFromViews(initialSections))
    }
    var saveError by remember(blueprint?.id) { mutableStateOf<String?>(null) }
    var saving by remember(blueprint?.id) { mutableStateOf(false) }
    var confirmDelete by remember(blueprint?.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val flat = remember(paper.categories) { CategorySelection.flatten(paper.categories) }
    val byId = remember(flat) { flat.associate { it.first.id to it.first } }

    // The quota shown per row is the one Start will allocate, not a rounded
    // share: BlueprintQuotas uses recursive floors, remainders, pool caps and
    // the min-one steal, which can differ from round(share × length).
    val previewViews = remember(parts, paper.categories) {
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
        paper.categories
            .filter { (parts[it.id] ?: 0) > 0 }
            .map { viewOf(it) }
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                stringResource(
                    if (blueprint == null) {
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
                            modifier = Modifier.testTag("blueprint-dec-${node.id}")
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
                            modifier = Modifier.testTag("blueprint-inc-${node.id}")
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
            if (nameBlank) {
                Text(
                    stringResource(R.string.blueprint_editor_error_name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (noSections) {
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
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    saveError = null
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
                            onSaved(id)
                            onDismiss()
                        } catch (e: Exception) {
                            // Hierarchy, uniqueness and shape refusals land here
                            // with the repository's own message.
                            saveError = e.message ?: "Could not save the blueprint."
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
            if (blueprint != null) {
                TextButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.testTag("blueprint-delete")
                ) {
                    Text(stringResource(R.string.delete))
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    }

    if (confirmDelete && blueprint != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.test_setup_blueprint_delete)) },
            text = {
                Text(
                    stringResource(
                        R.string.blueprint_editor_delete_confirm,
                        blueprint.name
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            runCatching { onDelete(blueprint.id) }
                            onDismiss()
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
