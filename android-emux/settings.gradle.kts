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
        // Termux native terminal emulator library (libtermux) + termux-api
        maven("https://packagecloud.io/termux/package/maven")
        maven("https://www.jitpack.io")
    }
}

rootProject.name = "EmuX"
include(":app")
