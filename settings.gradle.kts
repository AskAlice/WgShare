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

// The ReVanced patches live in a self-contained Gradle build under revanced/ (its own settings +
// ReVanced patches gradle plugin, which needs GitHub Packages auth). It is intentionally NOT part
// of this app build.
rootProject.name = "WgShare"
include(":app")
