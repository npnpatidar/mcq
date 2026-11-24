package com.mcqapp.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mcqapp.R
import com.mcqapp.domain.ai.AiSchemaGenerator
import com.mcqapp.domain.ai.PassageMode
import com.mcqapp.domain.ai.QuestionKind
import com.mcqapp.domain.ai.SchemaOptions
import com.mcqapp.domain.ai.SchemaWrapper
import com.mcqapp.ui.common.LocalUiMessageSender
import com.mcqapp.ui.common.UiMessage
import com.mcqapp.ui.common.UiMessageTone
import com.mcqapp.util.Logger
import com.mcqapp.util.nextSharedFile
import com.mcqapp.util.shareIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

/**
 * The AI schema generator. Four small controls produce a closed JSON Schema,
 * a short prompt and a tiny example; all output is built in the pure
 * [AiSchemaGenerator] so every combination is unit-tested against LegacyParser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSchemaScreen(navController: NavController) {
    var kind by rememberSaveable { mutableStateOf(QuestionKind.MIXED) }
    var table by rememberSaveable { mutableStateOf(false) }
    var math by rememberSaveable { mutableStateOf(false) }
    var image by rememberSaveable { mutableStateOf(false) }
    var passage by rememberSaveable { mutableStateOf(PassageMode.NONE) }
    // The old FULL_BANK wrapper is equivalent to papers + categories on and
    // passages off; the three toggles below are now the only container choice.
    var includePapers by rememberSaveable { mutableStateOf(true) }
    var includeCategories by rememberSaveable { mutableStateOf(true) }
    var includePassages by rememberSaveable { mutableStateOf(false) }

    val options = SchemaOptions(
        kind = kind,
        table = table,
        math = math,
        image = image,
        passage = passage,
        wrapper = SchemaWrapper.FULL_BANK,
        includePapers = includePapers,
        includeCategories = includeCategories,
        includePassages = includePassages
    )
    val generated = AiSchemaGenerator.generate(options)
    // A SAF picker can recreate the activity while it is open; read the latest
    // output in its callback instead of the composition that launched it.
    val current by rememberUpdatedState(generated)

    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val sendMessage = LocalUiMessageSender.current
    val copiedText = stringResource(R.string.ai_schema_copied)
    val savedText = stringResource(R.string.ai_schema_saved)
    val saveFailedText = stringResource(R.string.ai_schema_save_failed)
    val shareNoAppText = stringResource(R.string.ai_schema_share_no_app)
    val schemaLabel = stringResource(R.string.ai_schema_output_schema)
    val promptLabel = stringResource(R.string.ai_schema_output_prompt)
    val exampleLabel = stringResource(R.string.ai_schema_output_example)
    val shareFileName = stringResource(R.string.ai_schema_share_file_name)

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/schema+json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = current.schema
        scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("No output stream for $uri")
                withContext(Dispatchers.Main) { sendMessage(UiMessage(savedText)) }
            } catch (e: Exception) {
                Logger.e("AI-SCHEMA", "Could not save the schema", e)
                withContext(Dispatchers.Main) {
                    sendMessage(UiMessage(saveFailedText, UiMessageTone.Error))
                }
            }
        }
    }

    fun copyPrompt() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(promptLabel, current.prompt))
        sendMessage(UiMessage(copiedText))
    }

    fun share() {
        scope.launch(Dispatchers.IO) {
            val file = nextSharedFile(context.cacheDir, shareFileName)
            val text = "$schemaLabel\n${current.schema}\n\n" +
                "$promptLabel\n${current.prompt}\n\n" +
                "$exampleLabel\n${current.example}\n"
            FileOutputStream(file).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            val intent = shareIntent(context, file, "text/plain")
            withContext(Dispatchers.Main) {
                try {
                    context.startActivity(
                        Intent.createChooser(
                            intent,
                            resources.getString(R.string.share_chooser_title)
                        )
                    )
                } catch (_: ActivityNotFoundException) {
                    sendMessage(UiMessage(shareNoAppText, UiMessageTone.Error))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_schema_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            ControlCard(R.string.ai_schema_question_type, "ai-schema-kind") {
                KindChip(QuestionKind.FLASHCARDS, kind, R.string.ai_schema_kind_flashcards) { kind = it }
                KindChip(QuestionKind.MCQ, kind, R.string.ai_schema_kind_mcq) { kind = it }
                KindChip(QuestionKind.MIXED, kind, R.string.ai_schema_kind_mixed) { kind = it }
            }
            Spacer(Modifier.height(12.dp))
            ControlCard(R.string.ai_schema_blocks, "ai-schema-blocks") {
                FilterChip(
                    selected = true,
                    enabled = false,
                    onClick = {},
                    label = { Text(stringResource(R.string.ai_schema_block_text_always)) }
                )
                BlockChip(R.string.ai_schema_block_table, table, "ai-schema-block-table") { table = it }
                BlockChip(R.string.ai_schema_block_math, math, "ai-schema-block-math") { math = it }
                BlockChip(R.string.ai_schema_block_image, image, "ai-schema-block-image") { image = it }
            }
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth().testTag("ai-schema-containers")) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.ai_schema_containers),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(8.dp))
                    ContainerToggle(
                        R.string.ai_schema_include_papers, includePapers,
                        "ai-schema-include-papers"
                    ) { includePapers = it }
                    ContainerToggle(
                        R.string.ai_schema_include_categories, includeCategories,
                        "ai-schema-include-categories"
                    ) { includeCategories = it }
                    ContainerToggle(
                        R.string.ai_schema_include_passages, includePassages,
                        "ai-schema-include-passages"
                    ) { on ->
                        includePassages = on
                        // Turning passages on must select a visible shape too:
                        // NONE would be silently overridden by effectivePassage.
                        if (on && passage == PassageMode.NONE) {
                            passage = PassageMode.TEXT_ONLY
                        }
                    }
                    Text(
                        stringResource(R.string.ai_schema_questions_always),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // The shape of a passage still matters when passages are in; with
            // them off, "None" would duplicate the passage toggle above.
            if (includePassages) {
                Spacer(Modifier.height(12.dp))
                ControlCard(R.string.ai_schema_passage, "ai-schema-passage") {
                    PassageChip(
                        PassageMode.TEXT_ONLY, passage,
                        R.string.ai_schema_passage_text
                    ) { passage = it }
                    PassageChip(
                        PassageMode.RICH, passage,
                        R.string.ai_schema_passage_rich
                    ) { passage = it }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.ai_schema_size, generated.characterCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("ai-schema-char-count")
            )
            Spacer(Modifier.height(8.dp))
            OutputCard(schemaLabel, generated.schema, "ai-schema-output-schema")
            Spacer(Modifier.height(12.dp))
            OutputCard(promptLabel, generated.prompt, "ai-schema-output-prompt")
            Spacer(Modifier.height(12.dp))
            OutputCard(exampleLabel, generated.example, "ai-schema-output-example")
            Spacer(Modifier.height(12.dp))
            // Three equal-width buttons in one row left Share with a sliver
            // of space on a 320 dp phone, stacking its letters vertically.
            // Two rows give the first pair a readable width and Share the
            // whole line; labels, callbacks and tags are unchanged.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = ::copyPrompt,
                        modifier = Modifier.weight(1f).testTag("ai-schema-copy-prompt")
                    ) { Text(stringResource(R.string.ai_schema_copy_prompt)) }
                    OutlinedButton(
                        // The SAF default name is a file name, not user-facing copy.
                        onClick = { saveLauncher.launch("mcq-ai-schema.json") },
                        modifier = Modifier.weight(1f).testTag("ai-schema-save")
                    ) { Text(stringResource(R.string.ai_schema_save)) }
                }
                OutlinedButton(
                    onClick = ::share,
                    modifier = Modifier.fillMaxWidth().testTag("ai-schema-share")
                ) { Text(stringResource(R.string.ai_schema_share)) }
            }
        }
    }
}

@Composable
private fun ControlCard(
    @StringRes titleRes: Int,
    tag: String,
    content: @Composable () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) { content() }
        }
    }
}

@Composable
private fun OutputCard(title: String, text: String, tag: String) {
    Card(modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                // The Card keeps its own tag; tests need the JSON text node
                // itself, which is invisible in the merged tree.
                modifier = Modifier.testTag("$tag-text")
            )
        }
    }
}

@Composable
private fun KindChip(
    kind: QuestionKind,
    selected: QuestionKind,
    labelRes: Int,
    onSelect: (QuestionKind) -> Unit
) {
    FilterChip(
        selected = selected == kind,
        onClick = { onSelect(kind) },
        label = { Text(stringResource(labelRes)) },
        modifier = Modifier.testTag("ai-schema-kind-${kind.name}")
    )
}

@Composable
private fun BlockChip(labelRes: Int, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = checked,
        onClick = { onChange(!checked) },
        label = { Text(stringResource(labelRes)) },
        modifier = Modifier.testTag(tag)
    )
}

@Composable
private fun ContainerToggle(
    @StringRes labelRes: Int,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.testTag(tag)
        )
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun PassageChip(
    mode: PassageMode,
    selected: PassageMode,
    labelRes: Int,
    onSelect: (PassageMode) -> Unit
) {
    FilterChip(
        selected = selected == mode,
        onClick = { onSelect(mode) },
        label = { Text(stringResource(labelRes)) },
        modifier = Modifier.testTag("ai-schema-passage-${mode.name}")
    )
}
