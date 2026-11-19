package com.mcqapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's manifest opted into backup with no rules, so every question bank,
 * bookmark and attempt history was eligible for cloud backup. The rules are
 * XML with no runtime API to assert against, so the guarantee is pinned here.
 *
 * Backup is an <include> allow-list: only the DataStore settings file and a
 * tiny shared-pref mirror go to a backup. The database, logs and every other
 * file stay out, so the rules must carry no blanket file include and no
 * include that re-opens the log or database domains.
 */
class BackupRulesTest {

    private fun res(name: String): String {
        val file = File("src/main/res/xml/$name")
        assertTrue("missing: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun block(xml: String, transport: String): String {
        val start = xml.indexOf("<$transport>")
        assertTrue("missing <$transport>", start >= 0)
        val end = xml.indexOf("</$transport>", start)
        assertTrue("missing </$transport>", end >= 0)
        return xml.substring(start, end)
    }

    private val excludedDomains = listOf("database", "log", "external")

    private fun assertOnlyDataStoreIsIncluded(rules: String, scope: String) {
        val includes = Regex("""<include\s+domain="([^"]+)"([^>]*)/>""").findAll(rules)
            .map { it.value }.toList()
        val fileIncludes = includes.filter { it.contains("domain=\"file\"") }
        assertTrue(
            "$scope must allow-list only datastore/settings.preferences_pb from files: $fileIncludes",
            fileIncludes.size == 1 &&
                fileIncludes[0].contains("path=\"datastore/settings.preferences_pb\"")
        )
        assertFalse(
            "$scope has a blanket file include, which would back up logs and the database",
            fileIncludes.any { Regex("""<include\s+domain="file"\s*/>""").matches(it) }
        )
        assertFalse(
            "$scope re-opens the log domain: $includes",
            includes.any { it.contains("domain=\"log\"") }
        )
        assertFalse(
            "$scope re-opens the database domain: $includes",
            includes.any { it.contains("domain=\"database\"") }
        )
    }

    @Test
    fun theManifestPointsAtBothRuleFiles() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    @Test
    fun android12RulesExcludeTheDatabaseAndLogs() {
        val rules = res("data_extraction_rules.xml")
        for (transport in listOf("cloud-backup", "device-transfer")) {
            val transportRules = block(rules, transport)
            for (domain in excludedDomains) {
                assertTrue(
                    "$transport should exclude $domain",
                    transportRules.contains("<exclude domain=\"$domain\"")
                )
            }
            assertOnlyDataStoreIsIncluded(transportRules, transport)
        }
    }

    @Test
    fun preAndroid12RulesExcludeTheDatabaseAndLogs() {
        val rules = res("backup_rules.xml")
        for (domain in excludedDomains) {
            assertTrue("legacy backup should exclude $domain", rules.contains("<exclude domain=\"$domain\""))
        }
        assertOnlyDataStoreIsIncluded(rules, "legacy backup")
    }

    @Test
    fun smallSettingsAreStillBackedUp() {
        assertTrue(res("data_extraction_rules.xml").contains("<include domain=\"sharedpref\" />"))
        assertTrue(res("backup_rules.xml").contains("<include domain=\"sharedpref\" />"))
    }

    @Test
    fun noQuestionTextIsLogged() {
        // A structural guard: the logger used to write question stems and
        // option text into a shareable file.
        val offenders = listOf(
            "src/main/java/com/mcqapp/data/io/Importer.kt",
            "src/main/java/com/mcqapp/data/repository/McqRepository.kt",
            "src/main/java/com/mcqapp/data/repository/RepositorySettings.kt",
            "src/main/java/com/mcqapp/data/repository/QuestionStore.kt",
            "src/main/java/com/mcqapp/data/repository/PaperStore.kt",
            "src/main/java/com/mcqapp/data/repository/StudyStore.kt",
            "src/main/java/com/mcqapp/data/repository/HistoryStore.kt",
            "src/main/java/com/mcqapp/data/repository/QuestionContentMapper.kt",
            "src/main/java/com/mcqapp/ui/editor/EditorViewModel.kt",
            "src/main/java/com/mcqapp/ui/importscreen/ImportScreen.kt",
            "src/main/java/com/mcqapp/ui/importscreen/ImportViewModel.kt"
        ).filter { path ->
            val file = File(path)
            file.isFile && Regex("""Logger\.[a-z]+\([^)]*text='\$\{""").containsMatchIn(file.readText())
        }
        assertTrue("question text still reaches the log: $offenders", offenders.isEmpty())
    }
}
