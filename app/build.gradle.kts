plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "de.matze.einkauf"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.matze.einkauf"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    // Fester Schlüssel im Repo, damit sich neue Versionen einfach über die alte installieren lassen.
    signingConfigs {
        create("release") {
            storeFile = file("einkauf.keystore")
            storePassword = "einkauf-app"
            keyAlias = "einkauf"
            keyPassword = "einkauf-app"
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
