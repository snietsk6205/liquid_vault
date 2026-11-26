package dev.liquid.vault.core.common

/** Rules for copying an app into the Vault, unit-tested (D55). */
object CopyRules {
    /**
     * What is wrong with the APK files the personal copy named for a copy, or null when they look right: at least one,
     * each an absolute path to an `.apk` file, no `..` in it, no file twice.
     */
    fun apkPathsProblem(paths: List<String>): String? = when {
        paths.isEmpty() -> "no APK file was named"
        paths.any { !it.startsWith("/") } -> "an APK path is not absolute: ${paths.first { !it.startsWith("/") }}"
        paths.any { !it.endsWith(".apk") } -> "not an APK file: ${paths.first { !it.endsWith(".apk") }}"
        paths.any { "/../" in it || it.endsWith("/..") } -> "an APK path leaves its folder: ${paths.first { "/../" in it }}"
        paths.toSet().size != paths.size -> "an APK file is named twice"
        else -> null
    }
}
