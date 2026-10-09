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
        // Android 7.0: java.time comes from the ThreeTen backport, as it only exists from Android 8.
        minSdk = 24
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // Releases are signed with the private key from the SIGNING_* environment variables, which CI
        // fills from GitHub Secrets. The key never lives in the repository. Without it, release
        // builds fall back to the debug key and cannot update an installed release.
        val keystore = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "havauyari"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: System.getenv("SIGNING_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
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
    // The app uses only the Android framework, the Kotlin standard library and the java.time
    // backport, so it can also be built without Google's Maven repository (see tools/offline-apk).
    // Without the time-zone database: the app only works with fixed UTC offsets.
    implementation(variantOf(libs.threetenbp) { classifier("no-tzdb") })
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
