pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "planner"

include(":core:scheduler")
// `-PcoreOnly` builds just the pure-Kotlin module, for machines without the Android SDK.
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
}
