rootProject.name = "wgshare-revanced-patches"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        // The ReVanced patches gradle plugin is published to GitHub Packages and requires auth.
        // Provide githubPackagesUsername/githubPackagesPassword (a GH token with read:packages) via
        // gradle.properties or ORG_GRADLE_PROJECT_* env vars.
        maven {
            name = "githubPackages"
            url = uri("https://maven.pkg.github.com/revanced/revanced-patches-gradle-plugin")
            credentials(PasswordCredentials::class)
        }
        maven { url = uri("https://jitpack.io") }
    }
}

plugins {
    id("app.revanced.patches") version "1.0.0-dev.10"
}

settings {
    extensions {
        defaultNamespace = "app.revanced.extension"
        // Absolute path required so extensions in subfolders resolve the shared ProGuard config.
        proguardFiles(rootProject.projectDir.resolve("extensions/proguard-rules.pro").toString())
    }
}
