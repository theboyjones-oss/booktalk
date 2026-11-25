plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.booktalk.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.booktalk.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "2.4"
        // On-device Kokoro voices need 64-bit ARM (all recent phones)
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            storeFile = file("booktalk-release.jks")
            // Sideload key with Android's standard public debug password (not a secret)
            storePassword = "android"
            keyAlias = "booktalk"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    androidResources {
        noCompress += listOf("js", "html")
    }
}

dependencies {
    // Align Kotlin stdlib versions pulled in by AndroidX (avoids duplicate-class errors)
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.0.21"))
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
    // Unpacks the Kokoro voice pack (.tar.bz2) on the phone
    implementation("org.apache.commons:commons-compress:1.21")
}
