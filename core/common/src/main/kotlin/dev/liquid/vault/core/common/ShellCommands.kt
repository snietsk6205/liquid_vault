package dev.liquid.vault.core.common

/**
 * The shell commands Liquid Vault runs through Shevery (Shizuku API), built as argument arrays (no `sh -c`, so nothing
 * needs quoting) from validated values, and the parsers that read their output. Kept free of Android classes so every
 * builder and parser is unit-tested on the JVM.
 *
 * The Vault creation steps follow Insular's manual setup (secure-system.gitlab.io/Insular/setup.html, Apache-2.0).
 */
object ShellCommands {
    /** The Vault's name as Android shows it (Settings › Users, the Work tab). */
    const val VAULT_NAME = "Vault"

    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val componentPattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+$")
    private val opPattern = Regex("^[A-Z][A-Z0-9_]*$")

    fun isPackageName(value: String): Boolean = packagePattern.matches(value)

    private fun pkg(value: String): String {
        require(isPackageName(value)) { "not a package name: $value" }
        return value
    }

    private fun user(id: Int): String {
        require(id >= 0) { "not a user id: $id" }
        return id.toString()
    }

    fun id(): List<String> = listOf("id")

    fun getprop(name: String): List<String> {
        require(Regex("^[A-Za-z0-9._-]+$").matches(name)) { "not a property name: $name" }
        return listOf("getprop", name)
    }

    fun listUsers(): List<String> = listOf("pm", "list", "users")

    /** Step 1 of the shell path: a managed profile of the main user (0), named [VAULT_NAME]. */
    fun createVault(): List<String> = listOf("pm", "create-user", "--profileOf", "0", "--managed", VAULT_NAME)

    /** Step 2: our app (already installed for user 0) made available in the new profile. Also clones an app. */
    fun installExisting(userId: Int, packageName: String): List<String> =
        listOf("pm", "install-existing", "--user", user(userId), pkg(packageName))

    /**
     * Step 3: our admin receiver becomes the profile owner of the new profile. No `--name`: Android 14's dpm rejects it
     * ("Unknown option: --name", emulator test run 4); the Vault's own setup sets the organisation name instead.
     */
    fun setProfileOwner(userId: Int, component: String): List<String> {
        require(componentPattern.matches(component)) { "not a component: $component" }
        return listOf("dpm", "set-profile-owner", "--user", user(userId), component)
    }

    /** Step 4: the profile starts (and `-w` waits until it has). */
    fun startUser(userId: Int): List<String> = listOf("am", "start-user", "-w", user(userId))

    /** Step 5: the Vault copy of the app opens once, so it finishes its setup with a screen in front. */
    fun startVaultActivity(userId: Int, component: String, action: String): List<String> {
        require(componentPattern.matches(component)) { "not a component: $component" }
        require(Regex("^[A-Za-z0-9_.]+$").matches(action)) { "not an action: $action" }
        return listOf("am", "start", "--user", user(userId), "-a", action, "-n", component)
    }

    /** Removes the Vault (destroy fallback, and undo of a half-made Vault). Only after a typed confirmation. */
    fun removeUser(userId: Int): List<String> {
        require(userId > 0) { "user 0 is the phone's main user and is never removed" }
        return listOf("pm", "remove-user", user(userId))
    }

    fun appOpsSet(userId: Int, packageName: String, op: String, mode: String): List<String> {
        require(opPattern.matches(op)) { "not an app-op: $op" }
        require(mode in setOf("allow", "deny", "ignore", "default", "foreground")) { "not an app-op mode: $mode" }
        return listOf("appops", "set", "--user", user(userId), pkg(packageName), op, mode)
    }

    fun appOpsGet(userId: Int, packageName: String, op: String): List<String> {
        require(opPattern.matches(op)) { "not an app-op: $op" }
        return listOf("appops", "get", "--user", user(userId), pkg(packageName), op)
    }

    fun listPackages(userId: Int, packageName: String): List<String> =
        listOf("pm", "list", "packages", "--user", user(userId), pkg(packageName))

    /** Every user's facts, restrictions included (the shell may read it even when a profile blocks the shell). */
    fun dumpsysUser(): List<String> = listOf("dumpsys", "user")

    // Phase 1: what the shell does to apps (D45). `pm` prints "Package <pkg> new state: disabled-user" and
    // "Package <pkg> new suspended state: true" (AOSP PackageManagerShellCommand). The shell cannot `pm hide`: Android
    // asks for MANAGE_USERS there, which the shell does not hold (CI run 15), so a personal app is frozen by turning
    // it off for the user instead, which the shell may do to whole apps (CHANGE_COMPONENT_ENABLED_STATE).

    /** Freeze: the app is turned off for the user (gone from launchers, cannot run, its data kept). */
    fun disableUser(userId: Int, packageName: String): List<String> =
        listOf("pm", "disable-user", "--user", user(userId), pkg(packageName))

    /** Unfreeze: the app is turned on again. */
    fun enable(userId: Int, packageName: String): List<String> = listOf("pm", "enable", "--user", user(userId), pkg(packageName))

    /** Suspend: the icon stays, greyed; the app cannot open. */
    fun suspend(userId: Int, packageName: String): List<String> = listOf("pm", "suspend", "--user", user(userId), pkg(packageName))

    fun unsuspend(userId: Int, packageName: String): List<String> = listOf("pm", "unsuspend", "--user", user(userId), pkg(packageName))

    /** Removes an app (and its data) from one user only: the Vault's copy of an app. Only after a typed confirmation. */
    fun uninstallForUser(userId: Int, packageName: String): List<String> {
        require(userId > 0) { "apps are removed from the Vault only, never from the main user" }
        return listOf("pm", "uninstall", "--user", user(userId), pkg(packageName))
    }

    /** The state `pm enable` / `pm disable-user` reports: "enabled", "default", "disabled-user", … */
    fun parseEnabledState(output: String, packageName: String): String? =
        Regex("Package ${Regex.escape(packageName)} new state: ([a-z-]+)").find(output)?.groupValues?.get(1)

    fun parseSuspended(output: String, packageName: String): Boolean? =
        Regex("Package ${Regex.escape(packageName)} new suspended state: (true|false)").find(output)?.groupValues?.get(1)?.toBooleanStrict()

    fun uninstallSucceeded(output: String): Boolean = output.lineSequence().any { it.trim() == "Success" }

    /**
     * Phase 2 (D57): a personal app's own files, set by the shell as one script. All files by app-op (allow for "All
     * files", default for "Android decides", deny otherwise), then each storage or media permission the app asks for
     * granted ("Photos and media", "All files") or revoked ("No files"); Android refuses some for some apps, so each
     * line says how it went. "ok all-files" means the app-op was set, which is what makes the action done.
     */
    fun ownFiles(userId: Int, packageName: String, own: OwnFiles, requested: List<String>): List<String> {
        val u = user(userId)
        val p = pkg(packageName)
        val mode = when (own) {
            OwnFiles.ALL -> "allow"
            OwnFiles.ANDROID -> "default"
            OwnFiles.NONE, OwnFiles.MEDIA -> "deny"
        }
        val lines = mutableListOf("appops set --user $u $p MANAGE_EXTERNAL_STORAGE $mode && echo 'ok all-files'")
        val verb = when (own) {
            OwnFiles.MEDIA, OwnFiles.ALL -> "grant"
            OwnFiles.NONE -> "revoke"
            OwnFiles.ANDROID -> null
        }
        if (verb != null) {
            for (perm in requested) {
                require(perm.matches(Regex("^[A-Za-z0-9_.]+$"))) { "not a permission name: $perm" }
                lines += "pm $verb --user $u $p $perm >/dev/null 2>&1 && echo 'ok $perm' || echo 'no $perm'"
            }
        }
        return listOf("sh", "-c", lines.joinToString("; "))
    }

    fun ownFilesDone(output: String): Boolean = output.lineSequence().any { it.trim() == "ok all-files" }

    /**
     * The part of a failed `pm` answer worth showing: its first line plus the exception's own message when there is
     * one ("Exception occurred while executing 'hide': java.lang.SecurityException: Neither user 2000 nor current
     * process has android.permission.MANAGE_USERS."), never the stack trace. Null when the shell said nothing.
     */
    fun errorSummary(output: String, max: Int = 240): String? {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("at ") }.toList()
        if (lines.isEmpty()) return null
        val reason = lines.firstOrNull { Regex("^[\\w.$]+(Exception|Error)(: |$)").containsMatchIn(it) }
        val text = when {
            reason == null -> lines.take(2).joinToString(" ")
            reason == lines.first() -> reason
            else -> lines.first() + " " + reason
        }
        return text.take(max)
    }

    /** For the log: the command as it would be typed, quoting what needs it. */
    fun render(command: List<String>): String = command.joinToString(" ") { arg ->
        if (arg.isEmpty() || arg.any { it.isWhitespace() || it in "'\"$`\\;&|<>()*?![]{}" }) "'" + arg.replace("'", "'\\''") + "'" else arg
    }

    // ---- parsers ----

    /** `pm create-user` prints "Success: created user id 11". */
    fun parseCreatedUserId(output: String): Int? =
        Regex("created user id (\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()

    /** `pm install-existing` prints "Package <pkg> installed for user: 11". */
    fun installExistingSucceeded(output: String, packageName: String): Boolean =
        output.contains("Package $packageName installed for user") && !output.contains("Failure")

    /** `dpm set-profile-owner` prints "Success: Active admin and profile owner set to …". */
    fun setProfileOwnerSucceeded(output: String): Boolean = output.contains("Success")

    /** `am start-user` prints "Success: user started". */
    fun startUserSucceeded(output: String): Boolean = output.contains("Success")

    fun removeUserSucceeded(output: String): Boolean = output.contains("Success")

    /** One line of `pm list users`: `UserInfo{11:Vault:1030} running`. */
    data class UserLine(val id: Int, val name: String, val flags: Int, val running: Boolean) {
        /** UserInfo.FLAG_MANAGED_PROFILE. */
        val managedProfile: Boolean get() = flags and 0x20 != 0
        /** UserInfo.FLAG_QUIET_MODE (the profile is paused). */
        val quietMode: Boolean get() = flags and 0x80 != 0
    }

    fun parseUsers(output: String): List<UserLine> =
        Regex("UserInfo\\{(\\d+):([^:}]*):([0-9a-fA-F]+)\\}( running)?").findAll(output).map { m ->
            UserLine(
                id = m.groupValues[1].toInt(),
                name = m.groupValues[2],
                flags = m.groupValues[3].toLong(16).toInt(),
                running = m.groupValues[4].isNotEmpty(),
            )
        }.toList()

    /** `appops get … OP` prints "OP: deny; time=…" (or "OP: allow", or "No operations."). The mode, or null. */
    fun parseAppOpMode(output: String, op: String): String? {
        val line = output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$op:") } ?: return null
        return line.removePrefix("$op:").trim().substringBefore(';').trim().ifEmpty { null }
    }

    /**
     * After "No operations." `appops get` prints "Default mode: allow": the mode the op has without an entry. Setting an
     * op to its default mode removes the entry (AOSP `AppOpsService.setMode` prunes it), so "allow" reads back as
     * "No operations." for the storage and media ops (emulator run 8). The default mode, or null.
     */
    fun parseAppOpDefault(output: String): String? =
        output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Default mode:") }
            ?.removePrefix("Default mode:")?.trim()?.ifEmpty { null }

    /** `id` prints "uid=2000(shell) gid=…": the uid number. */
    fun parseUid(output: String): Int? = Regex("uid=(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Android refuses every shell command that names a user with the restriction "no debugging features" with this
     * sentence (AOSP 15 `UserController.handleIncomingUser`; phone test 0.1.0, check C2).
     */
    fun shellBlockedFromUser(output: String): Boolean = output.contains("Shell does not have permission to access user")

    /** One op as `appops get --user N <pkg> <OP>` prints it: the uid's mode, the package's mode, or only a default. */
    data class AppOpRead(val uidMode: String?, val packageMode: String?, val defaultMode: String?) {
        /** The mode Android applies: a uid mode that is not the op's default wins, then the package's, then the default. */
        fun effective(opDefault: String): String? = when {
            uidMode != null && uidMode != opDefault -> uidMode
            packageMode != null -> packageMode
            defaultMode != null -> defaultMode
            else -> uidMode
        }
    }

    /** Reads `appops get` output: "Uid mode: OP: allow", "OP: deny; time=…", or "No operations." + "Default mode: …". */
    fun parseAppOpRead(output: String, op: String): AppOpRead {
        val lines = output.lineSequence().map { it.trim() }.toList()
        fun mode(text: String) = text.substringBefore(';').trim().ifEmpty { null }
        val uid = lines.firstOrNull { it.startsWith("Uid mode: $op:") }?.removePrefix("Uid mode: $op:")?.let(::mode)
        val pkg = lines.firstOrNull { it.startsWith("$op:") }?.removePrefix("$op:")?.let(::mode)
        return AppOpRead(uid, pkg, parseAppOpDefault(output))
    }

    /** A user's restrictions as `dumpsys user` prints them (AOSP 14/15: three lists under the user's block). */
    data class UserRestrictions(
        /** "Restrictions:": the profile's own (base) restrictions, set when it was made or by the system. */
        val base: List<String>,
        /** "Device policy restrictions:" (Android 14+) or its global/local halves (older): set by device policy. */
        val devicePolicy: List<String>,
        /** "Effective restrictions:": what is in force. Empty when this Android does not print it. */
        val effective: List<String>,
    )

    /** Where a restriction in force on a profile comes from. */
    enum class RestrictionSource { NOT_SET, PROFILE_ITSELF, DEVICE_POLICY, BOTH }

    fun sourceOf(r: UserRestrictions, key: String): RestrictionSource {
        val base = key in r.base
        val policy = key in r.devicePolicy
        return when {
            base && policy -> RestrictionSource.BOTH
            base -> RestrictionSource.PROFILE_ITSELF
            policy -> RestrictionSource.DEVICE_POLICY
            else -> RestrictionSource.NOT_SET
        }
    }

    /**
     * Finds user [userId]'s block in `dumpsys user` output (from its "UserInfo{N:" line to the next user's) and reads
     * the restriction lists in it. Null when the user's block is not in the output.
     */
    fun parseUserRestrictions(dumpsysUser: String, userId: Int): UserRestrictions? {
        val lines = dumpsysUser.lines()
        val start = lines.indexOfFirst { it.contains("UserInfo{$userId:") }
        if (start < 0) return null
        val end = (start + 1 until lines.size).firstOrNull { i ->
            lines[i].contains("UserInfo{") || lines[i].trim() == "Device properties:"
        } ?: lines.size
        val sections = linkedMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        val header = Regex("^(.*[Rr]estrictions):$")
        val item = Regex("^[a-z0-9_]+$")
        for (raw in lines.subList(start + 1, end)) {
            val line = raw.trim()
            val h = header.find(line)
            if (h != null) {
                current = sections.getOrPut(h.groupValues[1]) { mutableListOf() }
                continue
            }
            if (current != null && item.matches(line)) {
                if (line != "none" && line != "null") current.add(line)
                continue
            }
            current = null
        }
        return UserRestrictions(
            base = sections["Restrictions"].orEmpty(),
            devicePolicy = sections.filterKeys { it.startsWith("Device policy") }.values.flatten().distinct(),
            effective = sections["Effective restrictions"].orEmpty(),
        )
    }

    /** Android's name for the restriction behind C2 (UserManager.DISALLOW_DEBUGGING_FEATURES). */
    const val NO_DEBUGGING_FEATURES = "no_debugging_features"
}
