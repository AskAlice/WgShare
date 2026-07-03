plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Version + signing are driven by Gradle properties / env so CI and scripts/build-release.sh stay
// the single source of truth. Falls back to dev defaults + debug signing for plain local builds.
val appVersionName = (project.findProperty("verName") as String?) ?: "0.1.0"
val appVersionCode = (project.findProperty("verCode") as String?)?.toInt() ?: 1
val releaseStore = (System.getenv("KEYSTORE") ?: project.findProperty("keystore") as String?)?.let(::file)

android {
    namespace = "dev.alice.wgshare"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.alice.wgshare"
        minSdk = 35
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = System.getenv("KEYSTORE_PASS")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASS")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same key as the ReVanced-patched SwiftKey/KDE Connect, so the signature-level
            // clipboard permission is honoured across all APKs.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)
    debugImplementation(libs.compose.ui.tooling)
}
