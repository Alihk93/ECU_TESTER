plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alayed.ecutester"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alayed.ecutester"
        // minSdk 24 (Android 7.0) — confirmed fleet floor (docs/ANDROID_MIGRATION.md §2).
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-m1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // appliance build; enable + tune proguard later
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // OkHttp WebSocket client — the EcuSocket transport (docs/ANDROID_MIGRATION.md §5).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
