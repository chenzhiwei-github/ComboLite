# xiaoji fork 变更说明

基线：上游 master `f4d4524`（combolite-core 2.0.2 / aar2apk 1.1.1），分支 `xiaoji/child-first`。
当前版本：**combolite-core `2.0.2-xj.2`**、**aar2apk `1.1.2-xj.2`**（坐标沿用 `io.github.lnzz123`，仅版本加 `-xj` 后缀）。

发布方式：本地 maven（正式交付方式待定）

```bash
./gradlew -p build-logic :aar2apk:publishToMavenLocal
./gradlew :comboLite-core:publishToMavenLocal
```

---

## comboLite-core（2.0.2-xj.2）

### 1. 受控 child-first 类加载策略

- 新增 `runtime/loader/PluginClassLoadingPolicy.kt`：
  - `ParentFirst`（默认）：与上游逐字节一致的双亲委派，零行为变化。
  - `ChildFirst(forceParentPrefixes, forceParentClasses, childFirstPrefixes)`：
    - `forceParentPrefixes` 强制走宿主的包前缀白名单，默认 `java./javax./android./androidx./dalvik./kotlin./kotlinx./com.combo.`；
    - `forceParentClasses` 精确类名级强制宿主（用于跨 loader 强转的契约接口）；
    - `childFirstPrefixes` 非空时仅命中前缀的类才 child-first（收窄开关）。
- `PluginClassLoader` 重写 `loadClass`：已加载缓存 → 白名单走宿主 → 本插件 dex（`findClassLocally`）→ 回退双亲委派（宿主未命中最终落回 `findClass` 的跨插件查找兜底）。
- 策略注入链：`PluginManager.initialize(context, classLoadingPolicy, onSetup)` → `PluginFrameworkContext.classLoadingPolicy` → `PluginLifecycleManager.loadPlugin` → `PluginClassLoader`；`PluginManager.classLoadingPolicy` 只读暴露。
- `PluginLifecycleManager.loadClassIndexForPlugin` 增加类索引冲突告警（同名类被不同插件覆盖时 error 日志）。

### 2. consumer-rules.pro 收敛（宿主可 R8 的前提）

删除所有全局通配 keep（`@Serializable` 全局、Composable 全局、`org.koin.**`、`kotlin.Metadata` 全局、Parcelable/enum 全局等），收窄到 `com.combo.core.**` 命名空间与框架契约类。引入方（宿主）不再被污染，可自由混淆业务与三方库。

---

## build-logic/aar2apk（1.1.2-xj.2）

### xj.1 已有

- DSL：`minifyRelease` / `minApi` / `proguardFiles` / `classpathFiles`。
- `DexProcessor.minifyToDex`：`java -cp <build-tools>/lib/d8.jar com.android.tools.r8.R8 --release --pg-compat` 调 R8，classpath 支持 AAR 自动解包，mapping 输出到产物目录。

### xj.2 新增

1. **DSL 扩展**（`PackagingOptions`）：
   - `hostProvidedPatterns: ListProperty<String>`：命中的依赖不进插件产物、转为 R8 `--classpath`。支持 `group:module`、`group:*`（`*` 通配）、`:project:path`（本地模块）。
   - `hostProvidedClassPrefixes: ListProperty<String>`：产物防呆校验用包前缀。
   - `extraProgramJars: ConfigurableFileCollection`：artifactView 收不到的本地文件依赖（jar/aar，aar 自动解 classes.jar + libs/*.jar），进 program 参与混淆。
   - `extraRPackages: ListProperty<String>`：aapt2 link `--extra-packages`，为依赖模块生成同 id 的 R.java 并参与编译。
2. **依赖收集重写**（修复上游两缺陷）：
   - 远端收集不再限定 `artifactType=aar`，改为无 attribute 的 lenient artifactView + `resolvedArtifacts`，**纯 jar 型 maven 依赖、`artifact { type = "aar" }` 声明式产物均可收集**；按 `hostProvidedPatterns` 分流 program / hostProvidedClasspath。
   - **修复上游 AAR 解压路径 bug**：`AarExtractor.extract(aar, outDir)` 实际解压到 `outDir/extracted/`，上游在 `outDir/` 下找 `classes.jar` 永远落空 → 远端 AAR 的 classes/assets/jni 从未进过产物。现按返回值取路径，并额外收集 AAR 内 `libs/*.jar` 与 `proguard.txt`（consumer rules 并入 R8 `--pg-conf`）。
   - 本地 project 依赖（android-classes-jar 视图）同样按 project path 分流。
3. **多 dex**：R8/D8 输出目录下全部 `classes*.dex` 依序打入 APK（上游只装 classes.dex，方法数超 64K 会静默丢 dex）。D8 路径的 `--min-api` 参数化（原硬编码 21）。
4. **META-INF/services 合并**：扫描全部 program jar 的 `META-INF/services/*`，合并去重写入 APK，并自动为 impl 类生成 `-keep class X { <init>(); }` 注入 R8（ServiceLoader 按名实例化不被混淆破坏）。
5. **产物防呆校验**：转换尾声解析产物 dex 全部 class_def（内置 dex 解析器，零新依赖），任一类名命中 `hostProvidedClassPrefixes` → 构建失败并列出前 50 个（保证"宿主提供的库绝不混进插件 dex"）。
6. **依赖清单 sidecar**：输出 `<插件>-<buildType>-deps-program.txt` / `-deps-host-provided.txt`（坐标 -> 文件路径），供调试与宿主精准 keep 生成（TraceReferences target 输入）。
7. **JVM 目标 21 → 17**：插件字节码兼容 JDK 17 的 Gradle daemon（Android Studio JBR 21 与终端 JDK 17 均可加载）。

### 兼容性

- 所有新 DSL 均有空默认值；不配置时（如仓内 sample-plugin）行为与上游一致，已用 sample 全量 debug/release 打包冒烟验证。
- `ConvertAarToApkTask` 任务属性 `remoteDependencyAars` 更名为 `remoteProgramArtifacts`（语义变化：aar+jar 混合、仅 program 侧）。
