plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

base {
    archivesName.set("AdhanClockOeka")
}

android {
    namespace = "com.oeka.adhanclock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.oeka.adhanclock"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "8.2"
    }

    // Fester Signaturschlüssel: jede neue Version lässt sich über die alte installieren
    // (egal ob in Android Studio oder auf GitHub gebaut). Nur für den privaten Gebrauch –
    // für eine Veröffentlichung im Play Store einen eigenen, geheimen Schlüssel verwenden.
    signingConfigs {
        create("adhanclock") {
            storeFile = file("adhanclock.keystore")
            storePassword = "adhanclock2026"
            keyAlias = "adhanclock"
            keyPassword = "adhanclock2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("adhanclock")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("adhanclock")
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
    // Bewusst keine Zusatzbibliotheken: nur Android-Framework + Kotlin-Standardbibliothek.
}
