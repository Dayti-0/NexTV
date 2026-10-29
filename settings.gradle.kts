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
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NexTV"

// Logique métier indépendante d'Android (sources, réseau, M3U, XMLTV).
// Build Gradle autonome : `./gradlew -p core test` fonctionne sans SDK Android.
includeBuild("core")

include(":app")
