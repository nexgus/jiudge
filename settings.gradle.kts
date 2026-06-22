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
        // BRouter (org.btools) is not on Maven Central - only GitHub Packages (token-gated).
        // JitPack builds it from the public GitHub repo with no auth. Scoped to the brouter
        // group so it is not consulted for other dependencies.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.abrensch.*") }
        }
    }
}

rootProject.name = "jiudge"
include(":app")
