package dev.liquid.vault.bridge

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import dev.liquid.vault.Env
import dev.liquid.vault.R
import dev.liquid.vault.admin.Crossing
import dev.liquid.vault.channel.Channel
import dev.liquid.vault.core.common.AccessEntry
import dev.liquid.vault.core.common.AccessRules
import dev.liquid.vault.core.common.AppRule
import dev.liquid.vault.core.common.BridgeAction
import dev.liquid.vault.core.common.BridgeCaller
import dev.liquid.vault.core.common.BridgeDecision
import dev.liquid.vault.core.common.BridgeRules
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.Place
import dev.liquid.vault.core.common.StoragePaths
import dev.liquid.vault.core.common.VaultJson
import dev.liquid.vault.diag.Prefs
import dev.liquid.vault.rules.RulesStore
import java.io.FileNotFoundException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Vault's test folder (check B0): Documents/Liquid Vault test, inside the Vault's own storage, with two small
 * files this app writes itself (so it can read them without any storage permission).
 */
object TestFolder {
    const val NAME = "Liquid Vault test"

    /** Its path inside the Vault's storage, as the bridge names it. */
    const val PATH = "Documents/$NAME"

    fun dir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), NAME)

    fun ensure(): List<String> {
        val lines = mutableListOf<String>()
        val dir = dir()
        if (!dir.isDirectory && !dir.mkdirs()) error("could not create ${dir.path}")
        val notes = File(dir, "notes").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())
        val hello = File(dir, "hello.txt")
        if (!hello.isFile) {
            hello.writeText(
                "Hello from the Vault.\nWritten by Liquid Vault ${Env.versionName()} on $stamp in user ${Env.myUserId}.\n" +
                    "If you can read this from the personal side, the bridge works.\n",
            )
        }
        val inside = File(notes, "inside.txt")
        if (!inside.isFile) inside.writeText("A file in a folder inside the test folder ($stamp).\n")
        lines += "folder: ${dir.path}"
        dir.walkTopDown().filter { it.isFile }.forEach { lines += "  ${it.relativeTo(dir).path} (${it.length()} bytes)" }
        return lines
    }

    /**
     * A photo for the storage checks (F1–F7): written through MediaStore into Pictures/Liquid Vault test, so a Vault
     * gallery or file app sees it exactly when it is allowed to see your photos. Needs no permission (own file).
     */
    fun addTestPhoto(): String {
        val size = 720
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), 0xFF7E57C2.toInt(), 0xFF1E88E5.toInt(), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 54f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        canvas.drawText("Liquid Vault", size / 2f, size / 2f - 20f, paint)
        paint.textSize = 34f
        canvas.drawText("test photo · ${SimpleDateFormat("HH:mm", Locale.ROOT).format(Date())}", size / 2f, size / 2f + 40f, paint)
        val resolver = Env.app.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "liquid-vault-test-${System.currentTimeMillis()}.png")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$NAME")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("MediaStore did not create the photo")
        resolver.openOutputStream(uri)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        values.clear()
        values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        DebugLog.event("Storage", "a test photo was put in the Vault: Pictures/$NAME ($uri)")
        return "Pictures/$NAME"
    }

    /** The file at [relative] inside the test folder, or null if it is outside it or missing. */
    fun resolve(relative: String): File? {
        val base = dir().canonicalFile
        val f = File(base, relative).canonicalFile
        val inside = f.path == base.path || f.path.startsWith(base.path + File.separator)
        return f.takeIf { inside && it.exists() }
    }

    fun mimeOf(file: File): String = if (file.isDirectory) Document.MIME_TYPE_DIR else
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    /** The listing bridge B fetches through the channel. */
    fun listForChannel(relative: String): Bundle {
        val dir = resolve(relative)?.takeIf { it.isDirectory } ?: throw java.io.FileNotFoundException(relative)
        val files = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
        return Bundle().apply {
            putStringArray("names", files.map { it.name }.toTypedArray())
            putBooleanArray("dirs", files.map { it.isDirectory }.toBooleanArray())
            putLongArray("sizes", files.map { if (it.isFile) it.length() else 0L }.toLongArray())
            putLongArray("modified", files.map { it.lastModified() }.toLongArray())
            putStringArray("mimes", files.map { mimeOf(it) }.toTypedArray())
        }
    }
}

/** Every bridge access, allowed or refused (brief decision 4). Kept in memory and appended to a file. */
object AccessLog {
    private val recent = ArrayDeque<AccessEntry>()
    private var loaded = false
    private var lastKey: String? = null
    private var lastAt = 0L

    private fun file() = File(Env.app.filesDir, "access-log.jsonl")

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            file().takeIf { it.isFile }?.readLines()?.takeLast(200)?.forEach { line ->
                runCatching { recent.addLast(VaultJson.decodeFromString(AccessEntry.serializer(), line)) }
            }
        }
    }

    @Synchronized
    fun add(entry: AccessEntry) {
        load()
        // The system picker asks the same thing several times a second while you browse: one line per second is enough.
        val key = "${entry.via}|${entry.callerPackage}|${entry.action}|${entry.document}|${entry.allowed}"
        if (key == lastKey && entry.atMillis - lastAt < 1_000) return
        lastKey = key
        lastAt = entry.atMillis
        recent.addLast(entry)
        while (recent.size > 200) recent.removeFirst()
        runCatching { file().appendText(VaultJson.encodeToString(AccessEntry.serializer(), entry) + "\n") }
        val verdict = if (entry.allowed) "allowed" else "REFUSED"
        DebugLog.event("Bridge", "[${entry.via}] ${entry.callerPackage} (user ${entry.callerUser}) ${entry.action} ${entry.document}: $verdict (${entry.reason})")
    }

    @Synchronized
    fun entries(): List<AccessEntry> {
        load()
        return recent.toList()
    }

    /** The newest entries as JSON lines, for the other copy's access log screen. */
    fun jsonLines(max: Int = 200): List<String> = entries().takeLast(max).map { VaultJson.encodeToString(AccessEntry.serializer(), it) }
}

/** Phase 0's allowed list for the bridge (each copy keeps its own). Liquid Vault itself is always allowed. */
object BridgeAllowlist {
    fun get(): Set<String> = Prefs.app.getStringSet(Prefs.BRIDGE_ALLOWED, emptySet())?.toSet() ?: emptySet()
    fun add(pkg: String) = Prefs.app.edit().putStringSet(Prefs.BRIDGE_ALLOWED, get() + pkg).apply()
    fun remove(pkg: String) = Prefs.app.edit().putStringSet(Prefs.BRIDGE_ALLOWED, get() - pkg).apply()

    /** The system picker's packages: whatever handles "open folder" among system apps, plus the known names. */
    fun systemPickers(): Set<String> {
        val pm = Env.app.packageManager
        val found = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), android.content.pm.PackageManager.MATCH_SYSTEM_ONLY)
                .map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        return found.toSet() + setOf("com.android.documentsui", "com.google.android.documentsui")
    }
}

/**
 * Bridge A's switch (Vault side): the profile owner lets the personal side's system picker open the Work tab. Undo
 * removes this app's filters and puts the channel's back.
 */
object PickerFilters {
    fun enable(): List<String> {
        val dpm = Env.dpm
        val lines = mutableListOf<String>()
        dpm.addCrossProfileIntentFilter(Env.admin, Crossing.filter(Intent.ACTION_OPEN_DOCUMENT_TREE, Intent.CATEGORY_DEFAULT), Crossing.PERSONAL_TO_VAULT)
        lines += "personal apps' \"open folder\" (OPEN_DOCUMENT_TREE) may reach the Vault"
        for (action in listOf(Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT)) {
            val f = IntentFilter(action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("*/*")
            }
            dpm.addCrossProfileIntentFilter(Env.admin, f, Crossing.PERSONAL_TO_VAULT)
            lines += "personal apps' ${action.substringAfterLast('.')} may reach the Vault"
        }
        Prefs.app.edit().putBoolean(Prefs.B1_FILTERS_ON, true).apply()
        DebugLog.event("Bridge", "bridge A: the picker's Work tab is open to personal apps")
        return lines
    }

    fun disable(): List<String> {
        val dpm = Env.dpm
        dpm.clearCrossProfileIntentFilters(Env.admin)
        Channel.addCrossProfileFilters(dpm)
        Prefs.app.edit().putBoolean(Prefs.B1_FILTERS_ON, false).apply()
        DebugLog.event("Bridge", "bridge A: the picker's Work tab is closed again (only the channel's filters are set)")
        return listOf("removed this app's cross-profile filters", "put the channel's two filters back")
    }

    fun isOn(): Boolean = Prefs.app.getBoolean(Prefs.B1_FILTERS_ON, false)
}

/** One file or folder a bridge shows; [path] is its path inside the other side's storage ("" for the top). */
data class BridgeEntry(val name: String, val path: String, val isDir: Boolean, val size: Long, val modified: Long, val mime: String)

/**
 * Phase 2's bridge (D58): a "Liquid Vault" place in the system picker showing the other side's files, as far as the
 * rules let the calling app reach: checked by calling package ([AccessRules.decide]) on every call and logged. The
 * files come from the other copy through the channel ([SideStore] there). Document ids: "root" for the top,
 * "p:<path>" for the rest. The Work tab (bridge A of Phase 0) stays closed (Q3).
 */
abstract class CrossBridgeProvider(private val via: String, private val appsPlace: Place) : DocumentsProvider() {
    abstract val rootSummary: String

    /** This copy is the one this bridge belongs in. */
    abstract fun onRightSide(): Boolean

    /** Folders the system picker may always browse and read (Phase 0's test folder, so B2 and B3 still work). */
    open val pickerFolders: Set<String> = emptySet()

    /** Phase 0's document ids still held as lasting grants on the phone (R3). */
    open fun legacyPath(documentId: String): String? = null

    override fun onCreate(): Boolean {
        context?.let { Env.init(it) }
        return true
    }

    private val authority: String get() = "${Env.app.packageName}.bridge.${if (appsPlace == Place.PERSONAL) "personal" else "vault"}"

    private fun rules(): List<AppRule> = RulesStore.rules(Env.app)

    private fun pathOf(documentId: String): String = when {
        documentId == ROOT_DOC -> ""
        documentId.startsWith(PREFIX) -> StoragePaths.clean(documentId.removePrefix(PREFIX)) ?: throw FileNotFoundException(documentId)
        else -> legacyPath(documentId) ?: throw FileNotFoundException("unknown document $documentId")
    }

    private fun idOf(path: String): String = if (path.isEmpty()) ROOT_DOC else PREFIX + path

    private fun decideFor(pkg: String?, action: BridgeAction, path: String): BridgeDecision {
        val pickers = BridgeAllowlist.systemPickers()
        if (pkg in pickers && !action.write && pickerFolders.isNotEmpty() && (path.isEmpty() || pickerFolders.any { StoragePaths.inside(path, it) })) {
            val byRules = AccessRules.decide(rules(), appsPlace, pkg, action, path, Env.app.packageName, pickers)
            if (byRules is BridgeDecision.Allowed) return byRules
            return BridgeDecision.Allowed("the system picker, browsing the Liquid Vault test folder")
        }
        return AccessRules.decide(rules(), appsPlace, pkg, action, path, Env.app.packageName, pickers)
    }

    /** Checks the calling app, logs the call, and refuses with the reason. Returns the calling package. */
    private fun gate(action: BridgeAction, path: String): String? {
        Env.init(context ?: throw SecurityException("no context"))
        val pkg = callingPackage
        val user = Binder.getCallingUid() / 100_000
        val decision = decideFor(pkg, action, path)
        AccessLog.add(AccessEntry(System.currentTimeMillis(), via, pkg, user, action.words, path.ifEmpty { "(top)" }, decision is BridgeDecision.Allowed, decision.reason))
        if (decision is BridgeDecision.Refused) throw SecurityException("Liquid Vault refused: ${decision.reason}")
        return pkg
    }

    /** The other copy's answer, or the reason it could not answer as a FileNotFoundException the picker shows. */
    private fun <T> remote(what: String, block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw e
    } catch (e: Exception) {
        throw FileNotFoundException("$what: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun list(path: String): List<BridgeEntry> = remote(path.ifEmpty { "the top" }) {
        val b = Channel.call("storeList", path) ?: return@remote emptyList()
        val names = b.getStringArray("names") ?: return@remote emptyList()
        val dirs = b.getBooleanArray("dirs") ?: BooleanArray(names.size)
        val sizes = b.getLongArray("sizes") ?: LongArray(names.size)
        val modified = b.getLongArray("modified") ?: LongArray(names.size)
        val mimes = b.getStringArray("mimes") ?: Array(names.size) { "application/octet-stream" }
        names.indices.map { i -> BridgeEntry(names[i], StoragePaths.child(path, names[i]), dirs[i], sizes[i], modified[i], mimes[i]) }
    }

    private fun stat(path: String): BridgeEntry? {
        if (path.isEmpty()) return BridgeEntry("Liquid Vault", "", true, 0L, 0L, Document.MIME_TYPE_DIR)
        val b = remote(path) { Channel.call("storeStat", path) } ?: return null
        if (!b.getBoolean("exists")) return null
        return BridgeEntry(StoragePaths.name(path), path, b.getBoolean("dir"), b.getLong("size"), b.getLong("modified"), b.getString("mime") ?: "application/octet-stream")
    }

    /** The top as [pkg] sees it: the other side's real top, or the chosen folders. */
    private fun topFor(pkg: String?): List<BridgeEntry> {
        val pickers = BridgeAllowlist.systemPickers()
        val chosen = AccessRules.topEntries(rules(), appsPlace, pkg, Env.app.packageName, pickers)
            ?.let { if (pkg in pickers) (it + pickerFolders).distinct().sorted() else it }
            ?: return list("")
        return chosen.mapNotNull { folder -> stat(folder)?.copy(name = folder.replace("/", " › ")) }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        if (!runCatching { onRightSide() && Channel.ready() }.getOrDefault(false)) return c
        val union = AccessRules.union(rules(), appsPlace)
        // No app of this side may reach anything across: no "Liquid Vault" place in its picker at all.
        if (union.isEmpty && pickerFolders.isEmpty()) return c
        var flags = Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY
        if (union.allWrite || union.writeFolders.isNotEmpty()) flags = flags or Root.FLAG_SUPPORTS_CREATE
        c.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC)
            .add(Root.COLUMN_TITLE, "Liquid Vault")
            .add(Root.COLUMN_SUMMARY, rootSummary)
            .add(Root.COLUMN_FLAGS, flags)
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            .add(Root.COLUMN_MIME_TYPES, "*/*")
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val path = pathOf(documentId)
        val pkg = gate(BridgeAction.DETAILS, path)
        val c = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addRow(c, stat(path) ?: throw FileNotFoundException(documentId), pkg)
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val path = pathOf(parentDocumentId)
        val pkg = gate(BridgeAction.LIST, path)
        val c = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        (if (path.isEmpty()) topFor(pkg) else list(path)).forEach { addRow(c, it, pkg) }
        c.setNotificationUri(Env.app.contentResolver, DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId))
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val path = pathOf(documentId)
        val write = mode.contains('w') || mode.contains('a')
        gate(if (write) BridgeAction.WRITE else BridgeAction.READ, path)
        return remote(path) { Channel.openStore(path, mode) } ?: throw FileNotFoundException(documentId)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = pathOf(parentDocumentId)
        require(StoragePaths.validName(displayName)) { "not a file name: $displayName" }
        gate(BridgeAction.CREATE, StoragePaths.child(parent, displayName))
        val made = remote(displayName) {
            Channel.call("storeCreate", null, Bundle().apply { putString("parent", parent); putString("name", displayName); putString("mime", mimeType) })?.getString("path")
        } ?: throw FileNotFoundException("$displayName was not created")
        notifyChildren(parentDocumentId)
        return idOf(made)
    }

    override fun deleteDocument(documentId: String) {
        val path = pathOf(documentId)
        gate(BridgeAction.DELETE, path)
        remote(path) { Channel.call("storeDelete", path) }
        notifyChildren(idOf(StoragePaths.parent(path)))
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val path = pathOf(documentId)
        require(StoragePaths.validName(displayName)) { "not a file name: $displayName" }
        gate(BridgeAction.RENAME, path)
        val renamed = remote(path) {
            Channel.call("storeRename", null, Bundle().apply { putString("path", path); putString("name", displayName) })?.getString("path")
        } ?: throw FileNotFoundException("$path was not renamed")
        notifyChildren(idOf(StoragePaths.parent(path)))
        return idOf(renamed).takeIf { it != documentId }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = runCatching { pathOf(parentDocumentId) }.getOrNull() ?: return false
        val child = runCatching { pathOf(documentId) }.getOrNull() ?: return false
        return StoragePaths.inside(child, parent)
    }

    override fun getDocumentType(documentId: String): String {
        val path = pathOf(documentId)
        return if (path.isEmpty()) Document.MIME_TYPE_DIR else stat(path)?.mime ?: "application/octet-stream"
    }

    private fun notifyChildren(parentDocumentId: String) {
        runCatching { Env.app.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId), null) }
    }

    /** What [pkg] may do with [e], for the picker's buttons (the call itself is checked again when it comes). */
    private fun flagsFor(pkg: String?, e: BridgeEntry): Int {
        fun ok(action: BridgeAction, path: String) = decideFor(pkg, action, path) is BridgeDecision.Allowed
        var f = 0
        if (!e.isDir && ok(BridgeAction.WRITE, e.path)) f = f or Document.FLAG_SUPPORTS_WRITE
        if (e.isDir && ok(BridgeAction.CREATE, StoragePaths.child(e.path, "new"))) f = f or Document.FLAG_DIR_SUPPORTS_CREATE
        if (e.path.isNotEmpty() && ok(BridgeAction.DELETE, e.path)) f = f or Document.FLAG_SUPPORTS_DELETE
        if (e.path.isNotEmpty() && ok(BridgeAction.RENAME, e.path)) f = f or Document.FLAG_SUPPORTS_RENAME
        return f
    }

    private fun addRow(c: MatrixCursor, e: BridgeEntry, pkg: String?) {
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, idOf(e.path))
            .add(Document.COLUMN_DISPLAY_NAME, e.name)
            .add(Document.COLUMN_MIME_TYPE, e.mime)
            .add(Document.COLUMN_SIZE, e.size)
            .add(Document.COLUMN_LAST_MODIFIED, e.modified)
            .add(Document.COLUMN_FLAGS, flagsFor(pkg, e))
    }

    companion object {
        const val ROOT_ID = "liquid-vault"
        const val ROOT_DOC = "root"
        const val PREFIX = "p:"

        /** The document id of [path] (for grants and the checks). */
        fun documentId(path: String): String = if (path.isEmpty()) ROOT_DOC else PREFIX + path

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES,
        )
        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        fun notifyRootsChanged(authority: String) {
            runCatching { Env.app.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority), null) }
        }
    }
}

/**
 * In the Vault copy: the personal side's files for Vault apps ("Liquid Vault · Personal files" in their picker),
 * served by the personal copy. (In Phase 0 this provider was bridge A: the Vault's test folder in the Work tab.)
 */
class VaultBridgeProvider : CrossBridgeProvider("V", Place.VAULT) {
    override val rootSummary = "Personal files"
    override fun onRightSide(): Boolean = Env.isVaultSide
}

/**
 * In the personal copy: the Vault's files for personal apps ("Liquid Vault · Vault files" in their picker), served by
 * the Vault copy. The Work tab stays closed.
 */
class PersonalBridgeProvider : CrossBridgeProvider("B", Place.PERSONAL) {
    override val rootSummary = "Vault files"
    override fun onRightSide(): Boolean = !Env.isVaultSide
    override val pickerFolders: Set<String> = setOf(TestFolder.PATH)

    // Phase 0's ids ("test", "test/<path>") pointed into the Vault's test folder.
    override fun legacyPath(documentId: String): String? = when {
        documentId == "test" -> TestFolder.PATH
        documentId.startsWith("test/") -> StoragePaths.clean(TestFolder.PATH + "/" + documentId.removePrefix("test/"))
        else -> null
    }
}

/** Lends a folder of this copy's bridge to an app, as the system picker would (for the emulator test, D60). */
object BridgeGrants {
    fun grant(context: Context, pkg: String, folder: String, file: String?): Bundle {
        val side = if (Env.isVaultSide) "vault" else "personal"
        val authority = "${context.packageName}.bridge.$side"
        val clean = StoragePaths.clean(folder) ?: error("not a folder: $folder")
        val docId = CrossBridgeProvider.documentId(clean)
        val tree = DocumentsContract.buildTreeDocumentUri(authority, docId)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        context.grantUriPermission(pkg, tree, flags)
        DebugLog.event("Bridge", "lent ${clean.ifEmpty { "the top" }} of the $side bridge to $pkg")
        return Bundle().apply {
            putString("tree", tree.toString())
            putString("children", DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId).toString())
            file?.let { putString("file", DocumentsContract.buildDocumentUriUsingTree(tree, CrossBridgeProvider.documentId(StoragePaths.child(clean, it))).toString()) }
        }
    }
}
