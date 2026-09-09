package com.combo.core.runtime.loader

sealed class PluginClassLoadingPolicy {
    object ParentFirst : PluginClassLoadingPolicy()

    data class ChildFirst(
        val forceParentPrefixes: List<String> = DEFAULT_PARENT_PREFIXES,
        val forceParentClasses: Set<String> = emptySet(),
        val childFirstPrefixes: List<String> = emptyList(),
    ) : PluginClassLoadingPolicy() {
        fun isChildFirst(name: String): Boolean =
            name !in forceParentClasses && forceParentPrefixes.none(name::startsWith) &&
                (childFirstPrefixes.isEmpty() || childFirstPrefixes.any(name::startsWith))
    }

    companion object {
        val DEFAULT_PARENT_PREFIXES = listOf(
            "java.", "javax.", "android.", "androidx.", "dalvik.",
            "kotlin.", "kotlinx.", "com.combo.",
        )
    }
}
