plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pwf.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pwf.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
    }

    buildTypes {
        debug {
            // Distinct package (and so distinct WebView renderer process names)
            // so a debug build can sit alongside a release one.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            // Sideloaded builds only; swap in a real key before distributing.
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

    androidResources {
        // The runtime is already compressed; storing it flat lets the asset
        // loader stream it and keeps wasm compilation off the decompressor.
        noCompress.addAll(listOf("wasm", "whl", "zip", "pyxapp"))
    }

    packaging {
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    // Pyodide ships its core distribution only as tar.bz2, and the platform has
    // no bzip2 decoder. Needed to install a runtime onto the device.
    implementation("org.apache.commons:commons-compress:1.27.1")
}
