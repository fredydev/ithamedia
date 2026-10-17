plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ithamedia.pdfsigner"
    compileSdk = 36

    val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
    val hasReleaseSigningCredentials = listOf(
        releaseKeystorePath,
        releaseKeystorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() }

    defaultConfig {
        applicationId = "com.ithamedia.pdfsigner"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "3.1"
        // Écrans d'outils ouvrables par adb uniquement en debug (test sur émulateur)
        manifestPlaceholders["toolsExported"] = "false"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigningCredentials) {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["toolsExported"] = "true"
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigningCredentials) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Écriture de la signature dans le PDF d'origine (texte et vecteurs conservés)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Scanner de documents (détection des bords, recadrage, filtres) via Google Play Services
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    // Reconnaissance de texte (OCR) sur l'appareil, alphabet latin
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
