plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "uk.gorim.pillguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.gorim.pillguard"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "0.9.6"
    }

    signingConfigs {
        // Fixed sideload key kept in the (private) repo so every build upgrades over the last one.
        // It only proves "same author as the previous build"; it is not a Play Store key.
        create("sideload") {
            storeFile = file("pillguard.jks")
            storePassword = "pillguard-sideload"
            keyAlias = "pillguard"
            keyPassword = "pillguard-sideload"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.print:print:1.0.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
}
