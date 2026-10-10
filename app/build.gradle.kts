plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fastjourney.smsforwarder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fastjourney.smsforwarder"
        minSdk = 26
        targetSdk = 34
        versionCode = 21
        versionName = "1.8.6"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "../release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "smsforwarder2024"
            keyAlias = System.getenv("KEY_ALIAS") ?: "sms-forwarder"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "smsforwarder2024"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
