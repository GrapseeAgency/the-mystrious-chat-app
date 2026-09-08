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
include(":domain")
include(":data")
include(":feature-chat")
include(":feature-calls")
include(":feature-stories")
include(":feature-hub")
include(":feature-settings")
