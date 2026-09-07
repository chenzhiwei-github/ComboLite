pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    resolutionStrategy.eachPlugin {
        if (requested.id.id == "org.jetbrains.kotlin.plugin.parcelize")
            useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name = "ComboLite-core-fork"
include(":comboLite-core")
project(":comboLite-core").projectDir = file("../comboLite-core")
project(":comboLite-core").buildFileName = "build-core.gradle.kts"
