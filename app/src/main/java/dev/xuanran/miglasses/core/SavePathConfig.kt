package dev.xuanran.miglasses.core

object SavePathConfig {
    const val GROUP = "config"
    const val KEY_PATH = "save_relative_path"
    const val DEFAULT_PATH = "DCIM/MiGlasses"

    fun current(): String = normalize(
        runCatching { HookHost.module?.getRemotePreferences(GROUP)?.getString(KEY_PATH, null) }
            .getOrNull()
    ) ?: DEFAULT_PATH

    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.replace('\\', '/')?.trim('/') ?: return null
        if (value.isBlank() || value.startsWith('/') || value.split('/').any { it == ".." }) return null
        val root = value.substringBefore('/').lowercase()
        if (root !in setOf("dcim", "pictures", "movies")) return null
        return value
    }
}
