import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

// Clé de publication fournie par l'environnement (secrets CI) ; jamais versionnée.
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

        // Clé TMDB optionnelle fournie à la compilation (secret CI) ; l'utilisateur peut aussi la saisir.
        val tmdbKey = providers.environmentVariable("NEXTV_TMDB_API_KEY").orNull.orEmpty()
        buildConfigField("String", "TMDB_API_KEY", "\"${tmdbKey.replace("\"", "")}\"")
    }

    signingConfigs {
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
            // Sans clé de publication, l'APK est signé avec la clé de debug : installable pour les tests.
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
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
}

dependencies {
    // Build inclus `core/` (substitution automatique).
    implementation("tv.nextv:core:1.0")

    implementation(libs.kotlinx.coroutines.android)
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
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Journal par test et durée maximale : un écran qui ne se stabilise pas est identifié au lieu de bloquer la CI.
tasks.withType<Test>().configureEach {
    testLogging {
        events("started", "passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    timeout.set(java.time.Duration.ofMinutes(25))
}
