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

package com.combo.core.runtime.installer

import android.app.Application
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.XmlResourceParser
import android.os.Build
import com.combo.core.model.AuthorizationRequest
import com.combo.core.model.IntentFilterInfo
import com.combo.core.model.MetaDataInfo
import com.combo.core.model.PluginInfo
import com.combo.core.model.ProviderInfo
import com.combo.core.model.StaticReceiverInfo
import com.combo.core.runtime.PluginManager
import com.combo.core.runtime.ValidationStrategy
import com.combo.core.security.permission.PermissionLevel
import com.combo.core.security.permission.RequiresPermission
import com.combo.core.security.permission.checkApiCaller
import com.combo.core.security.signature.SignatureValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlin.reflect.jvm.javaMethod


/**
 * 插件安装器
 * 负责插件的安装与卸载流程。
 * @property context Application 上下文，用于访问应用资源和包管理器。
 * @property xmlManager [XmlManager] 实例，用于管理 `plugins.xml` 配置文件。
 */
class InstallerManager(
    private val context: Application,
    private val xmlManager: XmlManager,
) {
    companion object {
        private const val TAG = "PluginInstaller"

        const val PLUGINS_DIR = "plugins"
        const val PLUGIN_BASE_APK_NAME = "base.apk"
        const val NATIVE_LIBS_DIR_NAME = "lib"
        const val DEX_OPTIMIZED_DIR_NAME = "dex_opt"
        const val CLASS_INDEX_FILENAME = "class_index"

        // 自定义的元数据键名
        private const val META_PLUGIN_ENTRY_CLASS = "plugin.entryClass"
        private const val META_PLUGIN_DESCRIPTION = "plugin.description"

        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }

    /**
     * 从插件 `AndroidManifest.xml` 中解析出的核心配置信息。
     *
     * @property id 插件的唯一标识符。
     * @property name 插件的名称。
     * @property iconResId 插件的图标资源ID。
     * @property pluginDescription 插件的功能描述。
     * @property versionCode 插件的版本号。
     * @property pluginVersionName 插件的版本名称。
     * @property entryClass 插件的入口类全限定名。
     */
    @Serializable
    data class PluginConfig(
        val id: String,
        val name: String,
        val iconResId: Int,
        val pluginDescription: String,
        val versionCode: Long,
        val pluginVersionName: String,
        val entryClass: String,
    )
    /**
     * 表示插件安装操作的结果。
     */
    sealed class InstallResult {
        /**
         * 表示安装成功。
         * @property pluginInfo 安装成功的插件信息。
         */
        data class Success(val pluginInfo: PluginInfo) : InstallResult()

        /**
         * 表示安装失败。
         * @property reason 失败的原因描述。
         * @property exception 导致失败的异常（可选）。
         */
        data class Failure(val reason: String, val exception: Throwable? = null) : InstallResult()
    }

    /**
     * 插件安装的根目录。
     * 懒加载，并在首次访问时自动创建目录。
     */
    private val pluginsDir: File by lazy {
        File(context.filesDir, PLUGINS_DIR).apply { mkdirs() }
    }

    /**
     * 异步安装一个插件。
     * 此方法会执行完整的安装流程，包括签名验证、版本检查、文件复制、组件解析和信息持久化。
     * API权限要求：[PermissionLevel.HOST]
     *
     * @param pluginApkFile 待安装的插件APK文件。
     * @param forceOverwrite 是否强制覆盖安装。如果为 `true`，则会忽略版本检查，直接覆盖现有插件。
     * 默认为 `false`。
     * @return [InstallResult] 对象，表示安装成功或失败。
     * @see[RequiresPermission]
     */
    @RequiresPermission(PermissionLevel.HOST)
    suspend fun installPlugin(
        pluginApkFile: File,
        forceOverwrite: Boolean = false,
    ): InstallResult = withContext(Dispatchers.IO) {
        xmlManager.registryAccessMode.requireMutable("installPlugin")
        if (::installPlugin.javaMethod?.checkApiCaller() == false) {
            Timber.w("权限不足：插件安装操作被拒绝")
            return@withContext InstallResult.Failure("权限不足")
        }

        Timber.tag(TAG).i("开始安装插件: ${pluginApkFile.name}, forceOverwrite: $forceOverwrite")

        // 步骤 1 & 2: 验证文件和元数据
        if (!pluginApkFile.exists()) return@withContext InstallResult.Failure("插件文件不存在")
        val pluginConfig = validateAndParseConfig(pluginApkFile)
            ?: return@withContext InstallResult.Failure("插件配置元数据验证失败")

        val validationResult = checkSignatureAndAuthorize(pluginApkFile, pluginConfig)
        if (!validationResult.isSuccess) {
            return@withContext InstallResult.Failure(validationResult.reason)
        }

        val pluginId = pluginConfig.id
        val pluginDir = getPluginDirectory(pluginId)

        // 步骤 4: 版本检查
        val existingPlugin = xmlManager.getPluginById(pluginId)
        when {
            existingPlugin == null -> {
                Timber.tag(TAG).i("新插件安装: $pluginId")
            }

            forceOverwrite -> {
                Timber.tag(TAG).i("强制覆盖安装插件: $pluginId")
                clearDexOptCache(pluginId)
            }

            pluginConfig.versionCode <= existingPlugin.versionCode -> {
                return@withContext InstallResult.Failure(
                    "已安装更高或相同版本 (code: ${existingPlugin.versionCode})，新版本 (code: ${pluginConfig.versionCode}) 不能覆盖"
                )
            }

            else -> {
                Timber.tag(TAG)
                    .i("插件版本升级: $pluginId (${existingPlugin.versionName} -> ${pluginConfig.pluginVersionName})")
                clearDexOptCache(pluginId)
            }
        }

        // 步骤 5: 备份旧插件目录 (如果是更新)
        val backupDir = File(pluginsDir, "$pluginId.backup")
        if (pluginDir.exists()) {
            try {
                pluginDir.copyRecursively(backupDir, overwrite = true)
                pluginDir.deleteRecursively()
                Timber.tag(TAG).d("旧插件目录已备份至: ${backupDir.absolutePath}")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "备份旧插件目录失败，继续安装")
            }
        }

        pluginDir.mkdirs()

        try {
            // 步骤 6: 复制 APK、解压 so 库、创建类索引
            val targetApkFile = copyPluginApk(pluginApkFile, pluginDir)
            extractNativeLibs(pluginApkFile, pluginDir)
            val indexSuccess = createClassIndex(targetApkFile, pluginDir)
            if (!indexSuccess) {
                throw IOException("创建插件类索引失败")
            }

            // 步骤 7: 解析四大组件信息
            val staticReceivers = parseStaticReceivers(targetApkFile.absolutePath)
            val providers = parseProviders(targetApkFile.absolutePath)

            // 步骤 8: 更新 plugins.xml
            val pluginInfo = PluginInfo(
                id = pluginConfig.id,
                name = pluginConfig.name,
                iconResId = pluginConfig.iconResId,
                description = pluginConfig.pluginDescription,
                versionCode = pluginConfig.versionCode,
                versionName = pluginConfig.pluginVersionName,
                path = targetApkFile.absolutePath,
                entryClass = pluginConfig.entryClass,
                enabled = existingPlugin?.enabled ?: true,
                installTime = existingPlugin?.installTime ?: System.currentTimeMillis(),
                staticReceivers = staticReceivers,
                providers = providers,
            )

            if (existingPlugin != null) {
                xmlManager.updatePlugin(pluginInfo)
            } else {
                xmlManager.addPlugin(pluginInfo)
            }
            xmlManager.flushToDisk()

            // 步骤 9: 清理备份
            backupDir.deleteRecursively()

            Timber.tag(TAG).i("插件 [$pluginId] 安装成功。")
            InstallResult.Success(pluginInfo)
        } catch (e: Exception) {
            // 如果安装过程中出错，尝试从备份恢复
            pluginDir.deleteRecursively()
            if (backupDir.exists()) {
                try {
                    backupDir.renameTo(pluginDir)
                    Timber.tag(TAG).i("从备份恢复插件目录: $pluginId")
                } catch (ex: Exception) {
                    Timber.tag(TAG).e(ex, "从备份恢复失败")
                }
            }
            val reason = "插件安装过程中发生异常: ${e.message}"
            Timber.tag(TAG).e(e, reason)
            InstallResult.Failure(reason, e)
        }
    }

    /** Immutable installation. The Authority owns the root, intent, OS lock and cleanup. */
    @RequiresPermission(PermissionLevel.HOST, hardFail = true)
    suspend fun installArtifact(
        pluginApkFile: File,
        artifactDirectory: File,
        expectedPluginId: String,
        expectedVersionCode: Long,
        expectedSha256: String,
    ): InstallResult = withContext(Dispatchers.IO) {
        xmlManager.registryAccessMode.requireMutable("installArtifact")
        if (::installArtifact.javaMethod?.checkApiCaller() == false) {
            return@withContext InstallResult.Failure("Artifact installation denied")
        }
        try {
            InstallResult.Success(ArtifactInstaller(context).install(
                pluginApkFile, artifactDirectory, expectedPluginId, expectedVersionCode, expectedSha256,
                parse = { apk ->
                    val config = requireNotNull(validateAndParseConfig(apk)) { "Invalid APK metadata" }
                    val signature = checkSignatureAndAuthorize(apk, config)
                    check(signature.isSuccess) { signature.reason }
                    PluginInfo(config.id, config.name, config.iconResId, config.versionCode,
                        config.pluginVersionName, apk.path, config.entryClass, config.pluginDescription,
                        true, System.currentTimeMillis(), parseStaticReceivers(apk.path), parseProviders(apk.path))
                },
                createIndex = ::createClassIndex,
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            InstallResult.Failure("Immutable artifact installation failed: ${e.message}", e)
        }
    }

    /** Read-only complete-tree validation; never opens plugins.xml or loads plugin code. */
    fun inspectArtifact(
        artifactDirectory: File,
        expectedPluginId: String,
        expectedVersionCode: Long,
        expectedSha256: String,
    ): PluginInfo {
        val info = ArtifactInstaller(context).inspect(
            artifactDirectory, expectedPluginId, expectedVersionCode, expectedSha256,
        )
        val config = requireNotNull(validateAndParseConfig(File(info.path))) { "Invalid installed APK metadata" }
        check(config.id == info.id && config.versionCode == info.versionCode &&
            config.entryClass == info.entryClass && config.pluginVersionName == info.versionName) {
            "Installed artifact record differs from APK metadata"
        }
        return info
    }

    /**
     * 卸载一个插件。
     * 这是一个事务性操作，会先将插件目录重命名，删除成功后再更新配置文件，以保证操作的原子性。
     * API权限要求：[PermissionLevel.SELF]
     *
     * @param pluginId 要卸载的插件的唯一标识符。
     * @return `true` 如果卸载成功，否则返回 `false`。
     * @see[RequiresPermission]
     */
    @RequiresPermission(PermissionLevel.SELF)
    suspend fun uninstallPlugin(pluginId: String): Boolean {
        xmlManager.registryAccessMode.requireMutable("uninstallPlugin")
        if (::uninstallPlugin.javaMethod?.checkApiCaller(pluginId) == false) {
            Timber.w("权限不足：插件卸载操作被拒绝")
            return false
        }

        Timber.tag(TAG).i("开始事务性卸载插件: $pluginId")

        // 清理插件的 DexOpt 缓存
        clearDexOptCache(pluginId)

        val pluginDir = getPluginDirectory(pluginId)

        // 如果插件目录不存在，则仅清理XML记录
        if (!pluginDir.exists()) {
            Timber.tag(TAG).w("插件目录不存在: ${pluginDir.absolutePath}, 将仅清理XML记录。")
            return if (xmlManager.getPluginById(pluginId) != null) {
                xmlManager.removePlugin(pluginId)
                xmlManager.flushToDisk()
                true
            } else {
                true
            }
        }

        // 将目录重命名为一个临时名称，防止删除过程中文件被占用
        val deletingDir =
            File(pluginDir.parentFile, "${pluginDir.name}.deleting_${System.currentTimeMillis()}")
        if (!pluginDir.renameTo(deletingDir)) {
            Timber.tag(TAG).e("卸载失败：无法重命名插件目录以进行安全删除。请检查文件权限或占用情况。")
            return false
        }

        Timber.tag(TAG).d("插件目录已移动至临时位置: ${deletingDir.absolutePath}")

        // 递归删除临时目录
        if (deletingDir.deleteRecursively()) {
            Timber.tag(TAG).d("临时目录已成功删除。")
            if (xmlManager.removePlugin(pluginId)) {
                xmlManager.flushToDisk()
            }
            Timber.tag(TAG).i("插件 [$pluginId] 已完全卸载。")
            return true
        } else {
            // 如果删除失败，尝试回滚（将临时目录重命名回去）
            Timber.tag(TAG).e("关键错误：在删除临时目录 ${deletingDir.name} 时失败。")
            if (deletingDir.renameTo(pluginDir)) {
                Timber.tag(TAG).i("回滚成功：插件目录已从临时位置恢复。")
            } else {
                Timber.tag(TAG)
                    .e("回滚失败！插件处于不一致状态，目录位于: ${deletingDir.absolutePath}")
            }
            return false
        }
    }

    /**
     * 获取指定插件的安装目录。
     *
     * @param pluginId 插件的唯一标识符。
     * @return 插件的安装目录 [File] 对象。
     */
    internal fun getPluginDirectory(pluginId: String): File {
        return File(pluginsDir, pluginId)
    }

    /**
     * 获取指定插件的 DEX 优化缓存目录。
     * 此操作仅在 Android 8.0 (API 26) 以下的系统版本有效，因为更高版本由系统管理DEX优化。
     */
    internal fun getOptimizedDirectory(pluginId: String): File? {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            File(File(context.cacheDir, DEX_OPTIMIZED_DIR_NAME), pluginId).apply { mkdirs() }
        } else {
            null
        }
    }

    /**
     * 创建并保存插件的类索引文件，支持多DEX
     */
    private fun createClassIndex(pluginApkFile: File, pluginDir: File): Boolean {
        val indexFile = File(pluginDir, CLASS_INDEX_FILENAME)
        var indexedCount = 0
        Timber.tag(TAG).d("开始为插件 [${pluginDir.name}] 创建类索引...")

        try {
            val dexContainer = DexFileFactory.loadDexContainer(pluginApkFile, Opcodes.forApi(Build.VERSION.SDK_INT))
            val dexEntryNames = dexContainer.dexEntryNames
            Timber.tag(TAG).d("在 ${pluginApkFile.name} 中找到 ${dexEntryNames.size} 个 DEX 文件: $dexEntryNames")

            indexFile.bufferedWriter().use { writer ->
                for (dexEntryName in dexEntryNames) {
                    // 获取 DexEntry 对象
                    val dexEntry = dexContainer.getEntry(dexEntryName) ?: continue

                    // 从 DexEntry 获取 DexFile
                    val dexFile = dexEntry.dexFile

                    // 遍历 DexFile 中的所有类
                    dexFile.classes.forEach { classDef ->
                        val className = convertDexTypeToClassName(classDef.type)
                        writer.write(className)
                        writer.newLine()
                        indexedCount++
                    }
                }
            }
            Timber.tag(TAG).i("为插件 [${pluginDir.name}] 成功创建类索引，共 $indexedCount 个类，文件: ${indexFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "创建类索引文件失败: ${pluginApkFile.name}")
            indexFile.delete()
            return false
        }
    }

    /**
     * 辅助函数，用于将 DexFile 中的类型描述符转换为标准的 Java 类名。
     */
    private fun convertDexTypeToClassName(dexType: String): String {
        return if (dexType.startsWith("L") && dexType.endsWith(";")) {
            dexType.substring(1, dexType.length - 1).replace('/', '.')
        } else {
            dexType.replace('/', '.')
        }
    }

    /**
     * 验证并解析插件 `AndroidManifest.xml` 中的元数据。
     *
     * @param pluginApkFile 插件APK文件。
     * @return 如果验证通过，返回 [PluginConfig] 对象；否则返回 `null`。
     */
    private fun validateAndParseConfig(pluginApkFile: File): PluginConfig? {
        Timber.tag(TAG).d("开始验证插件元数据配置: ${pluginApkFile.name}")
        try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(
                pluginApkFile.absolutePath,
                PackageManager.GET_META_DATA,
            )

            if (packageInfo == null) {
                Timber.tag(TAG).e("无法解析插件APK包信息: ${pluginApkFile.name}")
                return null
            }

            val appInfo = packageInfo.applicationInfo
            if (appInfo == null) {
                Timber.tag(TAG).e("在 PackageInfo 中未找到 ApplicationInfo。")
                return null
            }
            // 这一步很关键，确保 getApplicationLabel 能正确加载 APK 内的字符串资源
            appInfo.publicSourceDir = pluginApkFile.absolutePath

            val metaData = appInfo.metaData
            if (metaData == null) {
                Timber.tag(TAG).e("插件AndroidManifest.xml中未找到<application>标签下的元数据。")
                return null
            }

            val pluginId = packageInfo.packageName
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            val versionName = packageInfo.versionName ?: "0.0.0"
            val name = pm.getApplicationLabel(appInfo).toString()
            val iconResId = appInfo.icon

            val entryClass = metaData.getString(META_PLUGIN_ENTRY_CLASS)
            val pluginDescription = metaData.getString(META_PLUGIN_DESCRIPTION) ?: ""

            if (pluginId.isBlank() || entryClass.isNullOrBlank()) {
                Timber.tag(TAG).e("核心元数据 (package, entryClass) 不能为空。")
                return null
            }

            val pluginConfig = PluginConfig(
                id = pluginId,
                name = name,
                iconResId = iconResId,
                pluginDescription = pluginDescription,
                versionCode = versionCode,
                pluginVersionName = versionName,
                entryClass = entryClass
            )

            Timber.tag(TAG).d("插件元数据配置验证通过: ${pluginConfig.id}")
            return pluginConfig
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "解析插件元数据配置失败: ${e.message}")
            return null
        }
    }

    /**
     * 解析插件 APK 文件中的静态广播接收器 (`<receiver>`) 信息。
     *
     * @param apkPath 插件 APK 的文件路径。
     * @return 解析出的静态广播信息列表 ([StaticReceiverInfo])。
     */
    private fun parseStaticReceivers(apkPath: String): List<StaticReceiverInfo> {
        Timber.tag(TAG).d("开始解析 StaticReceivers: $apkPath")
        var parser: XmlResourceParser? = null
        var assetManager: AssetManager? = null
        try {
            assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val addAssetPathMethod =
                AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            val cookie = addAssetPathMethod.invoke(assetManager, apkPath) as Int
            check(cookie != 0) { "Cannot load plugin manifest resources" }

            parser = assetManager.openXmlResourceParser(cookie, "AndroidManifest.xml")
            val receivers = mutableListOf<StaticReceiverInfo>()
            var eventType = parser.eventType
            var currentReceiverName: String? = null
            var currentReceiverEnabled = true
            var currentReceiverExported = false
            var currentFilters: MutableList<IntentFilterInfo>? = null
            var inReceiverTag = false
            var inFilterTag = false
            var currentActions: MutableList<String>? = null
            var currentCategories: MutableList<String>? = null
            var currentSchemes: MutableList<String>? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "receiver" -> {
                            inReceiverTag = true
                            currentFilters = mutableListOf()
                            currentReceiverName =
                                parser.getAttributeValue(ANDROID_NAMESPACE, "name")
                            currentReceiverEnabled =
                                parser.getAttributeBooleanValue(ANDROID_NAMESPACE, "enabled", true)
                            currentReceiverExported = parser.getAttributeBooleanValue(
                                ANDROID_NAMESPACE,
                                "exported",
                                false
                            )
                        }

                        "intent-filter" -> if (inReceiverTag) {
                            inFilterTag = true
                            currentActions = mutableListOf()
                            currentCategories = mutableListOf()
                            currentSchemes = mutableListOf()
                        }

                        "action" -> if (inFilterTag) {
                            parser.getAttributeValue(ANDROID_NAMESPACE, "name")
                                ?.let { currentActions?.add(it) }
                        }

                        "category" -> if (inFilterTag) {
                            parser.getAttributeValue(ANDROID_NAMESPACE, "name")
                                ?.let { currentCategories?.add(it) }
                        }

                        "data" -> if (inFilterTag) {
                            parser.getAttributeValue(ANDROID_NAMESPACE, "scheme")
                                ?.let { currentSchemes?.add(it) }
                        }
                    }

                    XmlPullParser.END_TAG -> when (parser.name) {
                        "intent-filter" -> if (inFilterTag) {
                            inFilterTag = false
                            currentFilters?.add(
                                IntentFilterInfo(
                                    actions = currentActions ?: emptyList(),
                                    categories = currentCategories ?: emptyList(),
                                    schemes = currentSchemes ?: emptyList(),
                                ),
                            )
                        }

                        "receiver" -> if (inReceiverTag) {
                            inReceiverTag = false
                            if (!currentReceiverName.isNullOrBlank() && !currentFilters.isNullOrEmpty()) {
                                receivers.add(
                                    StaticReceiverInfo(
                                        className = currentReceiverName,
                                        enabled = currentReceiverEnabled,
                                        exported = currentReceiverExported,
                                        intentFilters = currentFilters,
                                    )
                                )
                                Timber.tag(TAG)
                                    .d("解析到静态广播: $currentReceiverName, filters: ${currentFilters.size}")
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
            return receivers
        } catch (e: Exception) {
            throw IOException("Cannot parse plugin static receivers: $apkPath", e)
        } finally {
            parser?.close()
            assetManager?.close()
        }
    }

    /**
     * 解析插件 APK 文件中的内容提供者 (`<provider>`) 信息。
     *
     * @param apkPath 插件 APK 的文件路径。
     * @return 解析出的内容提供者信息列表 ([ProviderInfo])。
     */
    @Suppress("DEPRECATION")
    private fun parseProviders(apkPath: String): List<ProviderInfo> {
        Timber.tag(TAG).d("开始解析 ContentProvider: $apkPath")
        try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(
                apkPath,
                PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA,
            )

            requireNotNull(packageInfo) { "Cannot parse plugin providers" }
            val providerList = mutableListOf<ProviderInfo>()
            packageInfo.providers?.forEach { provider ->
                val authorities = provider.authority?.split(";")?.filter { it.isNotBlank() }
                if (!authorities.isNullOrEmpty()) {
                    val metaDataList = provider.metaData?.keySet()?.mapNotNull { key ->
                        val rawValue = provider.metaData.get(key)
                        when (rawValue) {
                            is Int -> MetaDataInfo(name = key, value = null, resource = rawValue)
                            else -> MetaDataInfo(
                                name = key,
                                value = rawValue?.toString(),
                                resource = null
                            )
                        }
                    } ?: emptyList()

                    providerList.add(
                        ProviderInfo(
                            className = provider.name,
                            authorities = authorities,
                            enabled = provider.enabled,
                            exported = provider.exported,
                            metaData = metaDataList,
                        ),
                    )
                    Timber.tag(TAG)
                        .d("解析到 ContentProvider: ${provider.name}, authorities: $authorities, exported: ${provider.exported}")
                }
            }
            return providerList
        } catch (e: Exception) {
            throw IOException("Cannot parse plugin providers: $apkPath", e)
        }
    }

    /**
     * 将源 APK 文件复制到指定的插件目录中。
     *
     * @param sourceFile 源 APK 文件。
     * @param pluginDir 目标插件目录。
     * @return 复制后的目标文件 [File] 对象。
     * @throws IOException 如果文件复制或验证失败。
     */
    private fun copyPluginApk(sourceFile: File, pluginDir: File): File {
        val targetFile = File(pluginDir, PLUGIN_BASE_APK_NAME)
        Timber.tag(TAG).d("复制 APK: ${sourceFile.name} -> ${targetFile.absolutePath}")

        sourceFile.inputStream().use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (!targetFile.exists() || targetFile.length() != sourceFile.length()) {
            throw IOException("APK 文件复制验证失败")
        }
        targetFile.setReadOnly()
        return targetFile
    }

    /**
     * 从插件 APK 中提取 native so 库到指定的插件目录。
     *
     * @param pluginApk 插件 APK 文件。
     * @param pluginDir 目标插件目录。
     */
    private fun extractNativeLibs(pluginApk: File, pluginDir: File) {
        val libDir = File(pluginDir, NATIVE_LIBS_DIR_NAME)
        libDir.mkdirs()

        ZipFile(pluginApk).use { zip ->
            for (entry in zip.entries()) {
                if (entry.name.startsWith("lib/") && !entry.isDirectory) {
                    val abi = entry.name.substringAfter("lib/").substringBefore('/')
                    if (Build.SUPPORTED_ABIS.contains(abi)) {
                        val abiDir = File(libDir, abi).apply { mkdirs() }
                        val outputFile = File(abiDir, entry.name.substringAfterLast('/'))
                        zip.getInputStream(entry).use { input ->
                            outputFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
        }
        Timber.tag(TAG).d("插件 .so 库已解压至: ${libDir.absolutePath}")
    }

    /**
     * 清理指定插件的 DEX 优化缓存目录。
     * 此操作仅在 Android 8.0 (API 26) 以下的系统版本有效，因为更高版本由系统管理DEX优化。
     *
     * @param pluginId 插件的唯一标识符。
     */
    private fun clearDexOptCache(pluginId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            try {
                val cacheDir = File(context.cacheDir, DEX_OPTIMIZED_DIR_NAME)
                val pluginOptDir = File(cacheDir, pluginId)
                if (pluginOptDir.exists()) {
                    if (pluginOptDir.deleteRecursively()) {
                        Timber.tag(TAG).i("已成功清理插件 [$pluginId] 的 DexOpt 缓存。")
                    } else {
                        Timber.tag(TAG).w("清理插件 [$pluginId] 的 DexOpt 缓存失败。")
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "清理插件 [$pluginId] 的 DexOpt 缓存时发生错误。")
            }
        }
    }

    private suspend fun checkSignatureAndAuthorize(pluginApkFile: File, pluginConfig: PluginConfig): ValidationResult {
        val pluginSignatures = SignatureValidator.getPluginSignatures(context, pluginApkFile.absolutePath)
        if (pluginSignatures.isEmpty()) {
            return ValidationResult(false, "无法获取插件签名")
        }

        val hostSignatures = SignatureValidator.getHostSignatures(context)
        val matchesHostSignature = hostSignatures.containsAll(pluginSignatures)

        return when (PluginManager.validationStrategy) {
            ValidationStrategy.Strict -> {
                if (matchesHostSignature) ValidationResult(true)
                else ValidationResult(false, "签名不匹配 (严格模式)")
            }
            ValidationStrategy.UserGrant -> {
                if (matchesHostSignature) {
                    ValidationResult(true)
                } else {
                    val request = AuthorizationRequest.forInstall(
                        pluginId = pluginConfig.id,
                        name = pluginConfig.name,
                        iconResId = pluginConfig.iconResId,
                        description = pluginConfig.pluginDescription,
                        versionName = pluginConfig.pluginVersionName,
                        signature = pluginSignatures.first(),
                        apkPath = pluginApkFile.absolutePath
                    )
                    val authorized = PluginManager.authorizationManager.requestAuthorization(request, hardFail = false)
                    if (authorized) ValidationResult(true)
                    else ValidationResult(false, "用户拒绝安装")
                }
            }
            ValidationStrategy.Insecure -> {
                Timber.w("警告：正在使用 Insecure 模式，已跳过签名校验！")
                ValidationResult(true)
            }
        }
    }

    private data class ValidationResult(val isSuccess: Boolean, val reason: String = "")
}