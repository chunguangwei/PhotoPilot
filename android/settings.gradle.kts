pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.application" || requested.id.id == "com.android.library") {
                useModule("com.android.tools.build:gradle:${requested.version ?: "9.0.1"}")
            }
        }
    }
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

rootProject.name = "PhotoPilot"

include(
    ":app",
    ":feature-frames",
    ":feature-library",
    ":feature-sources",
    ":feature-settings",
    ":feature-playback",
    ":domain-source",
    ":domain-import",
    ":domain-frame",
    ":domain-playback",
    ":domain-weather",
    ":domain-storage",
    ":data-asset",
    ":core-render",
    ":core-ui",
)
