plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.xrhighlight"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.xrhighlight"
        // Android XR devices run Android 14+.
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Jetpack XR (immersive: headsets + wired XR glasses such as XREAL Aura)
    implementation("androidx.xr.runtime:runtime:1.1.0-alpha03")
    implementation("androidx.xr.scenecore:scenecore:1.1.0-alpha02")
    implementation("androidx.xr.arcore:arcore:1.1.0-alpha02")

    // Camera (world-facing camera is exposed through the normal Camera2 / CameraX APIs)
    val camerax = "1.7.0"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // UI
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
