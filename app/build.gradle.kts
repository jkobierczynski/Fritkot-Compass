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

    // Kept dependency-free (no AndroidX, no third-party libraries) for
    // everything except the one thing that genuinely needs a real library:
    // an interactive, pinch-zoomable OpenStreetMap view. Hand-rolling a
    // slippy map (tile loading/caching, projection math, gesture handling)
    // isn't a reasonable thing to build from scratch, so this uses osmdroid
    // — a mature, actively maintained, Apache-2.0-licensed OSM map view for
    // Android. See MapActivity.kt.
}

dependencies {
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
