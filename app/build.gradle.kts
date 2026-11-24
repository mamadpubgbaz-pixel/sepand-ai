plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.sepand.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.sepand.app"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("fixed") {
            // A fixed key so every new build can be installed over the old one (keeps your data).
            storeFile = rootProject.file("sepand-debug.keystore")
            storePassword = "sepandai"
            keyAlias = "sepand"
            keyPassword = "sepandai"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fixed") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // llama-server is shipped as libllama_cpu.so / libllama_gpu.so and started as a process,
    // so the libraries must be extracted to disk.
    packaging {
        jniLibs { useLegacyPackaging = true }
        // The PDF library brings BouncyCastle twice; keep the first copy of duplicate metadata files.
        resources { pickFirsts += setOf("META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST") }
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
