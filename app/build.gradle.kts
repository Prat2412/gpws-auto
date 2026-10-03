import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The release key lives outside the repo (../gpws-auto-signing), never on GitHub.
// Without it, a release build comes out unsigned; debug builds don't need it.
val signingDir = rootProject.file("../gpws-auto-signing")
val signingProps = File(signingDir, "keystore.properties")

android {
    namespace = "dev.gpws.auto"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.gpws.auto"
        minSdk = 26
        targetSdk = 34
        // Bump both for every GitHub release: the in-app updater compares the release tag to versionName.
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (signingProps.exists()) {
            create("release") {
                val p = Properties().apply { signingProps.inputStream().use { load(it) } }
                storeFile = File(signingDir, p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingProps.exists()) signingConfig = signingConfigs.getByName("release")
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
