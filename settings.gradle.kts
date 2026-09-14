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
        google(); mavenCentral()
        // JieLi's firmware-update AARs, kept in the repo — the ring is a JieLi AC632N and its OTA
        // is JieLi's authenticated RCSP protocol, so the update path is their own SDK. See
        // vitals/libs and PROTOCOL.md's "Updating the firmware".
        flatDir { dirs("vitals/libs") }
    }
}
rootProject.name = "R99 Ring Companion"
include(":app")
include(":vitals")
