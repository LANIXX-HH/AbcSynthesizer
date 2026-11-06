plugins {
    id("com.android.application")
}

android {
    namespace = "de.lanixx.chorstimme"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.lanixx.chorstimme"
        minSdk = 26
        targetSdk = 35
        // set by the release workflow from the git tag (v1.2.3) and the run number
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "dev"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        // fixed key in the repo, so every build can be installed over the previous one
        // (fine for personal use; take your own secret key for a store release)
        create("chorstimme") {
            storeFile = file("chorstimme.keystore")
            storePassword = "chorstimme"
            keyAlias = "chorstimme"
            keyPassword = "chorstimme"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("chorstimme")
        }
        debug {
            signingConfig = signingConfigs.getByName("chorstimme")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    // PDF parsing (Apache PDFBox port for Android, Apache 2.0)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
