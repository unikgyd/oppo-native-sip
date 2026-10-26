pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    resolutionStrategy { eachPlugin {
        if (requested.id.id == "org.jetbrains.kotlin.android") useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
    } }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven("https://api.xposed.info/") { content { includeGroup("de.robv.android.xposed") } }
        maven("https://download.linphone.org/releases/maven_repository") {
            content { includeGroup("org.linphone") }
        }
    }
}
rootProject.name = "MySIP"
include(":app")
