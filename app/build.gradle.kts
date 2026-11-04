plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.mira.guide"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.mira.guide"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // 127.0.0.1 + `adb reverse tcp:8000 tcp:8000` reaches the host backend
        // over USB (emulator and phone alike). For a standalone demo, replace
        // with the host LAN IP (e.g. http://192.168.1.20:8000) before building.
        buildConfigField("String", "BASE_URL", "\"http://127.0.0.1:8000\"")
        buildConfigField("String", "VENUE_ID", "\"venue_demo_museum\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.alphacephei:vosk-android:0.3.47")
}
