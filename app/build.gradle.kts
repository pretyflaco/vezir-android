plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.vezir.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vezir.android"
        // minSdk = 29 (Android 10) so AudioPlaybackCapture is universally
        // available; targetSdk = 35 matches Signal Android (per the static
        // inspection notes in vezir_plan.md).
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Hard cap on recording duration. v1 = 3 hours, per plan §18.
        // Capture service reads this from BuildConfig at runtime.
        buildConfigField(
            "long",
            "MAX_RECORDING_MILLIS",
            "${3L * 60L * 60L * 1000L}L"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Cleartext HTTP is allowed only for the configured Tailscale-style
    // hosts via res/xml/network_security_config.xml. The manifest points at
    // that file; do not flip android:usesCleartextTraffic globally.
    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // EncryptedSharedPreferences for VEZIR_URL/VEZIR_TOKEN at rest.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // OkHttp for /health, /upload (M3), /api/sessions polling (M3).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Kotlinx Serialization for the QR-payload JSON shape:
    //   { "v": 1, "url": "...", "token": "..." }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
