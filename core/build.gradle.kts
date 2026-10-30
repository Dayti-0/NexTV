import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

group = "tv.nextv"
version = "1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    // DNS sur HTTPS (résolveur public au choix, quand celui du fournisseur d'accès bloque).
    implementation(libs.okhttp.dnsoverhttps)
    // Les API XmlPull et org.json sont fournies par le framework Android ; on ne les embarque pas.
    compileOnly(libs.xmlpull)
    compileOnly(libs.orgjson)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kxml2)
    testImplementation(libs.orgjson)
}
