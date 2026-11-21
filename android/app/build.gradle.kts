plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cbnu.nabi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cbnu.nabi"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        debug {
            // Emulator: 10.0.2.2 is the host PC's 127.0.0.1.
            // Real phone over USB: run `adb reverse tcp:8000 tcp:8000` and use http://127.0.0.1:8000
            buildConfigField("String", "DEFAULT_BASE_URL", "\"http://10.0.2.2:8000\"")
        }
        release {
            isMinifyEnabled = false
            // Production must sit behind the TLS reverse proxy (see the root README).
            buildConfigField("String", "DEFAULT_BASE_URL", "\"https://api.example.com\"")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Health Connect: sleep sessions (auto-filled diary) and exercise sessions (previous day's habits).
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // 산책 page: OpenStreetMap tiles (no API key needed).
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}
