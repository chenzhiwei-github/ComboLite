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

package com.combo.core.runtime

import android.app.Application
import com.combo.core.api.IPluginEntryClass
import com.combo.core.model.LoadedPluginInfo
import com.combo.core.model.PluginFrameworkContext
import com.combo.core.model.PluginInfo
import com.combo.core.proxy.ProxyManager
import com.combo.core.runtime.InitState.INITIALIZED
import com.combo.core.runtime.InitState.INITIALIZING
import com.combo.core.runtime.InitState.NOT_INITIALIZED
import com.combo.core.runtime.ValidationStrategy.Insecure
import com.combo.core.runtime.ValidationStrategy.Strict
import com.combo.core.runtime.ValidationStrategy.UserGrant
import com.combo.core.runtime.loader.PluginClassLoadingPolicy
import com.combo.core.runtime.installer.InstallerManager
import com.combo.core.runtime.resource.PluginResourcesManager
import com.combo.core.security.auth.AuthorizationManager
import com.combo.core.security.permission.PermissionLevel
import com.combo.core.security.permission.RequiresPermission
import com.combo.core.security.permission.checkApiCaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext.startKoin
import timber.log.Timber
import kotlin.reflect.jvm.javaMethod

/**
 * 插件管理器的初始化状态。
 * 初始化状态：
 * - [NOT_INITIALIZED]：未初始化。
 * - [INITIALIZING]：初始化中。
 * - [INITIALIZED]：已初始化。
 */
enum class InitState { NOT_INITIALIZED, INITIALIZING, INITIALIZED }

/**
 * 定义插件安装时的签名校验策略。
 * 校验策略：
 * - [Strict]：严格模式，只允许与宿主签名完全一致的插件。
 * - [UserGrant]：用户授权模式，当遇到未知签名时，通过全局的 `IAuthorizationHandler` 回调请求用户授权。
 * - [Insecure]：不安全模式，完全禁用签名校验。
 */
enum class ValidationStrategy {
    Strict,
    UserGrant,
    Insecure
}

/**
 * 插件框架核心管理器
 *
 * 这是一个单例对象，作为整个插件框架的唯一公共API入口。
 * 它本身不包含复杂的业务逻辑，而是将所有请求转发给内部具体的、职责单一的管理器。
 */
object PluginManager {

    private const val TAG = "PluginManager"

    // 内部上下文，持有所有状态和管理器
    private var frameworkContext: PluginFrameworkContext? = null

    // 公开暴露给外部的属性，从 frameworkContext 获取
    val initStateFlow: StateFlow<InitState>
        get() = requireContext().initState
    val loadedPluginsFlow: StateFlow<Map<String, LoadedPluginInfo>>
        get() = requireContext().loadedPlugins
    val pluginInstancesFlow: StateFlow<Map<String, IPluginEntryClass>>
        get() = requireContext().pluginInstances

    val isInitialized: Boolean
        get() = frameworkContext?.initState?.value == InitState.INITIALIZED

    val validationStrategy: ValidationStrategy
        get() = requireContext().validationStrategy

    val classLoadingPolicy: PluginClassLoadingPolicy
        get() = requireContext().classLoadingPolicy
    val registryAccessMode: RegistryAccessMode
        get() = requireContext().registryAccessMode

    val installerManager: InstallerManager
        get() = requireContext().installerManager
    val resourcesManager: PluginResourcesManager
        get() = requireContext().resourcesManager
    val proxyManager: ProxyManager
        get() = requireContext().proxyManager
    val authorizationManager: AuthorizationManager
        get() = requireContext().authorizationManager

    internal fun getClassIndex(): Map<String, String> = requireContext().classIndex

    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun requireContext(): PluginFrameworkContext {
        return frameworkContext ?: throw IllegalStateException("PluginManager has not been initialized.")
    }

    /**
     * 初始化插件管理器。
     *
     * 初始化插件管理器时，会启动一个后台协程，用于加载插件。
     * 插件加载完成后，会将插件的类索引和实例存储在内部状态中。
     *
     * @param context 应用上下文，用于访问插件资源和系统服务。
     * @param onSetup 可选的插件加载代码块，用于在初始化完成后加载插件。
     */
    @Synchronized
    fun initialize(context: Application) =
        initialize(context, PluginClassLoadingPolicy.ParentFirst, null)

    @Synchronized
    fun initialize(context: Application, onSetup: (suspend () -> Unit)?) =
        initialize(context, PluginClassLoadingPolicy.ParentFirst, onSetup)

    @Synchronized
    fun initialize(
        context: Application,
        classLoadingPolicy: PluginClassLoadingPolicy = PluginClassLoadingPolicy.ParentFirst,
        onSetup: (suspend () -> Unit)? = null,
    ) = initialize(context, classLoadingPolicy, RegistryAccessMode.READ_ONLY_FAIL_CLOSED, onSetup)

    @Synchronized
    fun initialize(
        context: Application,
        classLoadingPolicy: PluginClassLoadingPolicy,
        registryAccessMode: RegistryAccessMode,
        onSetup: (suspend () -> Unit)?,
    ) {
        if (frameworkContext != null && frameworkContext?.initState?.value != InitState.NOT_INITIALIZED) {
            Timber.Forest.tag(TAG).w("PluginManager 正在初始化或已完成，跳过重复操作。")
            return
        }

        frameworkContext = PluginFrameworkContext(context, classLoadingPolicy)
        requireContext().configureRegistryAccessMode(registryAccessMode)
        requireContext().initState.value = INITIALIZING

        try {
            Timber.Forest.tag(TAG).i("开始初始化 PluginManager 核心组件...")
            // Exact artifact loading never consults the legacy global registry.
            startKoin { androidContext(context) }
        } catch (e: Exception) {
            Timber.Forest.tag(TAG).e(e, "PluginManager 初始化失败: ${e.message}")
            requireContext().initState.value = NOT_INITIALIZED
            throw e
        }

        requireContext().initState.value = INITIALIZED
        Timber.Forest.tag(TAG).i("PluginManager 核心已就绪。")

        managerScope.launch {
            try {
                onSetup?.invoke()
                Timber.Forest.tag(TAG).i("宿主自定义的框架设置任务已完成。")
            } catch (e: Exception) {
                Timber.Forest.tag(TAG).e(e, "宿主自定义的框架设置任务执行失败。")
            }
        }
    }

    suspend fun awaitInitialization() {
        if (isInitialized) return
        initStateFlow.first { it == INITIALIZED }
    }

    /**
     * 设置插件安装时的签名验证策略。
     * API权限要求：[PermissionLevel.HOST] (硬性要求)
     */
    @RequiresPermission(PermissionLevel.HOST, hardFail = true)
    suspend fun setValidationStrategy(strategy: ValidationStrategy) {
        if (::setValidationStrategy.javaMethod?.checkApiCaller() == false) return
        requireContext().validationStrategy = strategy
        Timber.i("PluginManager: ValidationStrategy 已更新为: ${strategy::class.java.simpleName}")
    }

    // --- API 转发层 ---

    /**
     * 启动插件
     * API权限要求：[PermissionLevel.HOST]
     * @param pluginId 插件ID
     * @return 是否启动成功
     */
    @RequiresPermission(PermissionLevel.SELF)
    suspend fun launchPlugin(pluginId: String): Boolean {
        if (::launchPlugin.javaMethod?.checkApiCaller(targetPluginId = pluginId) == false) {
            Timber.w("权限不足：插件启动操作被拒绝 [pluginId: $pluginId]")
            return false
        }
        return requireContext().lifecycleManager.launchPlugin(pluginId)
    }

    /** Load only the exact installed artifact authorized for this process. Never selects plugins.xml. */
    @RequiresPermission(PermissionLevel.HOST, hardFail = true)
    suspend fun launchArtifact(pluginInfo: PluginInfo, expectedSha256: String): Boolean {
        if (::launchArtifact.javaMethod?.checkApiCaller() == false) return false
        return requireContext().lifecycleManager.launchArtifact(pluginInfo, expectedSha256)
    }

    /**
     * 卸载插件
     * API权限要求：[PermissionLevel.HOST]
     * @param pluginId 插件ID
     */
    @RequiresPermission(PermissionLevel.SELF)
    suspend fun unloadPlugin(pluginId: String) {
        if (::unloadPlugin.javaMethod?.checkApiCaller(targetPluginId = pluginId) == false) {
            Timber.w("权限不足：插件卸载操作被拒绝 [pluginId: $pluginId]")
            return
        }
        requireContext().lifecycleManager.unloadPlugin(pluginId)
    }

    /**
     * 加载所有已启用插件
     * API权限要求：[PermissionLevel.HOST]
     * @return 成功加载的插件数量
     */
    @RequiresPermission(PermissionLevel.HOST)
    suspend fun loadEnabledPlugins(): Int {
        if (::loadEnabledPlugins.javaMethod?.checkApiCaller() == false) {
            Timber.w("权限不足：插件加载操作被拒绝")
            return 0
        }
        return requireContext().lifecycleManager.loadEnabledPlugins()
    }

    /**
     * 获取插件接口
     * @param interfaceClass 接口类
     * @param className 类名
     * @return 接口实例
     */
    fun <T : Any> getInterface(interfaceClass: Class<T>, className: String): T? {
        try {
            val targetPluginId = requireContext().classIndex[className]
            if (targetPluginId == null) {
                getInterfaceFromHost(interfaceClass, className)?.let { return it }
                Timber.Forest.tag(TAG).w("无法找到类 '$className' 的宿主插件，类索引中不存在该条目。")
                return null
            }

            val loadedPlugin = requireContext().loadedPlugins.value[targetPluginId]
            if (loadedPlugin == null) {
                Timber.Forest.tag(TAG)
                    .e("类索引不一致：类 '$className' 指向插件 '$targetPluginId'，但该插件当前未加载。")
                return null
            }

            Timber.Forest.tag(TAG)
                .d("正在从插件 '$targetPluginId' 中获取接口 '${interfaceClass.simpleName}' 的实现 '$className'...")
            return loadedPlugin.classLoader.getInterface(interfaceClass, className)

        } catch (e: Exception) {
            Timber.Forest.tag(TAG).e(e, "通过 PluginQueryManager 获取接口 '$className' 的实例时发生未知错误。")
            return null
        }
    }

    /**
     * 获取插件实例
     * @param pluginId 插件ID
     * @return 插件实例
     */
    fun getPluginInstance(pluginId: String): IPluginEntryClass? {
        return requireContext().pluginInstances.value[pluginId]
    }

    /**
     * 获取插件信息
     * @param pluginId 插件ID
     * @return 插件信息
     */
    fun getPluginInfo(pluginId: String): LoadedPluginInfo? {
        return requireContext().loadedPlugins.value[pluginId]
    }

    /**
     * 获取所有已加载插件实例
     * @return 所有已加载插件实例
     */
    fun getAllPluginInstances(): Map<String, IPluginEntryClass> {
        return requireContext().pluginInstances.value
    }

    /**
     * 获取所有已安装插件
     * @return 所有已安装插件
     */
    fun getAllInstallPlugins(): List<PluginInfo> {
        return requireContext().xmlManager.getAllPlugins()
    }

    /**
     * 获取插件依赖者链
     * @param pluginId 插件ID
     * @return 插件依赖者链
     */
    fun getPluginDependentsChain(pluginId: String): List<String> {
        return requireContext().dependencyManager.findDependentsRecursive(pluginId)
    }

    /**
     * 获取插件依赖链
     * @param pluginId 插件ID
     * @return 插件依赖链
     */
    fun getPluginDependenciesChain(pluginId: String): List<String> {
        return requireContext().dependencyManager.findDependenciesRecursive(pluginId)
    }

    /**
     * 设置插件启用状态
     * API权限要求：[PermissionLevel.HOST]
     * @param pluginId 插件ID
     * @param enabled 插件启用状态
     * @return 是否设置成功
     */
    @RequiresPermission(PermissionLevel.HOST)
    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean): Boolean {
        registryAccessMode.requireMutable("setPluginEnabled")
        if (::setPluginEnabled.javaMethod?.checkApiCaller(targetPluginId = pluginId) == false) {
            Timber.w("权限不足：插件状态设置操作被拒绝 [pluginId: $pluginId]")
            return false
        }
        return try {
            val pluginInfo = requireContext().xmlManager.getPluginById(pluginId) ?: return false
            if (pluginInfo.enabled == enabled) return true
            val updatedPluginInfo = pluginInfo.copy(enabled = enabled)
            requireContext().xmlManager.updatePlugin(updatedPluginInfo)
            requireContext().xmlManager.flushToDisk()
            true
        } catch (e: Exception) {
            Timber.Forest.tag(TAG).e(e, "设置插件 '$pluginId' 状态时出错。")
            false
        }
    }

    /**
     * 从宿主应用获取接口实例
     */
    private fun <T : Any> getInterfaceFromHost(interfaceClass: Class<T>, className: String): T? {
        return try {
            val clazz = requireContext().application.classLoader.loadClass(className)
            val instance = clazz.getDeclaredConstructor().newInstance()
            if (interfaceClass.isInstance(instance)) {
                @Suppress("UNCHECKED_CAST")
                instance as T
            } else {
                Timber.Forest.tag(TAG)
                    .e("类型不匹配：宿主类 '$className' 未实现接口 '${interfaceClass.simpleName}'")
                null
            }
        } catch (_: ClassNotFoundException) {
            null
        } catch (e: Throwable) {
            Timber.Forest.tag(TAG).e(e, "从宿主实例化 '$className' 时发生错误。")
            null
        }
    }
}