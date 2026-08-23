plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "be.fritkot.compass"
    compileSdk = 34

    defaultConfig {
        applicationId = "be.fritkot.compass"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // A fixed, checked-in debug keystore (see debug.keystore at the repo
    // root) so every CI build is signed with the SAME key. Without this,
    // each GitHub Actions run would generate a random debug key and a
    // newer APK would fail to install as an "update" over an older one.
    // This is fine for direct-download distribution; it is NOT a secret
    // and should not be used to sign anything submitted to Google Play.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
    }

    // No AndroidX, no third-party libraries: keeps dependency resolution
    // minimal and the app self-contained (only the Android platform SDK
    // and the Kotlin standard library are required).
}
