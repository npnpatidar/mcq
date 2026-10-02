package com.mcqapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's manifest opted into backup with no rules, so every question bank,
 * bookmark and attempt history was eligible for cloud backup. The rules are
 * XML with no runtime API to assert against, so the guarantee is pinned here.
 */
class BackupRulesTest {

    private fun res(name: String): String {
        val file = File("src/main/res/xml/$name")
        assertTrue("missing: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private val excludedDomains = listOf("database", "log", "file", "external")

    @Test
    fun theManifestPointsAtBothRuleFiles() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    @Test
    fun android12RulesExcludeTheDatabaseAndLogs() {
        val rules = res("data_extraction_rules.xml")
        for (domain in excludedDomains) {
            val tag = "<exclude domain=\"$domain\""
            assertTrue("cloud-backup should exclude $domain", rules.contains(tag))
            assertTrue("device-transfer should exclude $domain", rules.contains(tag))
        }
        assertTrue("both transports must be covered", rules.contains("<cloud-backup>"))
        assertTrue(rules.contains("<device-transfer>"))
    }

    @Test
    fun preAndroid12RulesExcludeTheDatabaseAndLogs() {
        val rules = res("backup_rules.xml")
        for (domain in excludedDomains) {
            assertTrue("legacy backup should exclude $domain", rules.contains("<exclude domain=\"$domain\""))
        }
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
