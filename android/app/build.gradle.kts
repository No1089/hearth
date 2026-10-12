import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing: android/keystore.properties (gitignored) with storeFile (relative to android/),
// storePassword, keyAlias, keyPassword. A device-owner app can only ever be updated by the key it
// was installed with, so that keystore must be backed up — see README "Release signing".
val keystoreProps: Properties? = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}
val hasKeystore = keystoreProps != null

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

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
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

// Without the keystore a release build would silently produce an unsigned APK that can't be
// installed over the provisioned app; fail early with the fix instead.
tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        if (!hasKeystore) throw GradleException(
            "Release signing needs android/keystore.properties (storeFile, storePassword, keyAlias, " +
                "keyPassword) — see android/README.md, \"Release signing\". Use assembleDebug for a debug build.")
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
