/*
 * Copyright (c) 2025, 贵州君城网络科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.combo.aar2apk.internal.utils

import java.io.File
import java.io.Serializable

internal class DependencyPatternMatcher(patterns: List<String>) : Serializable {

    private val projectPaths: Set<String> = patterns.filter { it.startsWith(":") }.toSet()

    private val modulePatterns: List<String> = patterns.filterNot { it.startsWith(":") }

    @Transient
    private var moduleRegexes: List<Regex>? = null

    private fun regexes(): List<Regex> {
        val cached = moduleRegexes
        if (cached != null) return cached
        val compiled = modulePatterns.map { pattern ->
            val normalized = if (pattern.contains(":")) pattern else "$pattern:*"
            val regexText = normalized.split("*").joinToString(".*") { Regex.escape(it) }
            Regex(regexText)
        }
        moduleRegexes = compiled
        return compiled
    }

    fun matchesProject(projectPath: String): Boolean = projectPath in projectPaths

    fun matchesModule(group: String, module: String): Boolean {
        val candidate = "$group:$module"
        return regexes().any { it.matches(candidate) }
    }
}

internal class DependencySplit(
    val programFiles: List<File>,
    val hostProvidedFiles: List<File>,
    val programLines: List<String>,
    val hostProvidedLines: List<String>,
) : Serializable
