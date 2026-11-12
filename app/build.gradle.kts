plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.titipong.photocap360"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.titipong.photocap360"
        minSdk = 26
        targetSdk = 35
        // CI builds: version 0.2.<run number> so every build installs over the previous one.
        val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        val gitSha = System.getenv("GITHUB_SHA")?.take(7) ?: "local"
        versionCode = ciRun ?: 1
        versionName = if (ciRun != null) "0.2.$ciRun" else "0.2.0-dev"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        // ONNX Runtime (AI depth) ships native code: phones only.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK from CI installs directly.
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
    }
}

dependencies {
    val camerax = "1.4.2"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // AI depth (Depth Anything V2 Small, downloaded on first use)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation("junit:junit:4.13.2")
}
