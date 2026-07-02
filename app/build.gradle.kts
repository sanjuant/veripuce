import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.veridoc.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.veridoc.app"
        minSdk = 24   // ~97 % du parc ; les AndroidX récentes exigent >= 23
        targetSdk = 36
        // Surchargés en CI par le n° de run (VERSION_CODE) et le tag (VERSION_NAME).
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Signature de la release. La clé n'est JAMAIS dans le dépôt : elle vient de variables
    // d'environnement (secrets GitHub en CI). Sans clé (build local ordinaire), la release
    // sort simplement non signée — le debug, lui, reste auto-signé comme d'habitude.
    val releaseStore = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        create("release") {
            if (releaseStore != null) {
                storeFile = file(releaseStore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // R8 désactivé volontairement : JMRTD / BouncyCastle / SCUBA reposent sur la
            // réflexion ; activer le shrink sans règles -keep casserait la lecture à l'exécution.
            isMinifyEnabled = false
            signingConfig = if (releaseStore != null) signingConfigs.getByName("release") else null
        }
    }

    // Les jars BouncyCastle (bcprov/bcutil) embarquent des métadonnées identiques
    // (OSGi, licences) qui entrent en collision au packaging de l'APK. On les écarte.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
            )
        }
        jniLibs {
            // Le .so prébuilt du décodeur JP2 n'est pas strippable par le NDK
            // ("Unable to strip ... libopenjpeg.so") — on le conserve tel quel, sans warning.
            keepDebugSymbols += "**/libopenjpeg.so"
        }
    }
}

// Remplace l'ancien android.kotlinOptions (déprécié en Kotlin 2.x).
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

base {
    // Nom des artefacts de build : produit veridoc-debug.apk au lieu de app-debug.apk.
    archivesName.set("veridoc")
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    // 1.18.0 = dernière version pour compileSdk 36 (1.19+ exige l'API 37 et AGP 9.1).
    implementation("androidx.core:core-ktx:1.18.0")

    // Scan OCR du CAN / de la MRZ : CameraX + ML Kit Text Recognition en mode BUNDLED
    // (modèle embarqué -> 100 % on-device et hors-ligne, aucune image ne quitte le
    // téléphone, fonctionne sans Play Services ; +~4 Mo d'APK, assumé pour une app d'identité).
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("com.google.android.material:material:1.14.0")

    // Lecture eMRTD / eID (PACE, Secure Messaging, parsing des data groups, SOD)
    implementation("org.jmrtd:jmrtd:0.8.6")                // dernière version stable
    implementation("net.sf.scuba:scuba-sc-android:0.0.26") // transport SCUBA pour Android

    // Crypto : courbes Brainpool, AES-CMAC… (voir le remplacement du provider BC au démarrage)
    // Aligné sur la version que JMRTD 0.8.6 tire en transitif (bcprov/bcpkix 1.84) pour
    // éviter tout mélange de versions BC dans l'APK.
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")

    // Décodage JPEG 2000 de la photo (Android n'a pas javax.imageio)
    // Coordonnée d'origine "com.gemalto.jp2:jp2-android:1.0" introuvable (Maven/Google/JitPack).
    // Miroir Maven Central de JP2ForAndroid, même package com.gemalto.jp2.* + libs natives.
    implementation("io.github.CshtZrgk:jp2-android:1.0.0")

    // Tests JVM (MrzOcr est du Kotlin pur, sans dépendance Android)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.21")
}
