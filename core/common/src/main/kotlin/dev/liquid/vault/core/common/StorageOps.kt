package dev.liquid.vault.core.common

/**
 * The storage, media and All files app-ops Diagnostics changes for a Vault app (F4–F7), what Android does with each,
 * and how "Undo" puts them back.
 *
 * Phone test 0.1.0 (D28): an app-op tied to a runtime permission the app asks for does not stay changed. AOSP's
 * PermissionPolicyService watches those ops and puts them back in step with the permission ("Work around incorrectly-set
 * package mode. It never makes sense for app ops related to runtime permissions"), and it resets All files to Android's
 * default for an app that does not ask for All files. So F4–F7 count only the ops app-ops really control: those the app
 * does not ask for, All files when the app asks for it, and a test op tied to no permission ([CONTROL]).
 *
 * Writing the mode "default" is not an undo: it leaves an entry, and for permission-backed ops Android may read
 * "default" as "not allowed". Android's own default (AOSP `AppOpsManager`: "allow" for the storage, media and
 * picture-in-picture ops, "default" for All files) removes the app's entry instead.
 */
object StorageOps {
    const val ALL_FILES = "MANAGE_EXTERNAL_STORAGE"

    /** A test op tied to no permission (nothing puts it back): it shows whether an app-op change reaches the app at all. */
    const val CONTROL = "PICTURE_IN_PICTURE"

    /** The ops F4–F7 change, by their `appops` names. */
    val shellNames: List<String> = listOf(
        "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO",
        ALL_FILES,
    )

    /** What a saved "before" says when the app had no entry of its own for the op. */
    const val NONE = "none"

    /** `READ_MEDIA_IMAGES` or `android:read_media_images` → `READ_MEDIA_IMAGES` (the `appops` command's name). */
    fun shellName(op: String): String = op.removePrefix("android:").uppercase()

    /** `READ_MEDIA_IMAGES` → `android:read_media_images` (the name AppOpsManager takes). */
    fun opString(op: String): String = "android:" + shellName(op).lowercase()

    /** The permission behind an op (`android.permission.READ_MEDIA_IMAGES`), or null for the test op. */
    fun permissionOf(op: String): String? = shellName(op).takeIf { it != CONTROL }?.let { "android.permission.$it" }

    /** Android's default mode for one of these ops; takes `READ_MEDIA_IMAGES` or `android:read_media_images`. */
    fun defaultMode(op: String): String = if (shellName(op) == ALL_FILES) "default" else "allow"

    /** What Android does with a change to this op for an app that asks for [requested] permissions. */
    enum class Kind(val counts: Boolean) {
        /** The app asks for the permission behind it: Android keeps the op in step with the permission. */
        FOLLOWS_PERMISSION(counts = false),

        /**
         * The app does not ask for the permission behind it. On stock Android a change stays; on MagicOS a uid-wide
         * mode keeps it tied to the permission anyway (phone test 0.1.1, D39), so it is reported, not counted.
         */
        FREE(counts = false),

        /** All files, and the app asks for it: the op is the switch. */
        ALL_FILES_ASKED(counts = true),

        /** All files, and the app does not ask for it: Android resets it to its default. */
        ALL_FILES_NOT_ASKED(counts = false),

        /** The test op tied to no permission. */
        CONTROL(counts = true),
    }

    fun kind(op: String, requested: Collection<String>): Kind = when (shellName(op)) {
        CONTROL -> Kind.CONTROL
        ALL_FILES -> if (permissionOf(op) in requested) Kind.ALL_FILES_ASKED else Kind.ALL_FILES_NOT_ASKED
        else -> if (permissionOf(op) in requested) Kind.FOLLOWS_PERMISSION else Kind.FREE
    }

    /** One op as F4–F7 saw it: its mode before, the mode set, right after, and one second later. */
    data class Reading(
        val op: String,
        val kind: Kind,
        val before: String?,
        val target: String,
        val now: String?,
        val later: String?,
        /** Extra words for the row, e.g. the uid mode the shell printed, or an error. */
        val note: String = "",
    ) {
        val stayed: Boolean get() = later == target
    }

    /** The row's line for one op, in plain words. [app] is the app's label, [granted] its permission state if known. */
    fun line(r: Reading, app: String, granted: Boolean?): String {
        val what = when (r.kind) {
            Kind.FOLLOWS_PERMISSION -> "follows $app's permission (${if (granted == true) "granted" else if (granted == false) "not granted" else "?"})"
            Kind.FREE -> "$app does not ask for this permission: a change should stay"
            Kind.ALL_FILES_ASKED -> "All files: $app asks for it"
            Kind.ALL_FILES_NOT_ASKED -> "All files: $app does not ask for it (Android resets it)"
            Kind.CONTROL -> "test op tied to no permission"
        }
        val note = if (r.note.isBlank()) "" else " (${r.note})"
        return "${shellName(r.op)} · $what: was ${r.before ?: "?"} → set ${r.target} → right after ${r.now ?: "?"} → after 1 s ${r.later ?: "?"}$note"
    }

    data class Verdict(val status: CheckStatus, val summary: String)

    /**
     * What F4–F7 conclude from their readings. [who] is "the owner" or "the shell". Only the ops app-ops control count;
     * the ones that follow a permission are reported, never counted (Android puts them back by design).
     */
    fun verdict(readings: List<Reading>, who: String, app: String, deny: Boolean): Verdict {
        val counted = readings.filter { it.kind.counts }
        val storage = readings.filter { it.kind == Kind.FOLLOWS_PERMISSION || it.kind == Kind.FREE }
        val unchanged = storage.filter { it.later != it.target }
        val tail = buildList {
            if (unchanged.isNotEmpty()) add("storage and media app-ops follow $app's permissions here (${names(unchanged)} did not stay changed): use F1/F2 for those")
            val stayedStorage = storage - unchanged.toSet()
            if (stayedStorage.isNotEmpty()) add("${names(stayedStorage)} stayed changed")
            if (readings.any { it.kind == Kind.ALL_FILES_NOT_ASKED }) add("All files: $app does not ask for it")
        }.joinToString("; ")
        val word = if (deny) "denied" else "allowed"
        if (counted.isEmpty()) {
            return Verdict(CheckStatus.INFO, "nothing here that app-ops control" + if (tail.isEmpty()) "" else ": $tail")
        }
        val stayed = counted.filter { it.stayed }
        if (stayed.isEmpty()) {
            val control = if (counted.any { it.kind == Kind.CONTROL }) ", not even the test op tied to no permission" else ""
            return Verdict(CheckStatus.FAIL, "$who could not change $app's app-ops in the Vault$control" + if (tail.isEmpty()) "" else " ($tail)")
        }
        val missed = counted - stayed.toSet()
        if (missed.isNotEmpty()) {
            return Verdict(CheckStatus.FAIL, "${names(missed)} did not stay $word; ${names(stayed)} did" + if (tail.isEmpty()) "" else "; $tail")
        }
        return Verdict(CheckStatus.PASS, "$who's app-ops reach $app in the Vault: ${names(stayed)} stayed $word" + if (tail.isEmpty()) "" else "; $tail")
    }

    private fun names(readings: List<Reading>): String = readings.joinToString { shellName(it.op) }

    /** "OP=mode,OP=mode": the app's own entries before the first change, kept until "Undo". */
    fun encode(modes: Map<String, String>): String = modes.entries.joinToString(",") { "${it.key}=${it.value}" }

    fun decode(text: String?): Map<String, String>? {
        if (text.isNullOrBlank()) return null
        val pairs = text.split(',').map { it.split('=', limit = 2) }
        if (pairs.any { it.size != 2 || it[0].isBlank() || it[1].isBlank() }) return null
        return pairs.associate { it[0] to it[1] }
    }

    /** Photo and video permissions: what an app targeting Android 13+ needs to see your photos and videos. */
    val mediaPermissions: Set<String> = setOf(
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
    )

    /**
     * Why an app cannot show what F1/F2's permission policy does, or null when it can. An app built for Android 13+ that
     * asks for no photo, video or audio permission reaches your files only through the system picker, which needs no
     * permission (phone test 0.1.0: Chrome), so denying or allowing its storage permissions changes nothing you can see.
     */
    fun policyTestUnsuitable(targetSdk: Int, requested: Collection<String>): String? {
        if (targetSdk < 33) return null
        if (requested.any { it in mediaPermissions }) return null
        return "it is built for Android 13 or newer and asks for no photo, video or audio permission: it reaches your files " +
            "only through the system picker, which needs no permission, so these permissions cannot change what it shows"
    }
}
