plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val appVersionName = (project.findProperty("verName") as String?) ?: "0.1.0"
val appVersionCode = (project.findProperty("verCode") as String?)?.toInt() ?: 1
val releaseStore = (System.getenv("KEYSTORE") ?: project.findProperty("keystore") as String?)?.let(::file)

android {
    namespace = "dev.alice.wgshare.xposed"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.alice.wgshare.xposed"
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
            // Must match :app so the CLIPBOARD_PUSH signature permission is granted.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Xposed framework API is provided by LSPosed at runtime, so compile-only.
    compileOnly(libs.xposed.api)
    implementation(libs.core.ktx)
}
