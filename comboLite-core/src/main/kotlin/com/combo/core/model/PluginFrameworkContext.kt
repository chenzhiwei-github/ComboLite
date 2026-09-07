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

package com.combo.core.model

import android.app.Application
import com.combo.core.api.IPluginEntryClass
import com.combo.core.proxy.ProxyManager
import com.combo.core.runtime.RegistryAccessMode
import com.combo.core.runtime.loader.PluginClassLoadingPolicy
import com.combo.core.runtime.InitState
import com.combo.core.runtime.ValidationStrategy
import com.combo.core.runtime.installer.InstallerManager
import com.combo.core.runtime.installer.XmlManager
import com.combo.core.runtime.lifecycle.PluginLifecycleManager
import com.combo.core.runtime.loader.DependencyManager
import com.combo.core.runtime.loader.IPluginStateProvider
import com.combo.core.runtime.loader.PluginClassLoadingPolicy
import com.combo.core.runtime.resource.PluginResourcesManager
import com.combo.core.security.auth.AuthorizationManager
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件框架内部上下文
 *
 * 持有所有共享状态和核心管理器的实例，作为内部组件的依赖中心，
 * 以解耦 PluginManager 和各个具体的功能管理器。
 */
internal data class PluginFrameworkContext(
    val application: Application,
    val classLoadingPolicy: PluginClassLoadingPolicy = PluginClassLoadingPolicy.ParentFirst,
) {
    // 共享状态
    val loadedPlugins = MutableStateFlow<Map<String, LoadedPluginInfo>>(emptyMap())
    val pluginInstances = MutableStateFlow<Map<String, IPluginEntryClass>>(emptyMap())
    val classIndex = ConcurrentHashMap<String, String>()
    val initState = MutableStateFlow(InitState.NOT_INITIALIZED)
    var validationStrategy: ValidationStrategy = ValidationStrategy.Strict

    // 核心管理器实例
    private var registryModeConfigured = false
    var registryAccessMode = RegistryAccessMode.READ_ONLY_FAIL_CLOSED
        private set
    internal fun configureRegistryAccessMode(mode: RegistryAccessMode) {
        check(!registryModeConfigured) { "Registry mode is already frozen" }
        registryAccessMode = mode
        registryModeConfigured = true
    }
    val xmlManager: XmlManager by lazy {
        check(registryModeConfigured)
        XmlManager(application, registryAccessMode)
    }
    val installerManager: InstallerManager by lazy { InstallerManager(application, xmlManager) }
    internal fun initializeRegistryManagers() { installerManager }
    val resourcesManager: PluginResourcesManager = PluginResourcesManager(application)
    val proxyManager: ProxyManager = ProxyManager(application)
    val lifecycleManager: PluginLifecycleManager = PluginLifecycleManager(this)
    val authorizationManager: AuthorizationManager = AuthorizationManager(application)

    // 依赖于其他管理器的管理器
    var dependencyManager: DependencyManager

    init {
        val stateProvider = object : IPluginStateProvider {
            override fun getClassIndex(): Map<String, String> = classIndex
            override fun getLoadedPlugins(): Map<String, LoadedPluginInfo> = loadedPlugins.value
        }
        dependencyManager = DependencyManager(stateProvider)
    }
}