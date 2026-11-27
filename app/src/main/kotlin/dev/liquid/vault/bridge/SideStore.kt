package dev.liquid.vault.bridge

import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import dev.liquid.vault.core.common.StoragePaths
import java.io.File
import java.io.FileNotFoundException

/**
 * This copy's own side's storage, served to the other copy through the channel for the bridge (Phase 2, D58): list,
 * details, open, create, rename, delete — always inside /storage/emulated/<this user>: paths are cleaned and the real
 * (canonical) file must stay inside, so neither ".." nor a link leads out. The rules are checked by the bridge that
 * faces the apps, in the other copy; this side only serves its storage to its own other copy (the channel's check).
 * It needs All files on its side: the Vault copy gives it to itself as the owner, the personal copy asks you.
 */
object SideStore {
    fun root(): File = Environment.getExternalStorageDirectory()

    /** Whether this copy may read every file of its side (All files). */
    fun canReadAll(): Boolean = Environment.isExternalStorageManager()

    fun resolve(relative: String): File? {
        val clean = StoragePaths.clean(relative) ?: return null
        val base = root().canonicalFile
        val f = File(base, clean).canonicalFile
        val inside = f.path == base.path || f.path.startsWith(base.path + File.separator)
        return f.takeIf { inside }
    }

    private fun existing(relative: String): File = resolve(relative)?.takeIf { it.exists() } ?: throw FileNotFoundException(relative)

    fun mimeOf(file: File): String = if (file.isDirectory) Document.MIME_TYPE_DIR else
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    private fun needAllFiles(): Nothing = throw IllegalStateException(
        if (dev.liquid.vault.Env.isVaultSide) "the Vault copy may not read all of the Vault's files yet (it allows itself when it starts): try again"
        else "Liquid Vault may not read your personal files yet: allow \"All files access\" for it (Home › Access rules)",
    )

    /** A folder's entries, sorted by name, in the shape the bridge reads. */
    fun list(relative: String): Bundle {
        val dir = existing(relative).takeIf { it.isDirectory } ?: throw FileNotFoundException("$relative is not a folder")
        val files = dir.listFiles() ?: if (!canReadAll()) needAllFiles() else emptyArray()
        val sorted = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        return Bundle().apply {
            putStringArray("names", sorted.map { it.name }.toTypedArray())
            putBooleanArray("dirs", sorted.map { it.isDirectory }.toBooleanArray())
            putLongArray("sizes", sorted.map { if (it.isFile) it.length() else 0L }.toLongArray())
            putLongArray("modified", sorted.map { it.lastModified() }.toLongArray())
            putStringArray("mimes", sorted.map { mimeOf(it) }.toTypedArray())
        }
    }

    /** One entry's details; "exists" is false when there is nothing at that path. */
    fun stat(relative: String): Bundle {
        val f = resolve(relative)
        return Bundle().apply {
            putBoolean("exists", f != null && f.exists())
            if (f != null && f.exists()) {
                putBoolean("dir", f.isDirectory)
                putLong("size", if (f.isFile) f.length() else 0L)
                putLong("modified", f.lastModified())
                putString("mime", mimeOf(f))
            }
        }
    }

    /** What the bridge's root shows about this side. */
    fun info(): Bundle = Bundle().apply {
        putBoolean("allFiles", canReadAll())
        putString("root", root().path)
        putLong("free", runCatching { root().usableSpace }.getOrDefault(-1L))
    }

    /** Creates a file or a folder in [parent], with a free name ("name (1).ext" when taken); returns its path. */
    fun create(parent: String, name: String, mime: String): String {
        require(StoragePaths.validName(name)) { "not a file name: $name" }
        val dir = existing(parent).takeIf { it.isDirectory } ?: throw FileNotFoundException("$parent is not a folder")
        val target = freeName(dir, name, mime == Document.MIME_TYPE_DIR)
        val ok = if (mime == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!ok) { if (!canReadAll()) needAllFiles() else error("could not create ${target.name}") }
        return StoragePaths.child(StoragePaths.clean(parent) ?: "", target.name)
    }

    private fun freeName(dir: File, name: String, isDir: Boolean): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = if (isDir || !name.contains('.')) name else name.substringBeforeLast('.')
        val ext = if (isDir || !name.contains('.')) "" else "." + name.substringAfterLast('.')
        var n = 1
        while (f.exists()) f = File(dir, "$base (${n++})$ext")
        return f
    }

    fun delete(relative: String) {
        require((StoragePaths.clean(relative) ?: "").isNotEmpty()) { "the top of the storage cannot be deleted" }
        val f = existing(relative)
        if (!f.deleteRecursively()) error("could not delete ${f.name}")
    }

    /** Renames a file or folder in place; returns its new path. */
    fun rename(relative: String, name: String): String {
        require(StoragePaths.validName(name)) { "not a file name: $name" }
        val clean = StoragePaths.clean(relative)?.takeIf { it.isNotEmpty() } ?: error("the top of the storage cannot be renamed")
        val f = existing(clean)
        val target = File(f.parentFile, name)
        if (target.exists()) error("$name already exists")
        if (!f.renameTo(target)) error("could not rename ${f.name}")
        return StoragePaths.child(StoragePaths.parent(clean), name)
    }

    /** Opens a file ("r", "w", "wt", "wa", "rw", "rwt"); writing creates it if needed, inside its folder only. */
    fun open(relative: String, mode: String): ParcelFileDescriptor {
        val f = resolve(relative) ?: throw FileNotFoundException(relative)
        if (f.isDirectory) throw FileNotFoundException("$relative is a folder")
        if (mode == "r" && !f.exists()) throw FileNotFoundException(relative)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }
}
