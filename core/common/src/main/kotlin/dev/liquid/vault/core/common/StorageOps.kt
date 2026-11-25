package dev.liquid.vault.core.common

/**
 * The storage, media and All files app-ops Diagnostics changes for a Vault app (F4–F7), and how "Undo" puts them back.
 *
 * Writing the mode "default" is not an undo: it leaves an entry, and for permission-backed ops Android may read
 * "default" as "not allowed". Android's own default (AOSP `AppOpsManager`: "allow" for the storage and media ops,
 * "default" for All files; `appops get` printed the same on the emulator, run 10) removes the app's entry instead.
 */
object StorageOps {
    /** The ops the shell changes (F6/F7), by their `appops` names. */
    val shellNames: List<String> = listOf(
        "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO",
        "MANAGE_EXTERNAL_STORAGE",
    )

    /** What a saved "before" says when the app had no entry of its own for the op. */
    const val NONE = "none"

    /** Android's default mode for one of these ops; takes `READ_MEDIA_IMAGES` or `android:read_media_images`. */
    fun defaultMode(op: String): String =
        if (op.removePrefix("android:").uppercase() == "MANAGE_EXTERNAL_STORAGE") "default" else "allow"

    /** "OP=mode,OP=mode": the app's own entries before the first change, kept until "Undo". */
    fun encode(modes: Map<String, String>): String = modes.entries.joinToString(",") { "${it.key}=${it.value}" }

    fun decode(text: String?): Map<String, String>? {
        if (text.isNullOrBlank()) return null
        val pairs = text.split(',').map { it.split('=', limit = 2) }
        if (pairs.any { it.size != 2 || it[0].isBlank() || it[1].isBlank() }) return null
        return pairs.associate { it[0] to it[1] }
    }
}
