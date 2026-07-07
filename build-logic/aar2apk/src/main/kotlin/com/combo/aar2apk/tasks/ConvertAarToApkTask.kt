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

package com.combo.aar2apk.tasks

import com.combo.aar2apk.PackagingOptions
import com.combo.aar2apk.SigningConfig
import com.combo.aar2apk.internal.model.SdkInfo
import com.combo.aar2apk.internal.processor.AarExtractor
import com.combo.aar2apk.internal.processor.ApkPackager
import com.combo.aar2apk.internal.processor.ApkSigner
import com.combo.aar2apk.internal.processor.DexProcessor
import com.combo.aar2apk.internal.processor.ResourceProcessor
import com.combo.aar2apk.internal.utils.DexClassNamesReader
import com.combo.aar2apk.internal.utils.ShellExecutor
import com.combo.aar2apk.internal.utils.TaskLogger
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

@CacheableTask
abstract class ConvertAarToApkTask @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {
    // --- 输入属性 ---
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val aarFile: RegularFileProperty

    @get:Input
    abstract val pluginName: Property<String>

    @get:Nested
    abstract val signingConfig: Property<SigningConfig>

    @get:Nested
    abstract val sdkInfo: Property<SdkInfo>

    @get:Input
    abstract val buildType: Property<String>

    @get:Input
    abstract val packageId: Property<String>

    @get:Nested
    abstract val packagingOptions: Property<PackagingOptions>

    @get:Input
    abstract val minify: Property<Boolean>

    @get:Input
    abstract val minApi: Property<Int>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val minifyProguardFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val minifyClasspathFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val remoteProgramArtifacts: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val hostProvidedClasspath: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val extraProgramJars: ConfigurableFileCollection

    @get:Input
    abstract val extraRPackages: ListProperty<String>

    @get:Input
    abstract val hostProvidedClassPrefixes: ListProperty<String>

    @get:Input
    abstract val programDependencyLines: ListProperty<String>

    @get:Input
    abstract val hostProvidedDependencyLines: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localDependencyClasses: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localDependencyResDirs: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localDependencyAssets: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localDependencyJniLibs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun execute() {
        TaskLogger(project, buildType.get(), aarFile.get().asFile.name).use { logger ->
            logger.start(this.path, outputDirectory.get().asFile)
            val workDir = temporaryDir
            val sdk = sdkInfo.get()
            val shellExecutor = ShellExecutor(execOperations, logger)
            val options = packagingOptions.get()

            logger.log("打包选项: $options")

            writeDependencySidecars(logger)

            // --- 1. 数据准备 ---
            val extractor = AarExtractor(project)
            val mainAarExtractDir =
                extractor.extract(aarFile.get().asFile, workDir.resolve("main_aar"))

            val remoteArtifactFiles = remoteProgramArtifacts.files.sortedBy { it.absolutePath }
            val programAars = remoteArtifactFiles.filter { it.extension == "aar" }
            val programJars = remoteArtifactFiles.filter { it.extension == "jar" }
            val hostProvidedAars =
                hostProvidedClasspath.files.filter { it.extension == "aar" }.toSet()

            // 根据配置，有条件地解压程序侧远程AAR并收集产物
            val remoteClassesJars = mutableSetOf<File>()
            val remoteAssetsDirs = mutableSetOf<File>()
            val remoteJniDirs = mutableSetOf<File>()
            val remoteProguardFiles = mutableListOf<File>()
            if (options.isAnyDependencyIncluded()) {
                val remoteAarExtractDir = workDir.resolve("remote_aars")
                programAars.forEachIndexed { index, aar ->
                    val extractDir = extractor.extract(
                        aar,
                        remoteAarExtractDir.resolve("${index}_${aar.nameWithoutExtension}")
                    )
                    extractDir.resolve("classes.jar").takeIf { it.exists() }
                        ?.let { remoteClassesJars.add(it) }
                    extractDir.resolve("libs").takeIf { it.isDirectory }
                        ?.listFiles { file -> file.extension == "jar" }
                        ?.let { remoteClassesJars.addAll(it) }
                    extractDir.resolve("assets").takeIf { it.exists() && it.isDirectory }
                        ?.let { remoteAssetsDirs.add(it) }
                    extractDir.resolve("jni").takeIf { it.exists() && it.isDirectory }
                        ?.let { remoteJniDirs.add(it) }
                    extractDir.resolve("proguard.txt").takeIf { it.isFile }
                        ?.let { remoteProguardFiles.add(it) }
                }
            }

            // --- 2. 资源处理 ---
            val resourceProcessor = ResourceProcessor(project, shellExecutor, sdk, logger)
            val resourceAars = if (options.includeDependenciesRes.get()) {
                (programAars + hostProvidedAars).toSet()
            } else {
                emptySet()
            }
            val linkedResources = resourceProcessor.process(
                mainAarExtractDir,
                resourceAars,
                if (options.includeDependenciesRes.get()) localDependencyResDirs.files else emptySet(),
                packageId.get(),
                workDir,
                extraRPackages.get()
            )
            if (linkedResources == null) {
                logger.log("模块不含资源和Manifest，处理完成。")
                return
            }

            // --- 3. DEX处理 ---
            val allClassJars = mutableSetOf<File>()
            mainAarExtractDir.resolve("classes.jar").takeIf { it.exists() }
                ?.let { allClassJars.add(it) }

            if (options.includeDependenciesDex.get()) {
                logger.log("DEX打包: 包含依赖库的代码（本地项目 + 远程AAR/JAR）。")
                allClassJars.addAll(localDependencyClasses.files)
                allClassJars.addAll(remoteClassesJars)
                allClassJars.addAll(programJars)
            } else {
                logger.log("DEX打包: 仅包含主模块代码。")
            }
            allClassJars.addAll(resolveExtraProgramJars(workDir))

            val servicesStagingDir = workDir.resolve("build/services_staging")
            val servicesKeepRules =
                mergeMetaInfServices(allClassJars, servicesStagingDir, workDir.resolve("build"), logger)

            val dexProcessor = DexProcessor(shellExecutor, sdk, logger)
            val dexFiles = dexProcessor.process(
                allClassJars,
                linkedResources.rJavaSourcesDir,
                buildType.get(),
                workDir,
                minify = minify.get(),
                minApi = minApi.get(),
                proguardFiles = remoteProguardFiles + listOfNotNull(servicesKeepRules) +
                        minifyProguardFiles.files,
                classpathFiles = hostProvidedClasspath.files + minifyClasspathFiles.files,
                mappingOutput = outputDirectory.get()
                    .file("${pluginName.get()}-${buildType.get()}-mapping.txt").asFile,
            )

            // --- 4. 打包 ---
            val allAssetDirs = mutableSetOf<File>()
            mainAarExtractDir.resolve("assets").takeIf { it.exists() && it.isDirectory }
                ?.let { allAssetDirs.add(it) }

            if (options.includeDependenciesAssets.get()) {
                logger.log("Assets打包: 包含依赖库的Assets。")
                allAssetDirs.addAll(localDependencyAssets.files)
                allAssetDirs.addAll(remoteAssetsDirs)
            } else {
                logger.log("Assets打包: 仅包含主模块Assets。")
            }

            val allJniDirs = mutableSetOf<File>()
            mainAarExtractDir.resolve("jni").takeIf { it.exists() && it.isDirectory }
                ?.let { allJniDirs.add(it) }

            if (options.includeDependenciesJni.get()) {
                logger.log("JNI打包: 包含依赖库的so库。")
                allJniDirs.addAll(localDependencyJniLibs.files)
                allJniDirs.addAll(remoteJniDirs)
            } else {
                logger.log("JNI打包: 仅包含主模块so库。")
            }

            val packager = ApkPackager(shellExecutor, logger)
            packager.addDex(linkedResources.unsignedApk, dexFiles)
            packager.addNativeLibs(linkedResources.unsignedApk, allJniDirs)
            packager.addAssets(linkedResources.unsignedApk, allAssetDirs)
            packager.addMetaInfServices(linkedResources.unsignedApk, servicesStagingDir)

            validateHostProvidedClassesAbsent(linkedResources.unsignedApk, logger)

            // --- 5. 签名 ---
            val signer = ApkSigner(shellExecutor, sdk, logger)
            val signedApk =
                outputDirectory.get().file("${pluginName.get()}-${buildType.get()}.apk").asFile
            signer.sign(linkedResources.unsignedApk, signedApk, signingConfig.get())

            logger.log("✅ 转换成功! APK 大小: ${signedApk.length() / 1024} KB")
        }
    }

    private fun resolveExtraProgramJars(workDir: File): List<File> {
        val result = mutableListOf<File>()
        val extractRoot = workDir.resolve("build/extra_program_jars")
        extraProgramJars.files.sortedBy { it.absolutePath }.forEachIndexed { index, file ->
            if (!file.exists()) return@forEachIndexed
            when (file.extension) {
                "jar" -> result.add(file)

                "aar" -> {
                    val outDir = extractRoot.resolve("${index}_${file.nameWithoutExtension}")
                    outDir.deleteRecursively()
                    outDir.mkdirs()
                    ZipFile(file).use { zip ->
                        for (entry in zip.entries()) {
                            val isClassesJar = entry.name == "classes.jar"
                            val isLibJar =
                                entry.name.startsWith("libs/") && entry.name.endsWith(".jar")
                            if (!isClassesJar && !isLibJar) continue
                            val target = File(outDir, entry.name.replace('/', '_'))
                            zip.getInputStream(entry).use { input ->
                                target.outputStream().use { output -> input.copyTo(output) }
                            }
                            result.add(target)
                        }
                    }
                }
            }
        }
        return result
    }

    private fun mergeMetaInfServices(
        programJars: Collection<File>,
        servicesStagingDir: File,
        buildDir: File,
        logger: TaskLogger
    ): File? {
        val services = linkedMapOf<String, LinkedHashSet<String>>()
        programJars.filter { it.isFile }.sortedBy { it.absolutePath }.forEach { jar ->
            ZipFile(jar).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    if (!entry.name.startsWith("META-INF/services/")) continue
                    val serviceName = entry.name.removePrefix("META-INF/services/")
                    if (serviceName.isEmpty() || serviceName.contains('/')) continue
                    val implementations = zip.getInputStream(entry).bufferedReader().readLines()
                        .map { it.substringBefore('#').trim() }
                        .filter { it.isNotEmpty() }
                    if (implementations.isEmpty()) continue
                    services.getOrPut(serviceName) { LinkedHashSet() }.addAll(implementations)
                }
            }
        }
        servicesStagingDir.deleteRecursively()
        if (services.isEmpty()) return null

        val servicesDir = servicesStagingDir.resolve("META-INF/services")
        servicesDir.mkdirs()
        services.forEach { (name, implementations) ->
            servicesDir.resolve(name)
                .writeText(implementations.joinToString(System.lineSeparator()))
        }
        logger.log(
            "合并 META-INF/services: ${services.size} 个service, " +
                    "${services.values.sumOf { it.size }} 个实现"
        )

        val keepRulesFile = buildDir.resolve("services-keep.pro")
        keepRulesFile.parentFile.mkdirs()
        keepRulesFile.writeText(
            services.values.flatten().toSortedSet()
                .joinToString(System.lineSeparator()) { "-keep class $it { <init>(); }" }
        )
        return keepRulesFile
    }

    private fun validateHostProvidedClassesAbsent(apkFile: File, logger: TaskLogger) {
        val prefixes = hostProvidedClassPrefixes.get()
        if (prefixes.isEmpty()) return
        val offending = DexClassNamesReader.readClassNames(apkFile)
            .filter { className -> prefixes.any { className.startsWith(it) } }
        if (offending.isEmpty()) {
            logger.log("防呆校验通过: 产物dex不含宿主提供前缀的类。")
            return
        }
        val preview = offending.take(50).joinToString(System.lineSeparator())
        throw GradleException(
            "插件产物包含 ${offending.size} 个应由宿主提供的类" +
                    "（命中 hostProvidedClassPrefixes），前50个:\n$preview"
        )
    }

    private fun writeDependencySidecars(logger: TaskLogger) {
        val prefix = "${pluginName.get()}-${buildType.get()}"
        val programFile = outputDirectory.get().file("$prefix-deps-program.txt").asFile
        val hostProvidedFile = outputDirectory.get().file("$prefix-deps-host-provided.txt").asFile
        programFile.parentFile.mkdirs()
        val extraLines = extraProgramJars.files.sortedBy { it.absolutePath }
            .map { "extra ${it.name} -> ${it.absolutePath}" }
        val classpathLines = minifyClasspathFiles.files.sortedBy { it.absolutePath }
            .map { "classpath ${it.name} -> ${it.absolutePath}" }
        programFile.writeText(
            (programDependencyLines.get() + extraLines).joinToString(System.lineSeparator())
        )
        hostProvidedFile.writeText(
            (hostProvidedDependencyLines.get() + classpathLines).joinToString(System.lineSeparator())
        )
        logger.log("依赖清单: ${programFile.name} / ${hostProvidedFile.name}")
    }
}
