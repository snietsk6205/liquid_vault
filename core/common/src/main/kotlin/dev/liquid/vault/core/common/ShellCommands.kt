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

    /** Step 3: our admin receiver becomes the profile owner of the new profile. */
    fun setProfileOwner(userId: Int, component: String): List<String> {
        require(componentPattern.matches(component)) { "not a component: $component" }
        return listOf("dpm", "set-profile-owner", "--user", user(userId), "--name", "Liquid Vault", component)
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

    /** `id` prints "uid=2000(shell) gid=…": the uid number. */
    fun parseUid(output: String): Int? = Regex("uid=(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()
}
