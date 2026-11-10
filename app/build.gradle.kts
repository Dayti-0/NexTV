import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Duration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

// Clé de publication fournie par l'environnement (CI) : déchiffrée à partir de
// app/nextv-release.keystore.enc avec le secret NEXTV_KEY_PASSPHRASE, ou décodée des secrets.
val releaseKeystorePath: String? = providers.environmentVariable("NEXTV_KEYSTORE_FILE").orNull

android {
    namespace = "tv.nextv.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "tv.nextv.app"
        minSdk = 24
        targetSdk = 36
        versionCode = providers.gradleProperty("nextv.versionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("nextv.versionName").orNull ?: "0.2.0"
        // Processeurs ARM seulement (Fire TV, téléviseurs, box) : le moteur VLC pèse ~40 Mo par processeur.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }

        // Clé TMDB optionnelle fournie à la compilation (secret CI) ; l'utilisateur peut aussi la saisir.
        val tmdbKey = providers.environmentVariable("NEXTV_TMDB_API_KEY").orNull.orEmpty()
        buildConfigField("String", "TMDB_API_KEY", "\"${tmdbKey.replace("\"", "")}\"")
        // Clé football-data.org optionnelle (secret CI) pour la section Foot ; elle peut aussi se saisir dans les réglages.
        val footballKey = providers.environmentVariable("NEXTV_FOOTBALL_API_KEY").orNull.orEmpty()
        buildConfigField("String", "FOOTBALL_API_KEY", "\"${footballKey.replace("\"", "")}\"")

        // Dépôt GitHub dont les versions publiées servent de mises à jour (lecture anonyme : dépôt public).
        val updateRepository = providers.gradleProperty("nextv.updateRepo").orNull ?: "Dayti-0/NexTV"
        buildConfigField("String", "UPDATE_REPOSITORY", "\"${updateRepository.replace("\"", "")}\"")
    }

    signingConfigs {
        // Clé de test fixe (dépôt privé) pour les versions de test et de debug : chaque version
        // s'installe par-dessus la précédente, sans désinstaller ni perdre playlists et réglages.
        getByName("debug") {
            storeFile = file("nextv-test.keystore")
            storePassword = "nextv-test"
            keyAlias = "nextv-test"
            keyPassword = "nextv-test"
        }
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("NEXTV_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("NEXTV_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("NEXTV_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sans clé de publication (secrets CI), l'APK est signé avec la clé de test du dépôt.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric rend les écrans avec les vraies ressources (captures d'écran Roborazzi).
            isIncludeAndroidResources = true
            // Captures en 1920 × 1080 : la mémoire par défaut (512 Mo) ne suffit plus.
            all { it.maxHeapSize = "2g" }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Bibliothèques natives compressées dans l'APK (moteur VLC) : téléchargement de la mise à jour deux fois plus léger.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.generateKotlin", "true")
    // Schéma de chaque version des bases, gardé dans le dépôt (migrations écrites et testées à partir de lui).
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Build inclus `core/` (substitution automatique).
    implementation("tv.nextv:core:1.0")

    implementation(libs.kotlinx.coroutines.android)
    // QR code de la configuration depuis le téléphone.
    implementation(libs.zxing.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.ui)
    // Son que l'appareil ne sait pas décoder (licence GPL v3, voir LICENSE).
    implementation(libs.media3.ffmpeg.decoder)
    // Son MPEG audio couche I et II (MP2 des enregistrements de la TNT), absent de FFmpeg et des appareils (licence LGPL).
    implementation(libs.jlayer)
    // Moteur de VLC (libVLC, licence LGPL) : lit les sons et vidéos que le lecteur de l'appli ne sait pas lire.
    implementation(libs.libvlc)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Captures d'écran : ./gradlew :app:recordRoborazziDebug
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Journal par test et durée maximale : un écran qui ne se stabilise pas est identifié au lieu de bloquer la CI.
tasks.withType<Test>().configureEach {
    testLogging {
        events("started", "passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
    timeout.set(Duration.ofMinutes(25))
}
