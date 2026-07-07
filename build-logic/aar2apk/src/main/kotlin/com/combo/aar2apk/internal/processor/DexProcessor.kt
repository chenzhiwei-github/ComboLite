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

package com.combo.aar2apk.internal.processor

import com.combo.aar2apk.internal.model.SdkInfo
import com.combo.aar2apk.internal.utils.ShellExecutor
import com.combo.aar2apk.internal.utils.TaskLogger
import java.io.File

/**
 * 负责编译Java/Kotlin代码并转换为DEX (javac, d8)
 */
internal class DexProcessor(
    private val shellExecutor: ShellExecutor,
    private val sdkInfo: SdkInfo,
    private val logger: TaskLogger
) {
    /**
     * @param allJarFiles 包含主模块和所有依赖库的 .jar 文件集合
     * @param rJavaSourcesDir aapt2 link 生成的 R.java 源码目录
     * @return 生成的全部 classes*.dex 文件
     */
    fun process(
        allJarFiles: Set<File>,
        rJavaSourcesDir: File?,
        buildType: String,
        workDir: File,
        minify: Boolean = false,
        minApi: Int = 21,
        proguardFiles: Collection<File> = emptyList(),
        classpathFiles: Collection<File> = emptyList(),
        mappingOutput: File? = null,
    ): List<File> {
        logger.log("步骤4: 编译Java/Kotlin代码并转换为DEX")
        val buildDir = File(workDir, "build")
        val rJavaFiles =
            rJavaSourcesDir?.walk()?.filter { it.isFile && it.name.endsWith(".java") }?.toList()
                ?: emptyList()

        // 如果没有任何代码，则跳过
        if (allJarFiles.isEmpty() && rJavaFiles.isEmpty()) {
            logger.log("⚠️ 未找到任何JAR或R.java文件，跳过DEX转换。")
            return emptyList()
        }

        // 编译 R.java (如果存在)
        val rClassesJar = if (rJavaFiles.isNotEmpty()) {
            compileRJava(rJavaSourcesDir!!, buildDir)
        } else null

        // 将编译后的 R.jar 和其他所有 jar 文件合并，一起转换为 DEX
        val jarsToDex = allJarFiles + listOfNotNull(rClassesJar)
        return if (minify && buildType == "release") {
            minifyToDex(jarsToDex, minApi, proguardFiles, classpathFiles, mappingOutput, buildDir)
        } else {
            convertToDex(jarsToDex, buildType, minApi, buildDir)
        }
    }

    private fun minifyToDex(
        jarFiles: Collection<File>,
        minApi: Int,
        proguardFiles: Collection<File>,
        classpathFiles: Collection<File>,
        mappingOutput: File?,
        buildDir: File
    ): List<File> {
        logger.log("  使用 R8 对 ${jarFiles.size} 个 JAR 进行混淆并转换为 DEX...")
        val dexOutputDir = File(buildDir, "dex_output")
        dexOutputDir.deleteRecursively()
        dexOutputDir.mkdirs()

        val d8Jar = File(sdkInfo.sdkPath, "build-tools/${sdkInfo.buildToolsVersion}/lib/d8.jar")
        if (!d8Jar.isFile) throw IllegalStateException("R8 所需的 d8.jar 不存在: ${d8Jar.absolutePath}")

        val classpathJars = classpathFiles.flatMap { resolveClasspathJars(it, buildDir) }
        val mapping = mappingOutput ?: File(buildDir, "r8-mapping.txt")
        mapping.parentFile?.mkdirs()

        val command = mutableListOf(
            "java", "-Xmx4g",
            "-cp", d8Jar.absolutePath,
            "com.android.tools.r8.R8",
            "--release",
            "--pg-compat",
            "--min-api", minApi.toString(),
            "--lib", sdkInfo.androidJar.absolutePath,
            "--output", dexOutputDir.absolutePath,
            "--pg-map-output", mapping.absolutePath,
        )
        classpathJars.forEach {
            command.add("--classpath")
            command.add(it.absolutePath)
        }
        proguardFiles.filter { it.isFile }.forEach {
            command.add("--pg-conf")
            command.add(it.absolutePath)
        }
        jarFiles.forEach { command.add(it.absolutePath) }
        shellExecutor.execute(command)

        val dexFiles = collectDexFiles(dexOutputDir)
        if (dexFiles.isEmpty()) throw IllegalStateException("R8 混淆失败，未生成任何dex文件。")
        logger.log("  R8 混淆完成，生成 ${dexFiles.size} 个dex，mapping: ${mapping.absolutePath}")
        return dexFiles
    }

    private fun collectDexFiles(dexOutputDir: File): List<File> {
        val dexNameRegex = Regex("classes\\d*\\.dex")
        return dexOutputDir.listFiles { file -> dexNameRegex.matches(file.name) }
            ?.sortedBy { file ->
                file.name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 1
            }
            ?: emptyList()
    }

    private fun resolveClasspathJars(file: File, buildDir: File): List<File> {
        if (!file.exists()) return emptyList()
        if (file.extension != "aar") return listOf(file)
        val outDir = File(buildDir, "classpath_aars/${file.nameWithoutExtension}")
        outDir.deleteRecursively()
        outDir.mkdirs()
        val jars = mutableListOf<File>()
        java.util.zip.ZipFile(file).use { zip ->
            for (entry in zip.entries()) {
                val isClassesJar = entry.name == "classes.jar"
                val isLibJar = entry.name.startsWith("libs/") && entry.name.endsWith(".jar")
                if (!isClassesJar && !isLibJar) continue
                val target = File(outDir, entry.name.replace('/', '_'))
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                jars.add(target)
            }
        }
        return jars
    }

    private fun compileRJava(sourceDir: File, buildDir: File): File {
        logger.log("  编译R.java文件...")
        val classesDir = File(buildDir, "r_classes")
        val rClassesJar = File(buildDir, "r_classes.jar")
        classesDir.deleteRecursively()
        classesDir.mkdirs()

        val javaFilePaths = sourceDir.walk()
            .filter { it.isFile && it.name.endsWith(".java") }
            .map { it.absolutePath }
            .toList()

        // 使用 javac 编译
        shellExecutor.execute(
            listOf(
                "javac", "-cp", sdkInfo.androidJar.absolutePath,
                "-d", classesDir.absolutePath,
                *javaFilePaths.toTypedArray()
            ),
            buildDir
        )

        // 将编译后的 .class 文件打成 jar 包
        shellExecutor.execute(
            listOf("jar", "cf", rClassesJar.absolutePath, "-C", classesDir.absolutePath, "."),
            buildDir
        )
        return rClassesJar
    }

    private fun convertToDex(
        jarFiles: Collection<File>,
        buildType: String,
        minApi: Int,
        buildDir: File
    ): List<File> {
        logger.log("  使用 D8 将 ${jarFiles.size} 个 JAR 文件转换为 DEX...")
        val dexOutputDir = File(buildDir, "dex_output")
        dexOutputDir.deleteRecursively()
        dexOutputDir.mkdirs()

        val command = mutableListOf(
            sdkInfo.getTool("d8"),
            "--min-api", minApi.toString(),
            "--output", dexOutputDir.absolutePath
        )
        if (buildType == "release") {
            command.add("--release")
        }
        jarFiles.forEach { command.add(it.absolutePath) }
        shellExecutor.execute(command)

        val dexFiles = collectDexFiles(dexOutputDir)
        if (dexFiles.isEmpty()) throw IllegalStateException("DEX转换失败，未生成任何dex文件。")
        return dexFiles
    }
}