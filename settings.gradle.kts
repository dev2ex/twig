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
        maven("https://jitpack.io") // termux terminal-view/emulator
    }
}

rootProject.name = "Twig"
include(":app")
include(":core-fs")
include(":fs-local")
include(":fs-archive")
include(":fs-archive-rar")
include(":fs-network")
include(":fs-smb")
include(":fs-restic")
include(":fs-zstd")
include(":git-lite")
