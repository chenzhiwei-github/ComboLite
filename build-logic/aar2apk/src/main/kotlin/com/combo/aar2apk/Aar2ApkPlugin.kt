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
package com.combo.aar2apk

import com.combo.aar2apk.internal.model.SdkInfo
import com.combo.aar2apk.internal.utils.DependencyPatternMatcher
import com.combo.aar2apk.internal.utils.DependencySplit
import com.combo.aar2apk.internal.utils.SdkLocator
import com.combo.aar2apk.tasks.ConvertAarToApkTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.tasks.Delete
import org.gradle.kotlin.dsl.register
import java.io.File

class Aar2ApkPlugin : Plugin<Project> {

    companion object {
        private const val GROUP_MAIN = "Plugin APKs"
        private const val GROUP_DEBUG_BUILDS = "Plugin APKs - Debug Builds"
        private const val GROUP_RELEASE_BUILDS = "Plugin APKs - Release Builds"
    }

    override fun apply(project: Project) {
        if (project == project.rootProject) {
            applyToRootProject(project)
        } else {
            project.plugins.withId("com.android.application") {
                project.plugins.apply(AppIntegrationPlugin::class.java)
            }
        }
    }

    /**
     * 插件被应用到根项目时的逻辑
     */
    private fun applyToRootProject(project: Project) {
        val extension = project.extensions.create("aar2apk", Aar2ApkExtension::class.java)

        project.afterEvaluate {
            if (extension.moduleConfigs.modules.isNotEmpty()) {
                extension.moduleConfigs.modules.forEach { config ->
                    project.evaluationDependsOn(config.path)
                }
                configurePluginBuildTasks(project, extension)
            } else {
                project.logger.info("Aar2ApkPlugin: 未在 aar2apk.modules 中配置任何模块，跳过任务创建。")
            }
        }
    }

    /**
     * 在根项目中配置所有插件构建任务
     */
    private fun configurePluginBuildTasks(project: Project, extension: Aar2ApkExtension) {
        val sdkPath = SdkLocator.getSdkPath(project)
        val buildToolsVersion = SdkLocator.findLatestBuildTools(sdkPath)
        val platformVersion = SdkLocator.findLatestPlatform(sdkPath)
        val sdkInfo = SdkInfo(sdkPath, buildToolsVersion, platformVersion)

        val pluginPackageIds = extension.moduleConfigs.modules.mapIndexed { index, config ->
            config.path to String.format("0x%02x", 0x80 + index)
        }.toMap()

        val debugTaskNames = mutableListOf<String>()
        val releaseTaskNames = mutableListOf<String>()

        extension.moduleConfigs.modules.forEach { options ->
            val modulePath = options.path
            val subproject = project.project(modulePath)
            val baseTaskName = modulePath.replace(":", "_").removePrefix("_")

            listOf("debug", "release").forEach { buildType ->
                val buildTypeCapitalized = buildType.replaceFirstChar { it.uppercase() }
                val taskName = "convert_${baseTaskName}_${buildType}"
                val assembleTaskName = "assemble$buildTypeCapitalized"
                val taskGroup =
                    if (buildType == "debug") GROUP_DEBUG_BUILDS else GROUP_RELEASE_BUILDS

                project.tasks.register<ConvertAarToApkTask>(taskName) {
                    group = taskGroup
                    dependsOn(subproject.tasks.named(assembleTaskName))

                    val aarFileName = "${subproject.name}-$buildType.aar"
                    aarFile.set(subproject.layout.buildDirectory.file("outputs/aar/$aarFileName"))
                    pluginName.set(subproject.name)
                    this.signingConfig.set(extension.signingConfig)
                    this.sdkInfo.set(sdkInfo)
                    this.buildType.set(buildType)
                    this.packageId.set(pluginPackageIds[modulePath]!!)
                    this.packagingOptions.set(options)
                    this.minify.set(options.minifyRelease.map { it && buildType == "release" })
                    this.minApi.set(options.minApi)
                    this.minifyProguardFiles.from(options.proguardFiles)
                    this.minifyClasspathFiles.from(options.classpathFiles)

                    description =
                        "构建 ${subproject.name} 模块并转换为 $buildTypeCapitalized 插件APK (精细化配置)"

                    val config = subproject.configurations.getByName("${buildType}RuntimeClasspath")

                    this.extraProgramJars.from(options.extraProgramJars)
                    this.extraRPackages.set(options.extraRPackages)
                    this.hostProvidedClassPrefixes.set(options.hostProvidedClassPrefixes)

                    if (options.isAnyDependencyIncluded()) {
                        val remoteArtifacts = config.incoming.artifactView {
                            componentFilter { it is ModuleComponentIdentifier }
                            lenient(true)
                        }.artifacts.resolvedArtifacts
                        val remoteSplit =
                            remoteArtifacts.zip(options.hostProvidedPatterns, ::splitArtifacts)
                        remoteProgramArtifacts.from(remoteSplit.map { it.programFiles })
                        hostProvidedClasspath.from(remoteSplit.map { it.hostProvidedFiles })
                        programDependencyLines.addAll(remoteSplit.map { it.programLines })
                        hostProvidedDependencyLines.addAll(remoteSplit.map { it.hostProvidedLines })
                    }
                    if (options.includeDependenciesRes.get()) {
                        localDependencyResDirs.from(config.incoming.artifactView {
                            attributes {
                                attribute(
                                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                                    "android-res"
                                )
                            }
                            componentFilter { it is org.gradle.api.artifacts.component.ProjectComponentIdentifier }
                            lenient(true)
                        }.files)
                    }
                    if (options.includeDependenciesDex.get()) {
                        val localClassesArtifacts = config.incoming.artifactView {
                            attributes {
                                attribute(
                                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                                    "android-classes-jar"
                                )
                            }
                            componentFilter { it is ProjectComponentIdentifier }
                            lenient(true)
                        }.artifacts.resolvedArtifacts
                        val localSplit =
                            localClassesArtifacts.zip(options.hostProvidedPatterns, ::splitArtifacts)
                        localDependencyClasses.from(localSplit.map { it.programFiles })
                        hostProvidedClasspath.from(localSplit.map { it.hostProvidedFiles })
                        programDependencyLines.addAll(localSplit.map { it.programLines })
                        hostProvidedDependencyLines.addAll(localSplit.map { it.hostProvidedLines })
                    }
                    if (options.includeDependenciesAssets.get()) {
                        localDependencyAssets.from(config.incoming.artifactView {
                            attributes {
                                attribute(
                                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                                    "android-assets"
                                )
                            }
                            componentFilter { it is org.gradle.api.artifacts.component.ProjectComponentIdentifier }
                            lenient(true)
                        }.files)
                    }
                    if (options.includeDependenciesJni.get()) {
                        localDependencyJniLibs.from(config.incoming.artifactView {
                            attributes {
                                attribute(
                                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                                    "android-jni"
                                )
                            }
                            componentFilter { it is org.gradle.api.artifacts.component.ProjectComponentIdentifier }
                            lenient(true)
                        }.files)
                    }
                    outputDirectory.set(project.layout.buildDirectory.dir("outputs/plugin-apks/$buildType"))
                }
                if (buildType == "debug") {
                    debugTaskNames.add(taskName)
                } else {
                    releaseTaskNames.add(taskName)
                }
            }
        }

        project.tasks.register("buildAllDebugPluginApks") {
            group = GROUP_MAIN
            description = "一键构建所有已配置模块的Debug插件APK"
            dependsOn(debugTaskNames)
            outputs.upToDateWhen { false }
        }
        project.tasks.register("buildAllReleasePluginApks") {
            group = GROUP_MAIN
            description = "一键构建所有已配置模块的Release插件APK"
            dependsOn(releaseTaskNames)
            outputs.upToDateWhen { false }
        }

        project.tasks.register<Delete>("cleanAllPluginApks") {
            group = GROUP_MAIN
            description = "清理所有生成的插件APK、相关日志和临时工作目录"
            delete(
                project.layout.buildDirectory.dir("outputs/plugin-apks"),
                project.layout.buildDirectory.dir("logs/aar2apk")
            )
            doLast {
                val tmpDir = project.layout.buildDirectory.get().asFile.resolve("tmp")
                if (tmpDir.exists() && tmpDir.isDirectory) {
                    tmpDir.listFiles()
                        ?.filter { it.isDirectory && it.name.startsWith("convert_") }
                        ?.forEach {
                            project.delete(it)
                            project.logger.lifecycle("Deleted temporary directory: ${it.path}")
                        }
                }
            }
        }
    }
}

private fun splitArtifacts(
    artifacts: Set<ResolvedArtifactResult>,
    patterns: List<String>,
): DependencySplit {
    val matcher = DependencyPatternMatcher(patterns)
    val programFiles = mutableListOf<File>()
    val hostProvidedFiles = mutableListOf<File>()
    val programLines = mutableListOf<String>()
    val hostProvidedLines = mutableListOf<String>()
    artifacts.sortedBy { it.file.absolutePath }.forEach { artifact ->
        val id = artifact.id.componentIdentifier
        val matched: Boolean
        val line: String
        when (id) {
            is ModuleComponentIdentifier -> {
                matched = matcher.matchesModule(id.group, id.module)
                line = "${id.group}:${id.module}:${id.version} -> ${artifact.file.absolutePath}"
            }

            is ProjectComponentIdentifier -> {
                matched = matcher.matchesProject(id.projectPath)
                line = "project ${id.projectPath} -> ${artifact.file.absolutePath}"
            }

            else -> {
                matched = false
                line = "${id.displayName} -> ${artifact.file.absolutePath}"
            }
        }
        if (matched) {
            hostProvidedFiles.add(artifact.file)
            hostProvidedLines.add(line)
        } else {
            programFiles.add(artifact.file)
            programLines.add(line)
        }
    }
    return DependencySplit(programFiles, hostProvidedFiles, programLines, hostProvidedLines)
}