plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ashwin.visionai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ashwin.visionai"
        // 29+ is required for WifiP2pConfig.Builder (fixed network name/
        // password), which is what lets the glasses reconnect instantly
        // on every launch instead of needing a fresh QR scan each time.
        // Your phone is Android 14, so this is not a real limitation.
        minSdk = 29
        targetSdk = 34       // app declares Android 14 behaviour; device Android 14 = API 34
        versionCode = 1
        versionName = "0.1.0"

        // MediaPipe/TensorFlow Lite bundle native libraries for 4 CPU
        // architectures by default. Almost every real phone (including
        // this one, on Android 14) is arm64-v8a, so we drop the other
        // three to keep the APK well under Telegram's 50MB bot upload
        // limit. Remove this block only if you need to support very old
        // 32-bit or x86 (emulator) devices.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        getByName("debug") {
            // A committed, stable keystore (app/debug.keystore) instead of
            // the auto-generated one at ~/.android/debug.keystore. GitHub
            // Actions runners start empty every run, so without this, every
            // CI build would get a brand-new random debug key and Android
            // would refuse to install-over the previous APK (forcing an
            // uninstall every time). Fine for personal sideloading; this
            // key is NOT secret since it's just the standard debug identity.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // The currency model is >1MB and must not be compressed inside the APK,
    // or TFLite's mmap-based loader fails at runtime.
    androidResources {
        noCompress += listOf("tflite")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Networking to the glasses' built-in HTTP server and to Gemini
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Your currency model: uses the exact same API the manufacturer app
    // uses for it (ML Kit Custom Image Labeling, confirmed from their
    // decompiled code: InputImage.fromBitmap + ImageLabeler.process),
    // not a raw TFLite Interpreter. The model's own embedded metadata
    // supplies its correct preprocessing and label names, which a manual
    // Interpreter approach would otherwise have to guess at.
    implementation("com.google.mlkit:image-labeling-custom:17.0.3")
    // No separate org.tensorflow:tensorflow-lite dependency needed: ML Kit's
    // custom image labeling (currency) and MediaPipe tasks-vision (walking
    // detector) each bundle their own TFLite runtime internally. Adding it
    // separately was dead weight pushing the APK over Telegram's 50MB cap.

    // Google's official, open MediaPipe Tasks Vision (object detection for walking mode)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // Generates the Wi-Fi QR code the glasses' camera scans to join the
    // phone's network - pure Java, works fully offline, no Google Play
    // Services or internet needed at runtime.
    implementation("com.google.zxing:core:3.5.3")

    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
}
