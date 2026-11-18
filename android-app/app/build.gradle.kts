import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Public deployment address only. Credentials must stay in Worker Secrets.
val gatewayConfigFile = rootProject.file("gateway.properties")
val gatewayProperties =
    Properties().apply {
        if (gatewayConfigFile.exists()) gatewayConfigFile.inputStream().use { load(it) }
    }
val museGatewayUrl =
    providers
        .gradleProperty("museGatewayUrl")
        .orElse(gatewayProperties.getProperty("museGatewayUrl", ""))
        .get()
        .trim()
        .trimEnd('/')

require(
    museGatewayUrl.isEmpty() ||
        runCatching {
                val uri = URI(museGatewayUrl)
                uri.scheme == "https" &&
                    !uri.host.isNullOrBlank() &&
                    uri.userInfo == null &&
                    uri.rawQuery == null &&
                    uri.fragment == null &&
                    uri.path.isNullOrEmpty()
            }
            .getOrDefault(false)
) {
    "museGatewayUrl must be a public HTTPS origin without credentials, path or query."
}

// App versioning
val releaseVersionCode =
    providers.gradleProperty("netraSaathiVersionCode").orNull?.let {
        it.toIntOrNull()?.takeIf { code -> code in 1..2100000000 }
    } ?: 1
val releaseVersionName =
    providers.gradleProperty("netraSaathiVersionName").orNull ?: "1.0.0"
val releaseStore = providers.environmentVariable("NETRASAATHI_KEYSTORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("NETRASAATHI_KEYSTORE_PASSWORD").orNull
val releaseAlias = providers.environmentVariable("NETRASAATHI_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("NETRASAATHI_KEY_PASSWORD").orNull
val releaseSigningReady =
    listOf(releaseStore, releaseStorePassword, releaseAlias, releaseKeyPassword).all {
        !it.isNullOrBlank()
    }

android {
    namespace = "com.librevision"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.netrasaathi"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseStore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    defaultConfig { buildConfigField("String", "MUSE_GATEWAY_URL", "\"$museGatewayUrl\"") }
    androidResources { noCompress += "onnx" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks
    .matching { it.name == "preReleaseBuild" }
    .configureEach {
        doFirst {
            check(releaseSigningReady) {
                "Release signing is required. Configure NETRASAATHI_KEYSTORE_FILE, NETRASAATHI_KEYSTORE_PASSWORD, NETRASAATHI_KEY_ALIAS and NETRASAATHI_KEY_PASSWORD."
            }
            check(museGatewayUrl.isNotEmpty()) {
                "Deploy the Worker with npm run deploy in worker/ before building a release APK. It writes gateway.properties automatically."
            }
        }
    }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("androidx.camera:camera-video:1.6.2")
    implementation("com.google.ai.edge.litert:litert:2.3.2")
    implementation("com.google.ai.edge.litert:litert-gpu:2.3.2")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("org.json:json:20240303")
    implementation("org.opencv:opencv:4.13.0")
    testImplementation("junit:junit:4.13.2")
}
