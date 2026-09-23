import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

apply(from = "product-photos.gradle.kts")

// This fingerprint belongs to the already installed test APK. Never silently
// replace its key: Android would reject an in-place update on the shop phones.
val verifyDebugSigningKey by tasks.registering {
    doLast {
        val signing = android.signingConfigs.getByName("debug")
        val keyFile = requireNotNull(signing.storeFile)
        check(keyFile.isFile) { "Chave de testes ausente. Restaure o debug.keystore original; veja ATUALIZACOES.md." }
        val keyStore = KeyStore.getInstance(keyFile, requireNotNull(signing.storePassword).toCharArray())
        val certificate = requireNotNull(keyStore.getCertificate(signing.keyAlias))
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        val expected = rootProject.file("distribution/signing-certificate.sha256").readText().trim()
        check(actual == expected) { "Assinatura diferente do APK anterior. Restaure a chave original; não desinstale o app para contornar o erro." }
    }
}
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(verifyDebugSigningKey) }
// The unconfigured build opens setup instructions, without contacting a fake backend.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "br.com.bebidaspdv"
    compileSdk = 35
    defaultConfig {
        applicationId = "br.com.bebidaspdv"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "FIREBASE_CONFIGURED", file("google-services.json").exists().toString())
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/productPhotos/assets"))
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Private test distribution only. The release variant has no self-update SDK.
    debugImplementation("com.google.firebase:firebase-appdistribution:16.0.0-beta20")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    // 2.9.1 fixes singleTop entries stuck in CREATED, which suspends lifecycle-aware UI collectors.
    implementation("androidx.navigation:navigation-compose:2.9.1")
    // Bundled barcode model: the first scan also works without an internet connection.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    // CameraX exposes ListenableFuture in its API. Keep Guava on the compile
    // classpath too; forcing listenablefuture:1.0 would duplicate it at runtime.
    implementation("com.google.guava:guava:32.1.3-android")
    implementation("io.insert-koin:koin-android:4.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation(platform("com.google.firebase:firebase-bom:34.0.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
