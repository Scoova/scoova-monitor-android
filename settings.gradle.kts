// Standalone settings file so this Android library can be built and
// published from its own directory in CI without a parent project.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        // Pin AGP + Kotlin so the module's `plugins {}` block doesn't have
        // to declare versions inline. Bump these in lockstep with the
        // versions referenced in build.gradle.kts.
        id("com.android.library") version "8.5.0"
        id("org.jetbrains.kotlin.android") version "1.9.22"
        id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sdk-android"
