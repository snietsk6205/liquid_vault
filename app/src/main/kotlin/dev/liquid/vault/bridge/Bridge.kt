package dev.liquid.vault.bridge

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
import dev.liquid.vault.core.common.BridgeCaller
import dev.liquid.vault.core.common.BridgeDecision
import dev.liquid.vault.core.common.BridgeRules
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.VaultJson
import dev.liquid.vault.diag.Prefs
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

/** One file or folder a bridge shows. [relative] is its path inside the test folder ("" for the folder itself). */
data class BridgeEntry(val name: String, val relative: String, val isDir: Boolean, val size: Long, val modified: Long, val mime: String)

/**
 * What both bridges share: one root showing the test folder, read-only in Phase 0, every call checked against
 * [BridgeRules] by calling package and written to the [AccessLog]. Document ids are "test" and "test/<path>".
 */
abstract class BridgeProvider(private val via: String) : DocumentsProvider() {
    abstract val rootTitle: String
    abstract val rootSummary: String
    abstract fun available(): Boolean
    abstract fun children(relative: String): List<BridgeEntry>
    abstract fun stat(relative: String): BridgeEntry?
    abstract fun openRead(relative: String): ParcelFileDescriptor

    override fun onCreate(): Boolean {
        context?.let { Env.init(it) }
        return true
    }

    private fun relativeOf(documentId: String): String {
        require(documentId == ROOT_DOC || documentId.startsWith("$ROOT_DOC/")) { "unknown document $documentId" }
        return documentId.removePrefix(ROOT_DOC).removePrefix("/")
    }

    private fun gate(action: String, documentId: String) {
        val context = context ?: throw SecurityException("no context")
        Env.init(context)
        val caller = BridgeCaller(callingPackage, Binder.getCallingUid() / 100_000)
        val decision = BridgeRules.decide(caller, context.packageName, BridgeAllowlist.get(), BridgeAllowlist.systemPickers())
        AccessLog.add(AccessEntry(System.currentTimeMillis(), via, caller.packageName, caller.userId, action, documentId, decision is BridgeDecision.Allowed, decision.reason))
        if (decision is BridgeDecision.Refused) throw SecurityException("Liquid Vault refused: ${decision.reason}")
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        if (!runCatching { available() }.getOrDefault(false)) return c
        c.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC)
            .add(Root.COLUMN_TITLE, rootTitle)
            .add(Root.COLUMN_SUMMARY, rootSummary)
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            .add(Root.COLUMN_MIME_TYPES, "*/*")
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        gate("details", documentId)
        val c = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val entry = stat(relativeOf(documentId)) ?: throw java.io.FileNotFoundException(documentId)
        addRow(c, entry)
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        gate("list", parentDocumentId)
        val c = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        children(relativeOf(parentDocumentId)).forEach { addRow(c, it) }
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") {
            gate("write ($mode)", documentId)
            throw SecurityException("Liquid Vault: the test folder is read-only in Phase 0")
        }
        gate("open", documentId)
        return openRead(relativeOf(documentId))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    override fun getDocumentType(documentId: String): String =
        stat(relativeOf(documentId))?.mime ?: "application/octet-stream"

    private fun addRow(c: MatrixCursor, e: BridgeEntry) {
        val id = if (e.relative.isEmpty()) ROOT_DOC else "$ROOT_DOC/${e.relative}"
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, e.name)
            .add(Document.COLUMN_MIME_TYPE, e.mime)
            .add(Document.COLUMN_SIZE, e.size)
            .add(Document.COLUMN_LAST_MODIFIED, e.modified)
            .add(Document.COLUMN_FLAGS, 0)
    }

    companion object {
        const val ROOT_ID = "vault-test"
        const val ROOT_DOC = "test"
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

/** Bridge A, in the Vault copy: the test folder straight from the Vault's storage, reached through the Work tab. */
class VaultBridgeProvider : BridgeProvider("A") {
    override val rootTitle = TestFolder.NAME
    override val rootSummary = "Vault side · bridge A (Liquid Vault)"
    override fun available(): Boolean = Env.isVaultSide && TestFolder.dir().isDirectory

    private fun entryOf(file: File, base: File) = BridgeEntry(
        name = if (file == base) TestFolder.NAME else file.name,
        relative = if (file == base) "" else file.relativeTo(base).path,
        isDir = file.isDirectory,
        size = if (file.isFile) file.length() else 0L,
        modified = file.lastModified(),
        mime = TestFolder.mimeOf(file),
    )

    override fun children(relative: String): List<BridgeEntry> {
        val base = TestFolder.dir().canonicalFile
        val dir = TestFolder.resolve(relative)?.takeIf { it.isDirectory } ?: throw java.io.FileNotFoundException(relative)
        return dir.listFiles()?.sortedBy { it.name.lowercase() }?.map { entryOf(it.canonicalFile, base) } ?: emptyList()
    }

    override fun stat(relative: String): BridgeEntry? {
        val base = TestFolder.dir().canonicalFile
        return TestFolder.resolve(relative)?.let { entryOf(it.canonicalFile, base) }
    }

    override fun openRead(relative: String): ParcelFileDescriptor {
        val f = TestFolder.resolve(relative)?.takeIf { it.isFile } ?: throw java.io.FileNotFoundException(relative)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}

/**
 * Bridge B, in the personal copy: a "Liquid Vault" place in the personal picker. It shows the Vault's test folder by
 * asking the Vault copy through the channel; the Work tab stays closed.
 */
class PersonalBridgeProvider : BridgeProvider("B") {
    override val rootTitle = "Liquid Vault"
    override val rootSummary = "Vault test folder · bridge B"
    override fun available(): Boolean = !Env.isVaultSide && Channel.ready()

    override fun children(relative: String): List<BridgeEntry> {
        val b = Channel.call("bridgeList", relative) ?: return emptyList()
        val names = b.getStringArray("names") ?: return emptyList()
        val dirs = b.getBooleanArray("dirs") ?: BooleanArray(names.size)
        val sizes = b.getLongArray("sizes") ?: LongArray(names.size)
        val modified = b.getLongArray("modified") ?: LongArray(names.size)
        val mimes = b.getStringArray("mimes") ?: Array(names.size) { "application/octet-stream" }
        return names.indices.map { i ->
            val rel = if (relative.isEmpty()) names[i] else "$relative/${names[i]}"
            BridgeEntry(names[i], rel, dirs[i], sizes[i], modified[i], mimes[i])
        }
    }

    override fun stat(relative: String): BridgeEntry? {
        if (relative.isEmpty()) return BridgeEntry(TestFolder.NAME, "", true, 0L, 0L, Document.MIME_TYPE_DIR)
        val parent = relative.substringBeforeLast('/', "")
        return children(parent).firstOrNull { it.relative == relative }
    }

    override fun openRead(relative: String): ParcelFileDescriptor =
        Channel.open(relative) ?: throw java.io.FileNotFoundException(relative)
}
