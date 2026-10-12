plugins {
    id("com.android.application")
}

android {
    namespace = "dev.homekiosk"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.homekiosk"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Pinned to versions that still compile against android-35 (newer ones require compileSdk 36).
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")

    testImplementation("junit:junit:4.13.2")
}
