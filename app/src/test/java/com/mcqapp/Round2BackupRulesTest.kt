package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * H1 (report 13): the settings live in Preferences DataStore under the `file`
 * domain. A blanket file exclusion dropped them on cloud/D2D restore while the
 * backup carried only a transient sharedpref journal. These are XML rules with
 * no runtime API, so the guarantee is pinned by reading the resource text.
 *
 * An `<include>` is an allow-list: Android backs up only the files it matches,
 * so logs and other content stay out by default without a separate exclude.
 */
class Round2BackupRulesTest {

    private fun res(name: String): String {
        val file = File("src/main/res/xml/$name")
        assertTrue("missing: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private val datastoreInclude = "<include domain=\"file\" path=\"datastore/settings.preferences_pb\" />"
    private val logsFileExclude = "<exclude domain=\"file\" path=\"logs/\" />"
    private val blanketFileInclude = "<include domain=\"file\" />"
    private val blanketFileExclude = "<exclude domain=\"file\" />"

    @Test
    fun android12RulesIncludeDatastoreInBothTransports() {
        val rules = res("data_extraction_rules.xml")
        val cloud = rules.between("<cloud-backup>", "</cloud-backup>")
        val d2d = rules.between("<device-transfer>", "</device-transfer>")
        assertTrue("cloud-backup must include DataStore settings: $cloud", cloud.contains(datastoreInclude))
        assertTrue("device-transfer must include DataStore settings: $d2d", d2d.contains(datastoreInclude))
    }

    @Test
    fun legacyRulesIncludeDatastore() {
        assertTrue(res("backup_rules.xml").contains(datastoreInclude))
    }

    @Test
    fun logsAreNotCoveredByAnyInclude() {
        // With an <include> present Android backs up only matched files, so a
        // path-scoped logs exclude is redundant — and lint rejects it because
        // its path is not inside any included path. The only file-domain
        // include must be the DataStore settings file, never logs or the whole
        // file directory; a blanket file exclude would cancel it.
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val rules = res(name)
            val sections = if (name == "data_extraction_rules.xml") {
                listOf(
                    "cloud-backup" to rules.between("<cloud-backup>", "</cloud-backup>"),
                    "device-transfer" to rules.between("<device-transfer>", "</device-transfer>")
                )
            } else {
                listOf("full-backup-content" to rules)
            }
            for ((section, text) in sections) {
                val label = "$name/$section"
                val fileIncludes = text.lines().filter { it.trim().startsWith("<include domain=\"file\"") }
                assertEquals("$label must include exactly one file-domain path", 1, fileIncludes.size)
                assertTrue(
                    "$label's file include must be the DataStore settings file",
                    fileIncludes.single().contains(datastoreInclude)
                )
                assertFalse("$label must not blanket-include the file domain", fileIncludes.any { it.contains(blanketFileInclude) })
                assertFalse("$label must not include the logs directory", fileIncludes.any { it.contains("logs") })
                assertFalse("$label must not keep a path-scoped logs exclude", text.contains(logsFileExclude))
                assertFalse("$label has a blanket file exclude that shadows the include", text.contains(blanketFileExclude))
            }
        }
    }

    @Test
    fun contentDomainsRemainExcluded() {
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val rules = res(name)
            for (domain in listOf("database", "log", "external")) {
                assertTrue("$name must still exclude $domain", rules.contains("<exclude domain=\"$domain\""))
            }
        }
    }

    @Test
    fun commentNoLongerClaimsSettingsLiveInSharedPreferences() {
        val rules = res("data_extraction_rules.xml")
        assertFalse("misleading comment still present", rules.contains("font scale, scheduler config"))
        assertTrue("comment should name Preferences DataStore", rules.contains("Preferences DataStore"))
    }

    private fun String.between(start: String, end: String): String {
        val from = indexOf(start).takeIf { it >= 0 } ?: return ""
        val to = indexOf(end, from + start.length).takeIf { it >= 0 } ?: return ""
        return substring(from + start.length, to)
    }
}
