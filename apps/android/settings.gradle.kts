// Pulse Android — module registry.
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
rootProject.name = "Pulse"

include(":app")
include(":core")
include(":protocol")
include(":domain")
include(":data")
include(":ui")
include(":feature-chat")
include(":feature-stories")
include(":feature-calls")
include(":feature-voice")
include(":feature-hub")
include(":feature-settings")
