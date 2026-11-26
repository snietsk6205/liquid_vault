package dev.liquid.vault.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessRulesTest {
    private val own = "dev.liquid.vault"
    private val pickers = setOf("com.google.android.documentsui", "com.android.documentsui")
    private val gmail = "com.google.android.gm"
    private val aves = "deckers.thibault.aves.libre"

    private fun decide(rules: List<AppRule>, place: Place, caller: String?, action: BridgeAction, path: String) =
        AccessRules.decide(rules, place, caller, action, path, own, pickers)

    private fun allowed(d: BridgeDecision) = d is BridgeDecision.Allowed

    private val gmailReadsWork = AppRule(gmail, Place.PERSONAL, reach = Reach.FOLDERS, folders = listOf("Documents/Work"))

    @Test
    fun pathsAreCleanedAndCannotLeaveTheStorage() {
        assertEquals("Documents/Work", StoragePaths.clean("/Documents//Work/"))
        assertEquals("", StoragePaths.clean("/"))
        assertEquals("a/b", StoragePaths.clean("./a/./b"))
        assertNull(StoragePaths.clean("Documents/../../data"))
        assertTrue(StoragePaths.inside("Documents/Work/a.pdf", "Documents/Work"))
        assertTrue(StoragePaths.inside("Documents/Work", "Documents/Work"))
        assertFalse(StoragePaths.inside("Documents/Workshop/a.pdf", "Documents/Work"))
        assertTrue(StoragePaths.inside("anything", ""))
        assertFalse(StoragePaths.validName("a/b"))
        assertFalse(StoragePaths.validName(".."))
        assertTrue(StoragePaths.validName("Report 2026.pdf"))
    }

    @Test
    fun noRuleMeansRefused() {
        val d = decide(emptyList(), Place.PERSONAL, gmail, BridgeAction.LIST, "")
        assertFalse(allowed(d))
        assertEquals("com.google.android.gm has no access to the vault files", d.reason)
        assertFalse(allowed(decide(emptyList(), Place.PERSONAL, null, BridgeAction.READ, "a")))
    }

    @Test
    fun liquidVaultItselfIsAlwaysAllowed() {
        assertTrue(allowed(decide(emptyList(), Place.VAULT, own, BridgeAction.DELETE, "Documents/x.txt")))
    }

    @Test
    fun chosenFoldersAreTheLimit() {
        val rules = listOf(gmailReadsWork)
        assertTrue(allowed(decide(rules, Place.PERSONAL, gmail, BridgeAction.LIST, "")))
        assertTrue(allowed(decide(rules, Place.PERSONAL, gmail, BridgeAction.READ, "Documents/Work/plan.pdf")))
        assertTrue(allowed(decide(rules, Place.PERSONAL, gmail, BridgeAction.LIST, "Documents/Work")))
        assertFalse(allowed(decide(rules, Place.PERSONAL, gmail, BridgeAction.READ, "Documents/Private/diary.txt")))
        assertFalse(allowed(decide(rules, Place.PERSONAL, gmail, BridgeAction.LIST, "Documents")))
        assertFalse(allowed(decide(rules, Place.PERSONAL, gmail, BridgeAction.READ, "Documents/Workshop/x")))
        // The rule is for Gmail on the personal side; Gmail in the Vault has none.
        assertFalse(allowed(decide(rules, Place.VAULT, gmail, BridgeAction.READ, "Documents/Work/plan.pdf")))
    }

    @Test
    fun readOnlyRefusesEveryChange() {
        val rules = listOf(gmailReadsWork)
        for (a in BridgeAction.entries.filter { it.write }) {
            val d = decide(rules, Place.PERSONAL, gmail, a, "Documents/Work/plan.pdf")
            assertFalse(a.name, allowed(d))
        }
        assertEquals(
            "com.google.android.gm may only read (its folders, read-only)",
            decide(rules, Place.PERSONAL, gmail, BridgeAction.WRITE, "Documents/Work/plan.pdf").reason,
        )
    }

    @Test
    fun readWriteAllowsChangesInsideButNotTheSharedFolderItself() {
        val rules = listOf(gmailReadsWork.copy(write = true))
        assertTrue(allowed(decide(rules, Place.PERSONAL, gmail, BridgeAction.WRITE, "Documents/Work/plan.pdf")))
        assertTrue(allowed(decide(rules, Place.PERSONAL, gmail, BridgeAction.CREATE, "Documents/Work/new.txt")))
        assertTrue(allowed(decide(rules, Place.PERSONAL, gmail, BridgeAction.DELETE, "Documents/Work/old.txt")))
        assertFalse(allowed(decide(rules, Place.PERSONAL, gmail, BridgeAction.DELETE, "Documents/Work")))
        assertFalse(allowed(decide(rules, Place.PERSONAL, gmail, BridgeAction.RENAME, "Documents/Work")))
        assertFalse(allowed(decide(rules, Place.PERSONAL, gmail, BridgeAction.CREATE, "Documents/elsewhere.txt")))
    }

    @Test
    fun allFilesAcrossStillProtectsTheTop() {
        val rules = listOf(AppRule(aves, Place.VAULT, reach = Reach.ALL, write = true))
        assertTrue(allowed(decide(rules, Place.VAULT, aves, BridgeAction.READ, "DCIM/Camera/a.jpg")))
        assertTrue(allowed(decide(rules, Place.VAULT, aves, BridgeAction.DELETE, "DCIM/Camera/a.jpg")))
        assertFalse(allowed(decide(rules, Place.VAULT, aves, BridgeAction.DELETE, "")))
        assertFalse(allowed(decide(rules, Place.VAULT, aves, BridgeAction.READ, "DCIM/../../data")))
    }

    @Test
    fun thePickerShowsWhatAtLeastOneAppMayReach() {
        val picker = "com.google.android.documentsui"
        assertFalse(allowed(decide(emptyList(), Place.PERSONAL, picker, BridgeAction.LIST, "")))
        val rules = listOf(gmailReadsWork, AppRule(aves, Place.PERSONAL, reach = Reach.FOLDERS, folders = listOf("Pictures"), write = true))
        assertTrue(allowed(decide(rules, Place.PERSONAL, picker, BridgeAction.LIST, "")))
        assertTrue(allowed(decide(rules, Place.PERSONAL, picker, BridgeAction.READ, "Documents/Work/plan.pdf")))
        assertFalse(allowed(decide(rules, Place.PERSONAL, picker, BridgeAction.READ, "Download/x.apk")))
        // Saving through the picker: only where some app may write.
        assertTrue(allowed(decide(rules, Place.PERSONAL, picker, BridgeAction.CREATE, "Pictures/new.png")))
        assertFalse(allowed(decide(rules, Place.PERSONAL, picker, BridgeAction.CREATE, "Documents/Work/new.txt")))
        assertFalse(allowed(decide(rules, Place.PERSONAL, picker, BridgeAction.DELETE, "Pictures")))
        assertEquals(listOf("Documents/Work", "Pictures"), AccessRules.topEntries(rules, Place.PERSONAL, picker, own, pickers))
        assertEquals(listOf("Documents/Work"), AccessRules.topEntries(rules, Place.PERSONAL, gmail, own, pickers))
        assertEquals(emptyList<String>(), AccessRules.topEntries(rules, Place.PERSONAL, "some.other.app", own, pickers))
        assertNull(AccessRules.topEntries(rules, Place.PERSONAL, own, own, pickers))
    }

    @Test
    fun rulesAreTidiedWhenPut() {
        var rules = AccessRules.put(emptyList(), AppRule(gmail, Place.PERSONAL, reach = Reach.FOLDERS, folders = listOf("/Documents/Work/", "Documents", "Documents/Work"), write = true))
        assertEquals(listOf("Documents"), rules.single().folders)
        // A rule that changes nothing is not kept.
        rules = AccessRules.put(rules, AppRule(gmail, Place.PERSONAL))
        assertTrue(rules.isEmpty())
        // The top of the storage as a folder means all files.
        val all = AccessRules.put(emptyList(), AppRule(gmail, Place.PERSONAL, reach = Reach.FOLDERS, folders = listOf("/")))
        assertEquals(Reach.ALL, all.single().reach)
        // Nothing across cannot be read-write.
        assertFalse(AccessRules.put(emptyList(), AppRule(gmail, Place.PERSONAL, own = OwnFiles.MEDIA, write = true)).single().write)
        // The same app on the other side is another rule.
        val both = AccessRules.put(AccessRules.put(emptyList(), gmailReadsWork), gmailReadsWork.copy(place = Place.VAULT))
        assertEquals(2, both.size)
    }

    @Test
    fun presetsFillARule() {
        val base = AppRule(aves, Place.VAULT, label = "Aves")
        val photos = Preset.PHOTOS_ACROSS.applyTo(base)
        assertEquals(OwnFiles.MEDIA, photos.own)
        assertEquals(listOf("DCIM", "Pictures"), photos.folders)
        assertFalse(photos.write)
        assertEquals("Own side: Photos and media · Personal files: 2 folders, read-only", photos.summary())
        assertTrue(Preset.RESET.applyTo(photos).isDefault)
        assertEquals("Own side: All files · Personal files: all, can change them", Preset.FILE_MANAGER.applyTo(base).summary())
    }

    @Test
    fun theUnionIsWhatThePickerUses() {
        val rules = listOf(
            gmailReadsWork,
            AppRule(aves, Place.PERSONAL, reach = Reach.FOLDERS, folders = listOf("Pictures"), write = true),
            AppRule("x.vault.app", Place.VAULT, reach = Reach.ALL),
        )
        val personal = AccessRules.union(rules, Place.PERSONAL)
        assertFalse(personal.all)
        assertEquals(setOf("Documents/Work", "Pictures"), personal.folders)
        assertEquals(setOf("Pictures"), personal.writeFolders)
        val vault = AccessRules.union(rules, Place.VAULT)
        assertTrue(vault.all)
        assertFalse(vault.allWrite)
    }

    @Test
    fun exportedRulesComeBackAndBadOnesAreRefused() {
        val file = RulesFile(savedAt = 1_795_700_000_000, rules = listOf(gmailReadsWork, Preset.FILE_MANAGER.applyTo(AppRule(aves, Place.VAULT, label = "Aves"))))
        val text = AccessRules.export(file)
        assertTrue(text.contains("\n")) // readable
        assertEquals(file, AccessRules.import(text).getOrThrow())
        assertTrue(AccessRules.import(text.replace("\"version\": 1", "\"version\": 9")).isFailure)
        assertTrue(AccessRules.import(text.replace("com.google.android.gm", "not a package")).isFailure)
        assertTrue(AccessRules.import(text.replace("Documents/Work", "../../data")).isFailure)
        assertTrue(AccessRules.import("{ nope").isFailure)
    }
}
