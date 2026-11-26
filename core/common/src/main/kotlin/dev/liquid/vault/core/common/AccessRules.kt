package dev.liquid.vault.core.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What an app may do with the files of its own side: Android's own permissions, which Liquid Vault sets (Phase 2, D57).
 * In the Vault as its owner (storage and media by permission policy, All files by app-op: F1, F2, F4, F8 on the phone);
 * on the personal side through the shell.
 */
@Serializable
enum class OwnFiles(val label: String, val words: String) {
    ANDROID("Android decides", "Liquid Vault sets nothing: the app asks you, as usual"),
    NONE("No files", "no photos, no media, no files"),
    MEDIA("Photos and media", "photos, videos and music, not other files"),
    ALL("All files", "every file on its side"),
}

/** How far an app reaches into the other side's files, through Liquid Vault's bridge (D58). */
@Serializable
enum class Reach(val label: String) {
    NONE("None"),
    FOLDERS("Chosen folders"),
    ALL("All files"),
}

/**
 * The rule for one app on one side ([place]): its own side's files ([own]) and the other side's ([reach], [folders],
 * [write]). An app on both sides has two rules. [folders] are paths inside the other side's storage ("Documents/Work").
 */
@Serializable
data class AppRule(
    val pkg: String,
    val place: Place,
    val label: String = pkg,
    val own: OwnFiles = OwnFiles.ANDROID,
    val reach: Reach = Reach.NONE,
    val folders: List<String> = emptyList(),
    val write: Boolean = false,
) {
    /** A rule that changes nothing: Android decides, nothing across. Such a rule is not kept. */
    val isDefault: Boolean get() = own == OwnFiles.ANDROID && reach == Reach.NONE

    /** The other side's files, as this app sees them: "Vault files" for a personal app. */
    val acrossName: String get() = AccessRules.acrossName(place)

    /** "Own side: All files · Vault files: 2 folders, read-only". */
    fun summary(): String {
        val rw = if (write) "can change them" else "read-only"
        val across = when (reach) {
            Reach.NONE -> "none"
            Reach.FOLDERS -> if (folders.isEmpty()) "no folder chosen yet" else "${folders.size} folder${if (folders.size == 1) "" else "s"}, $rw"
            Reach.ALL -> "all, $rw"
        }
        return "Own side: ${own.label} · $acrossName: $across"
    }
}

/** The rules as they are saved, exported and synced to the Vault copy. */
@Serializable
data class RulesFile(val version: Int = AccessRules.VERSION, val savedAt: Long = 0, val rules: List<AppRule> = emptyList())

/** What an app asks of a bridge, as the access log names it. */
enum class BridgeAction(val write: Boolean, val words: String) {
    DETAILS(false, "details"),
    LIST(false, "list"),
    READ(false, "read"),
    WRITE(true, "write"),
    CREATE(true, "create"),
    DELETE(true, "delete"),
    RENAME(true, "rename"),
}

/** Paths inside one side's storage: "/" separated, relative to its root, "" for the whole storage. */
object StoragePaths {
    /** "Documents//Work/" → "Documents/Work"; null when the path would leave the storage (".."). */
    fun clean(path: String): String? {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** [path] is [folder] or inside it ("" is the whole storage). */
    fun inside(path: String, folder: String): Boolean = folder.isEmpty() || path == folder || path.startsWith("$folder/")

    fun parent(path: String): String = path.substringBeforeLast('/', "")

    fun name(path: String): String = path.substringAfterLast('/')

    fun child(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

    /** A file or folder name as typed: no separators, not "." or "..", not empty. */
    fun validName(name: String): Boolean = name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != ".." && '\u0000' !in name
}

/**
 * What the apps of one side may reach, all together: what the system picker shows (it browses for whichever app
 * opened it, and Android does not say which, so it shows what at least one of them may reach).
 */
data class ReachUnion(val all: Boolean, val folders: Set<String>, val allWrite: Boolean, val writeFolders: Set<String>) {
    val isEmpty: Boolean get() = !all && folders.isEmpty()
    fun canSee(path: String): Boolean = all || folders.any { StoragePaths.inside(path, it) }
    fun canWrite(path: String): Boolean = allWrite || writeFolders.any { StoragePaths.inside(path, it) }
}

/** A preset fills a rule in one tap; the rule can still be changed after. */
enum class Preset(val label: String, val words: String) {
    NOTHING("No files", "nothing on its side, nothing across"),
    MEDIA_ONLY("Media only", "photos and media on its side, nothing across"),
    PHOTOS_ACROSS("Photos across", "media on its side; the other side's DCIM and Pictures, read-only"),
    FILE_MANAGER("File manager", "all files on its side, and all files across, can change them"),
    RESET("Android decides", "no rule: Android asks as usual, nothing across"),
    ;

    fun applyTo(rule: AppRule): AppRule = when (this) {
        NOTHING -> rule.copy(own = OwnFiles.NONE, reach = Reach.NONE, folders = emptyList(), write = false)
        MEDIA_ONLY -> rule.copy(own = OwnFiles.MEDIA, reach = Reach.NONE, folders = emptyList(), write = false)
        PHOTOS_ACROSS -> rule.copy(own = OwnFiles.MEDIA, reach = Reach.FOLDERS, folders = listOf("DCIM", "Pictures"), write = false)
        FILE_MANAGER -> rule.copy(own = OwnFiles.ALL, reach = Reach.ALL, folders = emptyList(), write = true)
        RESET -> rule.copy(own = OwnFiles.ANDROID, reach = Reach.NONE, folders = emptyList(), write = false)
    }
}

/**
 * The access rules (brief: app × side × scope × read-only/read-write; decision 4: All files per app per side, folders
 * and anything crossing sides through the bridge, by calling package, every access logged). Pure and unit-tested.
 */
object AccessRules {
    const val VERSION = 1

    fun acrossName(appsPlace: Place): String = if (appsPlace == Place.PERSONAL) "Vault files" else "Personal files"

    fun ruleFor(rules: List<AppRule>, pkg: String, place: Place): AppRule? = rules.firstOrNull { it.pkg == pkg && it.place == place }

    /**
     * Puts [rule] in place of the app's rule on that side (a default rule removes it), keeping the order stable.
     * Folders are cleaned, kept once, and dropped when they cover each other ("Documents" covers "Documents/Work").
     */
    fun put(rules: List<AppRule>, rule: AppRule): List<AppRule> {
        val tidy = tidy(rule) ?: return rules.filterNot { it.pkg == rule.pkg && it.place == rule.place }
        val i = rules.indexOfFirst { it.pkg == rule.pkg && it.place == rule.place }
        return if (i < 0) rules + tidy else rules.toMutableList().also { it[i] = tidy }
    }

    /** The rule as it is kept: null when it changes nothing. */
    fun tidy(rule: AppRule): AppRule? {
        val folders = rule.folders.mapNotNull { StoragePaths.clean(it) }.distinct()
        val outer = folders.filter { f -> folders.none { o -> o != f && o.isNotEmpty() && StoragePaths.inside(f, o) } }
        val kept = when (rule.reach) {
            Reach.FOLDERS -> if (outer.any { it.isEmpty() }) rule.copy(reach = Reach.ALL, folders = emptyList()) else rule.copy(folders = outer.sorted())
            else -> rule.copy(folders = emptyList())
        }
        val final = if (kept.reach == Reach.NONE) kept.copy(write = false) else kept
        return final.takeUnless { it.isDefault }
    }

    /** What all apps on [appsPlace] may reach of the other side, together. */
    fun union(rules: List<AppRule>, appsPlace: Place): ReachUnion {
        val mine = rules.filter { it.place == appsPlace && it.reach != Reach.NONE }
        val all = mine.any { it.reach == Reach.ALL }
        val allWrite = mine.any { it.reach == Reach.ALL && it.write }
        val folders = mine.filter { it.reach == Reach.FOLDERS }.flatMap { it.folders }.toSet()
        val writeFolders = mine.filter { it.reach == Reach.FOLDERS && it.write }.flatMap { it.folders }.toSet()
        return ReachUnion(all, folders, allWrite, writeFolders)
    }

    /**
     * Whether [caller], running on [appsPlace], may do [action] on [path] of the other side's files.
     *
     * - Liquid Vault itself ([ownPackage]): always; it is the controller.
     * - The system picker ([pickers]): it browses and reads what at least one app of its side may reach; it may create,
     *   rename or delete where at least one may write (that is you, saving or tidying a file in the picker). The app is
     *   checked by its own package when it opens what you picked.
     * - Any other app: by its own rule. No rule: refused.
     */
    fun decide(
        rules: List<AppRule>,
        appsPlace: Place,
        caller: String?,
        action: BridgeAction,
        path: String,
        ownPackage: String,
        pickers: Set<String>,
    ): BridgeDecision {
        val across = acrossName(appsPlace).lowercase()
        val pkg = caller ?: return BridgeDecision.Refused("Android did not say which app is calling")
        if (pkg == ownPackage) return BridgeDecision.Allowed("Liquid Vault itself")
        val clean = StoragePaths.clean(path) ?: return BridgeDecision.Refused("the path leaves the storage")
        if (action.write && clean.isEmpty()) return BridgeDecision.Refused("the top of the storage cannot be changed through the bridge")
        // Removing or renaming a shared folder itself would end the share: only Liquid Vault does that.
        val structural = action == BridgeAction.DELETE || action == BridgeAction.RENAME
        if (pkg in pickers) {
            val union = union(rules, appsPlace)
            return when {
                union.isEmpty -> BridgeDecision.Refused("the system picker: no app here may reach the $across")
                clean.isEmpty() -> BridgeDecision.Allowed("the system picker, browsing for the app you are using")
                !union.canSee(clean) -> BridgeDecision.Refused("the system picker: no app here may reach that")
                action.write && !union.canWrite(clean) -> BridgeDecision.Refused("the system picker: no app here may change that")
                structural && clean in union.folders -> BridgeDecision.Refused("a shared folder itself cannot be removed or renamed through the bridge")
                else -> BridgeDecision.Allowed("the system picker, ${if (action.write) "changing a file for you" else "browsing for the app you are using"}")
            }
        }
        val rule = ruleFor(rules, pkg, appsPlace)
        if (rule == null || rule.reach == Reach.NONE) return BridgeDecision.Refused("$pkg has no access to the $across")
        val scope = if (rule.reach == Reach.ALL) "all $across" else "its folders"
        val rw = if (rule.write) "can change them" else "read-only"
        if (rule.reach == Reach.FOLDERS && clean.isNotEmpty() && rule.folders.none { StoragePaths.inside(clean, it) }) {
            return BridgeDecision.Refused("$pkg may reach only its chosen folders: ${rule.folders.joinToString()}")
        }
        if (action.write && !rule.write) return BridgeDecision.Refused("$pkg may only read ($scope, read-only)")
        if (structural && rule.reach == Reach.FOLDERS && clean in rule.folders) {
            return BridgeDecision.Refused("a shared folder itself cannot be removed or renamed through the bridge")
        }
        return BridgeDecision.Allowed("$pkg: $scope, $rw")
    }

    /**
     * What a listing of the bridge's top shows to [caller]: the real top of the storage when it may reach all of it,
     * else its folders (the system picker: the folders of every app of its side). Null means the real top.
     */
    fun topEntries(rules: List<AppRule>, appsPlace: Place, caller: String?, ownPackage: String, pickers: Set<String>): List<String>? {
        if (caller == ownPackage) return null
        if (caller in pickers) {
            val union = union(rules, appsPlace)
            return if (union.all) null else union.folders.sorted()
        }
        val rule = caller?.let { ruleFor(rules, it, appsPlace) } ?: return emptyList()
        return when (rule.reach) {
            Reach.ALL -> null
            Reach.FOLDERS -> rule.folders
            Reach.NONE -> emptyList()
        }
    }

    private val exportJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun export(file: RulesFile): String = exportJson.encodeToString(RulesFile.serializer(), file)

    /**
     * Reads exported rules, refusing what would not be safe to apply: a newer format, a package name that is not one,
     * a folder that leaves the storage. Rules that change nothing are dropped; the same app on the same side twice
     * keeps the last one.
     */
    fun import(text: String): Result<RulesFile> = runCatching {
        val file = exportJson.decodeFromString(RulesFile.serializer(), text)
        require(file.version in 1..VERSION) { "these rules are from a newer Liquid Vault (format ${file.version})" }
        val pkgName = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        file.rules.forEach { r ->
            require(pkgName.matches(r.pkg)) { "\"${r.pkg}\" is not an app's package name" }
            r.folders.forEach { f -> requireNotNull(StoragePaths.clean(f)) { "the folder \"$f\" of ${r.pkg} leaves the storage" } }
        }
        var rules = emptyList<AppRule>()
        file.rules.forEach { rules = put(rules, it) }
        file.copy(rules = rules)
    }
}
