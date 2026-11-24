plugins {
    id("com.android.application")
}

android {
    namespace = "com.booktalk.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.booktalk.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.5"
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

    androidResources {
        noCompress += listOf("js", "html")
    }
}

dependencies {
    // Align Kotlin stdlib versions pulled in by AndroidX (avoids duplicate-class errors)
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.22"))
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
}
