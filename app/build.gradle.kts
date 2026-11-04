import java.util.Properties

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
        versionCode = 2
        versionName = "1.0"
        buildConfigField("String", "BASE_URL", "\"http://127.0.0.1:8000\"")
        buildConfigField("String", "VENUE_ID", "\"venue_demo_museum\"")
    }

    buildFeatures { buildConfig = true }

    // Release signing from keystore.properties (gitignored) when present;
    // debug signing otherwise so the build never breaks on a fresh clone.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
    }
    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("mira") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // USB dev: `adb reverse tcp:8000 tcp:8000` reaches the host backend.
            buildConfigField("String", "BASE_URL", "\"http://127.0.0.1:8000\"")
        }
        release {
            isMinifyEnabled = false
            // Judges: the public Fly.io backend, no cable involved.
            buildConfigField(
                "String", "BASE_URL",
                "\"https://mira-ai-museum-guide.fly.dev\""
            )
            signingConfig = signingConfigs.findByName("mira")
                ?: signingConfigs.getByName("debug")
        }
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
    testImplementation("junit:junit:4.13.2")
}
