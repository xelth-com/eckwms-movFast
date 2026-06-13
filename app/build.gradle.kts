import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Provisioned xelixir license token (the gating secret). Read from the gitignored
// local.properties (key `xelixir.licenseToken`) so the value is baked into the APK
// at build time but never committed. Empty when not provisioned.
val xelixirLicenseToken: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("xelixir.licenseToken", "")

android {
    namespace = "com.xelth.eckwms_movfast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xelth.eckwms_movfast"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "XELIXIR_LICENSE_TOKEN", "\"$xelixirLicenseToken\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(project.property("MYAPP_RELEASE_STORE_FILE") as String)
            storePassword = project.property("MYAPP_RELEASE_STORE_PASSWORD") as String
            keyAlias = project.property("MYAPP_RELEASE_KEY_ALIAS") as String
            keyPassword = project.property("MYAPP_RELEASE_KEY_PASSWORD") as String
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Distribution split (see 9eck.com/.eck/PRIVACY_BY_DESIGN.md + owner decision):
    //   paid — we install on managed PDAs (sideload/MDM). May carry the
    //          REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission (in src/paid/
    //          AndroidManifest.xml) and request the battery-exemption dialog,
    //          so trip recording survives aggressive OEM ROMs.
    //   free — users download from Google Play. That restricted permission is
    //          NOT present; the app only deep-links to system settings.
    // Shared code branches on BuildConfig.ENTERPRISE.
    flavorDimensions += "distribution"
    productFlavors {
        create("paid") {
            dimension = "distribution"
            buildConfigField("Boolean", "ENTERPRISE", "true")
        }
        create("free") {
            dimension = "distribution"
            buildConfigField("Boolean", "ENTERPRISE", "false")
            // Distinct id so a Play build can coexist / be published separately.
            applicationIdSuffix = ".free"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(files("libs/xcscanner_qrcode_v1.3.56.1.7-release.aar"))
    implementation("com.google.accompanist:accompanist-permissions:0.31.1-alpha")

    // Bouncy Castle for AES-GCM with non-standard 16-byte nonce
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")

    // Jetpack Compose Navigation
    implementation("androidx.navigation:navigation-compose:2.7.0")
    // Your existing Compose dependencies
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Для интеграции LiveData с Compose
    implementation("androidx.compose.runtime:runtime-livedata:1.5.4")
    implementation("io.coil-kt:coil-compose:2.5.0")
    
    // ML Kit Barcode Scanning
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // ML Kit Text Recognition (odometer OCR for the Fahrtenbuch)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Fused low-power location + Activity Recognition (trip auto-detect)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // CameraX dependencies
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Cryptography - Lazysodium for secure pairing (Ed25519)
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // Secure storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager for background sync
    implementation(libs.work.runtime.ktx)

    // WebSocket for hybrid transport
    implementation("org.java-websocket:Java-WebSocket:1.5.4")

    // Kotlinx Collections Immutable (required by POS UI)
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.7")
}