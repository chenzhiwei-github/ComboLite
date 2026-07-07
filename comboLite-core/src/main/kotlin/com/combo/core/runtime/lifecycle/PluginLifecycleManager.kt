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

package com.combo.core.runtime.lifecycle

import android.os.Build
import com.combo.core.api.IPluginEntryClass
import com.combo.core.model.LoadedPluginInfo
import com.combo.core.model.PluginContext
import com.combo.core.model.PluginFrameworkContext
import com.combo.core.model.PluginInfo
import com.combo.core.runtime.loader.PluginClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import timber.log.Timber
import java.io.File

/**
 * 插件生命周期管理器
 *
 * 负责插件的加载、实例化、启动、卸载和重载等核心生命周期操作。
 */
internal class PluginLifecycleManager(private val context: PluginFrameworkContext) {

    companion object {
        private const val TAG = "PluginLifecycleManager"
        private const val CLASS_INDEX_TAG = "ClassIndex"
    }

    suspend fun launchPlugin(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            when {
                context.loadedPlugins.value.containsKey(pluginId) -> {
                    Timber.Forest.tag(TAG).i("插件 [$pluginId] 已加载，执行链式重启...")
                    reloadPluginWithDependents(pluginId)
                }

                else -> {
                    Timber.Forest.tag(TAG).i("插件 [$pluginId] 未加载，执行首次启动...")
                    launchSinglePlugin(pluginId)
                }
            }
        } catch (e: Throwable) {
            Timber.Forest.tag(TAG).e(e, "启动或重启插件 [$pluginId] 期间发生严重错误。")
            if (context.loadedPlugins.value.containsKey(pluginId)) {
                unloadPlugin(pluginId)
            }
            false
        }
    }

    suspend fun unloadPlugin(pluginId: String) = withContext(Dispatchers.IO) {
        if (!context.loadedPlugins.value.containsKey(pluginId)) {
            Timber.Forest.tag(TAG).w("尝试卸载一个未加载的插件: $pluginId")
            return@withContext
        }

        Timber.Forest.tag(TAG).i("开始卸载插件: $pluginId")

        context.pluginInstances.value[pluginId]?.let { instance ->
            executeOnUnload(pluginId, instance)
            unloadKoinModules(pluginId, instance)
        }

        context.loadedPlugins.update { it - pluginId }
        context.pluginInstances.update { it - pluginId }

        context.proxyManager.unregisterStaticReceivers(pluginId)
        context.proxyManager.unregisterProviders(pluginId)
        context.dependencyManager.clearDependenciesFor(pluginId)
        context.resourcesManager.removePluginResources(pluginId)
        removePluginFromIndex(pluginId)

        Timber.Forest.tag(TAG).i("插件 [$pluginId] 卸载完成。")
    }

    suspend fun loadEnabledPlugins(): Int = withContext(Dispatchers.IO) {
        Timber.Forest.tag(TAG).i("开始异步初始化所有已启用的插件。")
        val enabledPlugins =
            getEnabledPlugins().filter { !context.loadedPlugins.value.containsKey(it.id) }

        if (enabledPlugins.isEmpty()) {
            Timber.Forest.tag(TAG).i("没有新的已启用插件需要加载。")
            return@withContext 0
        }

        Timber.Forest.tag(TAG).i("找到 ${enabledPlugins.size} 个新的已启用插件，开始加载...")
        if (loadAndInstantiatePlugins(enabledPlugins)) {
            enabledPlugins.size
        } else {
            0
        }
    }

    private suspend fun launchSinglePlugin(pluginId: String): Boolean {
        val pluginInfo = context.xmlManager.getPluginById(pluginId) ?: run {
            Timber.Forest.tag(TAG).e("插件信息未找到: $pluginId")
            return false
        }

        val loadedPlugin = loadPlugin(pluginInfo) ?: run {
            Timber.Forest.tag(TAG).e("插件加载失败: $pluginId")
            return false
        }
        context.loadedPlugins.update { it + (pluginId to loadedPlugin) }

        val instance = instantiatePlugin(loadedPlugin) ?: run {
            Timber.Forest.tag(TAG).e("插件实例化失败: $pluginId，执行回滚...")
            unloadPlugin(pluginId)
            return false
        }
        context.pluginInstances.update { it + (pluginId to instance) }

        Timber.Forest.tag(TAG).i("插件 [$pluginId] 首次启动成功。")
        return true
    }

    private suspend fun reloadPluginWithDependents(pluginId: String): Boolean {
        val dependents = context.dependencyManager.findDependentsRecursive(pluginId)
        val pluginsToReloadIds = listOf(pluginId) + dependents
        Timber.Forest.tag(TAG).i("链式重启计划：将重启以下插件: $pluginsToReloadIds")

        pluginsToReloadIds.reversed().forEach { id ->
            if (context.loadedPlugins.value.containsKey(id)) unloadPlugin(id)
        }
        Timber.Forest.tag(TAG).i("所有相关插件已卸载，准备重新加载...")

        val pluginInfosToReload = pluginsToReloadIds.mapNotNull { context.xmlManager.getPluginById(it) }
        if (pluginInfosToReload.size != pluginsToReloadIds.size) {
            Timber.Forest.tag(TAG).e("无法获取部分要重启的插件信息，操作中止。")
            return false
        }

        return loadAndInstantiatePlugins(pluginInfosToReload)
    }

    private suspend fun loadAndInstantiatePlugins(pluginsToLoad: List<PluginInfo>): Boolean =
        coroutineScope {
            if (pluginsToLoad.isEmpty()) return@coroutineScope true
            var loadedPluginsList: List<LoadedPluginInfo>? = null

            try {
                val loadJobs = pluginsToLoad.map { async(Dispatchers.IO) { loadPlugin(it) } }
                loadedPluginsList = loadJobs.awaitAll().filterNotNull()
                if (loadedPluginsList.size != pluginsToLoad.size) {
                    Timber.Forest.tag(TAG).w("部分插件加载失败，操作中止。")
                    loadedPluginsList.forEach { unloadPlugin(it.pluginInfo.id) }
                    return@coroutineScope false
                }

                context.loadedPlugins.update { it + loadedPluginsList.associateBy { p -> p.pluginInfo.id } }
                Timber.Forest.tag(TAG).d("成功注册 ${loadedPluginsList.size} 个插件的加载信息。")

                val instantiateJobs = loadedPluginsList.map { loadedPlugin ->
                    async(Dispatchers.Default) {
                        instantiatePlugin(loadedPlugin)?.let { it1 -> loadedPlugin.pluginInfo.id to it1 }
                    }
                }
                val successfulInstances = instantiateJobs.awaitAll().filterNotNull().toMap()
                if (successfulInstances.size != pluginsToLoad.size) {
                    Timber.Forest.tag(TAG).e("部分插件实例化失败，执行回滚...")
                    loadedPluginsList.forEach { unloadPlugin(it.pluginInfo.id) }
                    return@coroutineScope false
                }

                context.pluginInstances.update { it + successfulInstances }
                Timber.Forest.tag(TAG)
                    .i("批量操作成功，共加载并实例化 ${successfulInstances.size} 个插件。")
                true
            } catch (e: Throwable) {
                Timber.Forest.tag(TAG).e(e, "批量加载插件时发生严重错误，执行回滚...")
                loadedPluginsList?.forEach { unloadPlugin(it.pluginInfo.id) }
                false
            }
        }

    private suspend fun loadPlugin(plugin: PluginInfo): LoadedPluginInfo? =
        withContext(Dispatchers.IO) {
            try {
                val pluginApkFile = File(plugin.path)
                if (!pluginApkFile.exists()) {
                    Timber.Forest.tag(TAG).e("插件 APK 文件不存在: ${plugin.path}")
                    return@withContext null
                }

                loadClassIndexForPlugin(plugin)

                context.proxyManager.registerStaticReceivers(
                    plugin.id,
                    plugin.staticReceivers.filter { it.enabled })
                context.proxyManager.registerProviders(
                    plugin.id,
                    plugin.providers.filter { it.enabled })

                val pluginInstallDir = context.installerManager.getPluginDirectory(plugin.id)
                val abi = Build.SUPPORTED_ABIS[0]
                val nativeLibDir = File(pluginInstallDir, "lib/$abi")
                val nativeLibraryPath =
                    if (nativeLibDir.exists()) nativeLibDir.absolutePath else null
                val optimizedDirectory =
                    context.installerManager.getOptimizedDirectory(plugin.id)?.absolutePath

                val classLoader = PluginClassLoader(
                    pluginId = plugin.id,
                    pluginFile = pluginApkFile,
                    parent = context.application.classLoader,
                    optimizedDirectory = optimizedDirectory,
                    librarySearchPath = nativeLibraryPath,
                    pluginFinder = context.dependencyManager,
                    loadingPolicy = context.classLoadingPolicy,
                )

                context.resourcesManager.loadPluginResources(plugin.id, pluginApkFile)
                LoadedPluginInfo(pluginInfo = plugin, classLoader = classLoader)
            } catch (e: Exception) {
                Timber.Forest.tag(TAG).e(e, "加载插件 ${plugin.id} 失败: ${e.message}")
                null
            }
        }

    private fun instantiatePlugin(loadedPlugin: LoadedPluginInfo): IPluginEntryClass? {
        val plugin = loadedPlugin.pluginInfo
        return try {
            val instance = loadedPlugin.classLoader.getInterface(IPluginEntryClass::class.java, plugin.entryClass)
            if (instance != null) {
                Timber.Forest.tag(TAG).d("插件入口类实例化成功: ${plugin.id} -> ${plugin.entryClass}")
                loadKoinModules(plugin.id, instance)
                executeOnLoad(plugin, instance)
                instance
            } else {
                Timber.Forest.tag(TAG).e("插件入口类实例化失败: ${plugin.id} -> ${plugin.entryClass}")
                null
            }
        } catch (e: Exception) {
            Timber.Forest.tag(TAG).e(e, "实例化插件 ${plugin.id} 入口类失败: ${e.message}")
            null
        }
    }

    private fun getEnabledPlugins(): List<PluginInfo> {
        return try {
            context.xmlManager.getAllPlugins().filter { it.enabled }
        } catch (e: Exception) {
            Timber.Forest.tag(TAG).e(e, "读取已启用插件信息失败。")
            emptyList()
        }
    }

    private fun executeOnLoad(plugin: PluginInfo, instance: IPluginEntryClass) {
        try {
            val pluginContext =
                PluginContext(application = context.application, pluginInfo = plugin)
            instance.onLoad(pluginContext)
            Timber.Forest.tag(TAG).d("插件 [${plugin.id}] onLoad() 执行成功。")
        } catch (e: Exception) {
            Timber.Forest.tag(TAG).e(e, "插件 [${plugin.id}] onLoad() 执行失败。")
        }
    }

    private fun executeOnUnload(pluginId: String, instance: IPluginEntryClass) {
        try {
            instance.onUnload()
            Timber.Forest.tag(TAG).d("插件 [$pluginId] onUnload() 执行成功。")
        } catch (e: Exception) {
            Timber.Forest.tag(TAG).e(e, "插件 [$pluginId] onUnload() 执行失败。")
        }
    }

    private fun loadKoinModules(pluginId: String, instance: IPluginEntryClass) {
        try {
            val modules = instance.pluginModule
            if (modules.isNotEmpty()) {
                GlobalContext.get().loadModules(modules)
                Timber.Forest.tag(TAG).d("插件 [$pluginId] 的 ${modules.size} 个 Koin 模块加载成功。")
            }
        } catch (e: Exception) {
            Timber.Forest.tag(TAG).e(e, "加载插件 [$pluginId] 的 Koin 模块失败。")
        }
    }

    private fun unloadKoinModules(pluginId: String, instance: IPluginEntryClass) {
        try {
            val modules = instance.pluginModule
            if (modules.isNotEmpty()) {
                GlobalContext.get().unloadModules(modules)
                Timber.Forest.tag(TAG).d("插件 [$pluginId] 的 ${modules.size} 个 Koin 模块卸载成功。")
            }
        } catch (e: Exception) {
            Timber.Forest.tag(TAG).e(e, "卸载插件 [$pluginId] 的 Koin 模块失败。")
        }
    }

    private fun loadClassIndexForPlugin(plugin: PluginInfo) {
        val pluginDir = context.installerManager.getPluginDirectory(plugin.id)
        val indexFile = File(pluginDir, "class_index")
        var loadedCount = 0

        if (!indexFile.exists()) {
            Timber.Forest.tag(CLASS_INDEX_TAG).e("类索引文件未找到: ${indexFile.absolutePath}")
            return
        }

        try {
            indexFile.forEachLine { className ->
                if (className.isNotBlank()) {
                    val previousOwner = context.classIndex.put(className, plugin.id)
                    if (previousOwner != null && previousOwner != plugin.id) {
                        Timber.Forest.tag(CLASS_INDEX_TAG)
                            .e("类索引冲突: 类 '$className' 已属于插件 [$previousOwner]，现被插件 [${plugin.id}] 覆盖。")
                    }
                    loadedCount++
                }
            }
            Timber.Forest.tag(CLASS_INDEX_TAG).d("为插件 [${plugin.id}] 从文件加载了 $loadedCount 个类索引。")
        } catch (e: Exception) {
            Timber.Forest.tag(CLASS_INDEX_TAG).e(e, "从文件加载类索引失败: ${indexFile.absolutePath}")
        }
    }

    private fun removePluginFromIndex(pluginId: String) {
        var removedCount = 0
        val iterator = context.classIndex.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value == pluginId) {
                iterator.remove()
                removedCount++
            }
        }
        Timber.Forest.tag(CLASS_INDEX_TAG).d("从索引中移除了插件 [$pluginId] 的 $removedCount 个类。")
    }
}