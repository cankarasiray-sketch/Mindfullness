plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// The commit count gives every build of a commit the same, ever-increasing versionCode, whether it
// comes from CI or from tools/offline-apk, so each new APK installs over the previous one.
val buildNumber: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

android {
    namespace = "com.mindfullness.weather"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mindfullness.weather"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // A fixed key keeps the signature stable between builds, so updates install without
        // uninstalling first. Override it with SIGNING_* environment variables for a private key.
        create("app") {
            storeFile = System.getenv("SIGNING_STORE_FILE")?.let { file(it) } ?: file("signing/havauyari.jks")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "havauyari"
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "havauyari"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: "havauyari"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("app")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("app")
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
    // The app uses only the Android framework and the Kotlin standard library, so it can also be
    // built without Google's Maven repository (see tools/offline-apk).
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
