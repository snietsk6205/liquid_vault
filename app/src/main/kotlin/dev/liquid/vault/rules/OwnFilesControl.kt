package dev.liquid.vault.rules

import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import dev.liquid.vault.Env
import dev.liquid.vault.admin.OwnerAppOps
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.OwnFiles
import dev.liquid.vault.core.common.asEvidence

/**
 * An app's own side's files (Phase 2, D57). In the Vault, the Vault copy sets them as the owner, as F1/F2 (permission
 * policy) and F4/F8 (All files app-op) showed on the phone; on the personal side the shell does (a waiting action).
 */
object OwnFilesControl {
    /** The storage and media permissions Liquid Vault sets; only those the app asks for are touched. */
    val storagePermissions: List<String> = buildList {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        add(Manifest.permission.READ_MEDIA_AUDIO)
        if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    private fun allRequested(context: Context, pkg: String): List<String> = runCatching {
        context.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList()
    }.getOrNull().orEmpty()

    /** The storage and media permissions [pkg] asks for, as this copy's user sees it. */
    fun requested(context: Context, pkg: String): List<String> = allRequested(context, pkg).filter { it in storagePermissions }

    /**
     * Whether [pkg] asks for All files. Android gives it only to apps that do: for any other app it puts the app-op back
     * at once (PermissionPolicyService; run 21 on the emulator), so Liquid Vault sets nothing there.
     */
    fun asksAllFiles(context: Context, pkg: String): Boolean = Manifest.permission.MANAGE_EXTERNAL_STORAGE in allRequested(context, pkg)

    /** What the shell's script needs: the storage and media permissions, and All files when the app asks for it. */
    fun requestedForShell(context: Context, pkg: String): List<String> =
        requested(context, pkg) + listOfNotNull(Manifest.permission.MANAGE_EXTERNAL_STORAGE.takeIf { asksAllFiles(context, pkg) })

    /** What the app asks for, in words, for its rule's page (any side: each copy answers for its own apps). */
    fun asks(context: Context, pkg: String): Bundle {
        val req = requested(context, pkg)
        val all = asksAllFiles(context, pkg)
        val words = buildList {
            if (req.any { it.startsWith("android.permission.READ_MEDIA_IMAGES") || it.startsWith("android.permission.READ_MEDIA_VIDEO") || it.endsWith("VISUAL_USER_SELECTED") }) add("photos and videos")
            if (Manifest.permission.READ_MEDIA_AUDIO in req) add("music and audio")
            if (Manifest.permission.READ_EXTERNAL_STORAGE in req || Manifest.permission.WRITE_EXTERNAL_STORAGE in req) add("storage (older Android)")
            if (Manifest.permission.ACCESS_MEDIA_LOCATION in req) add("where photos were taken")
            if (all) add("All files")
        }
        return Bundle().apply {
            putBoolean("allFiles", all)
            putString(
                "words",
                if (words.isEmpty()) "It asks for no storage permission: these choices change nothing for it (it can still open files you pick)."
                else "It asks for: ${words.joinToString()}." + if (!all) " Not for All files, so Android lets no one give it that." else "",
            )
        }
    }

    /**
     * Vault copy: sets a Vault app's own files as the owner. "Android decides" hands everything back (policy DEFAULT,
     * All files app-op default); "No files" denies by policy and denies All files; "Photos and media" grants by policy
     * and denies All files; "All files" grants by policy and allows All files. Read back for the answer.
     */
    fun applyInVault(context: Context, pkg: String, own: OwnFiles): Bundle {
        check(Env.isVaultSide) { "only the Vault copy owns the Vault" }
        val lines = mutableListOf<String>()
        val state = when (own) {
            OwnFiles.ANDROID -> DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
            OwnFiles.NONE -> DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
            OwnFiles.MEDIA, OwnFiles.ALL -> DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
        }
        var ok = true
        for (p in requested(context, pkg)) {
            val set = runCatching { Env.dpm.setPermissionGrantState(Env.admin, pkg, p, state) }
            if (set.getOrDefault(false) != true) ok = false
            lines += "${p.substringAfterLast('.')}: ${set.fold({ if (it) "set" else "Android refused" }, { it.asEvidence() })}"
        }
        val mode = when (own) {
            OwnFiles.ALL -> AppOpsManager.MODE_ALLOWED
            OwnFiles.ANDROID -> AppOpsManager.MODE_DEFAULT
            OwnFiles.NONE, OwnFiles.MEDIA -> AppOpsManager.MODE_ERRORED
        }
        val uid = runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
        val asksAll = asksAllFiles(context, pkg)
        if (uid == null) {
            ok = false
            lines += "$pkg is not in the Vault"
        } else if (!asksAll) {
            lines += "All files: it does not ask for it (Android gives it only to apps that ask)"
        } else {
            val set = runCatching { OwnerAppOps.setMode(OwnerAppOps.OP_ALL_FILES, uid, pkg, mode) }
            val now = runCatching { OwnerAppOps.modeName(OwnerAppOps.mode(OwnerAppOps.OP_ALL_FILES, uid, pkg)) }.getOrDefault("?")
            if (set.isFailure || now != OwnerAppOps.modeName(mode)) ok = false
            lines += "All files (app-op): ${set.exceptionOrNull()?.asEvidence() ?: "now $now"}"
        }
        DebugLog.event("Rules", "$pkg in the Vault: own files ${own.label} (${if (ok) "done" else "not all done"}): ${lines.joinToString("; ")}")
        return Bundle().apply {
            putBoolean("ok", ok)
            val label = if (own == OwnFiles.ALL && !asksAll) "Photos and media (it does not ask for All files)" else own.label
            putString("message", if (ok) "$label for its Vault files" else "not all of it was set: ${lines.joinToString("; ")}")
            putStringArrayList("lines", ArrayList(lines))
        }
    }

    /** Vault copy: what a Vault app may do with its own files now, for the rules screen ("android" when nothing is set). */
    fun readInVault(context: Context, pkg: String): String {
        val uid = runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull() ?: return "not in the Vault"
        val allFiles = runCatching { OwnerAppOps.modeName(OwnerAppOps.mode(OwnerAppOps.OP_ALL_FILES, uid, pkg)) }.getOrDefault("?")
        val states = requested(context, pkg).map { Env.dpm.getPermissionGrantState(Env.admin, pkg, it) }.toSet()
        return "All files $allFiles; policy ${states.joinToString { grantStateName(it) }.ifEmpty { "none asked" }}"
    }

    private fun grantStateName(state: Int) = when (state) {
        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED -> "granted"
        DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED -> "denied"
        else -> "default"
    }
}
