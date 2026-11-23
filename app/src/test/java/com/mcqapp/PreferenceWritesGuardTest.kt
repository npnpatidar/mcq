package com.mcqapp

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every preference write must go through [com.mcqapp.data.repository.editPreferences].
 *
 * `DataStore.updateData` runs the caller's transform under the caller's own
 * coroutine context inside the shared write actor. A ViewModel write therefore
 * runs the transform on `Dispatchers.Main.immediate`; under Robolectric that is
 * the test's main looper, and once the test discards it the actor waits for the
 * transform forever. Every later DataStore call in the process then times out —
 * the recurring 60 s `clearGradingKeys` failure and count reads that never
 * arrive, at a different class each run. The helper hops to IO so no caller can
 * reintroduce that wedge by writing preferences directly.
 *
 * Pure JVM: reads the sources, no Robolectric.
 *
 * INCIDENT: a direct `dataStore.edit` on Main stranded the process-wide write
 * actor after a test discarded its looper, at a different class each run.
 */
class PreferenceWritesGuardTest {

    private val helperName = "PreferenceWrites.kt"

    /** The production sources, keyed by a name for the failure message. */
    private fun productionSources(): List<Pair<String, String>> {
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
        assertTrue("main source root not found from ${File(".").absolutePath}", root != null)
        return root!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path to it.readText() }
            .toList()
    }

    /** `dataStore.edit` on any receiver, across line breaks and spacing. */
    private val directEdit = Regex("""\bdataStore\s*\.\s*edit\b""")

    /** Comments explain the rule; only code may trip it. */
    private fun stripComments(text: String): String =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    /** Files that still write the store directly; empty means the guard holds. */
    private fun offences(sources: List<Pair<String, String>>): List<String> =
        sources
            .filterNot { (path, _) -> File(path).name == helperName }
            .flatMap { (path, text) ->
                val code = stripComments(text)
                directEdit.findAll(code).map { match ->
                    val line = code.take(match.range.first).count { it == '\n' } + 1
                    "$path:$line"
                }.toList()
            }

    @Test
    fun noProductionFileEditsTheStoreDirectly() {
        val offenders = offences(productionSources())
        assertTrue(
            "preference writes outside $helperName must call editPreferences, " +
                "which moves the transform off the main thread; direct writes " +
                "wedge the process-wide DataStore actor under Robolectric: " +
                offenders.joinToString(),
            offenders.isEmpty()
        )
    }

    /** Negative case: a direct write anywhere in `src/main` must trip the guard. */
    @Test
    fun theGuardDetectsAReintroducedDirectEdit() {
        val regressed = listOf(
            "com/mcqapp/data/repository/NewSettings.kt" to
                "suspend fun save(context: Context) {\n" +
                "    context.dataStore.edit { it[KEY] = true }\n" +
                "}"
        )
        assertTrue(
            "the guard must reject a direct DataStore write",
            offences(regressed).isNotEmpty()
        )
    }

    /** What the helper must keep doing; empty means the helper is sound. */
    private fun helperOffences(code: String): List<String> = buildList {
        if (Regex("""withContext\(Dispatchers\.IO\)""").findAll(code).count() != 1) add(
            "$helperName must wrap the edit in exactly one withContext(Dispatchers.IO); " +
                "otherwise the transform still runs on the caller's dispatcher"
        )
        if (!Regex("""\bedit\(transform\)""").containsMatchIn(code)) add(
            "$helperName must still perform the atomic DataStore.edit"
        )
        if (!code.contains("dataStore.editPreferences(transform)")) add(
            "$helperName must route the production Context overload through the " +
                "same injected-store write path, so a test cannot bypass the IO hop"
        )
    }

    @Test
    fun theHelperHopsToIoBeforeEditing() {
        val helper = productionSources()
            .firstOrNull { (path, _) -> File(path).name == helperName }
            ?.second
        assertTrue("missing production helper $helperName", helper != null)
        val offenders = helperOffences(stripComments(helper!!))
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** Negative case: dropping the IO hop must trip the helper guard. */
    @Test
    fun theHelperGuardDetectsAMissingIoHop() {
        val noHop = "internal suspend fun DataStore<Preferences>.editPreferences(" +
            "transform: suspend (MutablePreferences) -> Unit) {\n" +
            "    edit(transform)\n" +
            "}\n" +
            "internal suspend fun Context.editPreferences(" +
            "transform: suspend (MutablePreferences) -> Unit) =\n" +
            "    dataStore.editPreferences(transform)"
        assertTrue(
            "a helper without the IO hop must not pass",
            helperOffences(stripComments(noHop)).isNotEmpty()
        )
    }
}
