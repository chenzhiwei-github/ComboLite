// Reproducible source-only profile; excludes upstream samples and release signing plugins.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.parcelize")
    `maven-publish`
}
// Preserve the frozen Kotlin internal JVM suffix as well as ordinary public API descriptors.
kotlin { compilerOptions { moduleName.set("comboLite-core_release") } }
group = "io.github.lnzz123"
version = "2.0.2-xj.8"
android {
    namespace = "com.combo.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }



    buildFeatures { compose = true }
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    // 最小化依赖
    implementation(libs.androidx.core)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.android)

    // Compose核心（用于@Composable注解）
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // 序列化支持
    implementation(libs.kotlinx.serialization.json)

    // Kotlin反射库
    implementation(libs.kotlin.reflect)

    // Koin依赖注入（用于插件模块管理）
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Dex lib2 库
    implementation(libs.dexlib2)

    implementation(libs.coil.kt)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.kt.compose)
}


afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.github.lnzz123"
                artifactId = "combolite-core"
                version = "2.0.2-xj.8"
                pom {
                    name.set("ComboLite Core GameHub fork")
                    description.set("Source-built immutable artifact loading fork reconstructed from upstream f4d4524")
                    url.set("https://github.com/lnzz123/ComboLite")
                    licenses { license { name.set("Apache License 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } }
                }
            }
        }
        repositories { maven { name = "forkArchive"; url = uri(rootProject.layout.buildDirectory.dir("m2repo")) } }
    }
}
