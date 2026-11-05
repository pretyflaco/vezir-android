import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Optional release-signing config. If keystore.properties is found we
// build a signed release APK; otherwise assembleRelease falls back to
// the debug keystore so CI / first-time clones still build.
//
// v0.8.0: the keystore + properties live OUTSIDE the repo working tree
// (~/.android-keystores/vezir/) so they are never one .gitignore edit or
// `git add -f` away from being committed.  Resolution order:
//   1. $VEZIR_ANDROID_KEYSTORE_PROPS (explicit override)
//   2. ~/.android-keystores/vezir/keystore.properties (canonical)
//   3. <repo>/keystore.properties (legacy fallback; discouraged)
val keystorePropsFile: File? = sequenceOf(
    System.getenv("VEZIR_ANDROID_KEYSTORE_PROPS")?.let { File(it) },
    File(System.getProperty("user.home"), ".android-keystores/vezir/keystore.properties"),
    rootProject.file("keystore.properties"),
).firstOrNull { it != null && it.exists() }

val keystoreProps = Properties().apply {
    keystorePropsFile?.inputStream()?.use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile") != null

// Deployment settings: the default server URL offered at first launch and
// the public host whose certificate chain is pinned (see
// app/network_security_config.xml.template).  They describe one deployment,
// so they live outside the repo like the signing config.  Resolution order:
//   1. $VEZIR_ANDROID_DEPLOYMENT_PROPS (explicit override)
//   2. ~/.android-keystores/vezir/deployment.properties (canonical)
//   3. <repo>/deployment.properties (gitignored)
// Keys: defaultServerUrl=https://vezir.example.com  pinnedHost=vezir.example.com
// Without the file a build has no default URL and pins nothing; a RELEASE
// build refuses to run without both (a published APK must keep its pin).
val deploymentPropsFile: File? = sequenceOf(
    System.getenv("VEZIR_ANDROID_DEPLOYMENT_PROPS")?.let { File(it) },
    File(System.getProperty("user.home"), ".android-keystores/vezir/deployment.properties"),
    rootProject.file("deployment.properties"),
).firstOrNull { it != null && it.exists() }

val deploymentProps = Properties().apply {
    deploymentPropsFile?.inputStream()?.use { load(it) }
}
val deployDefaultServerUrl: String = deploymentProps.getProperty("defaultServerUrl", "").trim()
val deployPinnedHost: String = deploymentProps.getProperty("pinnedHost", "").trim()

require(deployDefaultServerUrl.isEmpty() || Regex("""https://[A-Za-z0-9.-]+(:\d+)?/?""").matches(deployDefaultServerUrl)) {
    "deployment.properties: defaultServerUrl must be https://<host>[:port], got '$deployDefaultServerUrl'"
}
require(deployPinnedHost.isEmpty() || Regex("""[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+""").matches(deployPinnedHost)) {
    "deployment.properties: pinnedHost must be a hostname, got '$deployPinnedHost'"
}

/** Writes res/xml/network_security_config.xml from the template. */
abstract class GenerateNetworkSecurityConfig : DefaultTask() {
    @get:InputFile
    abstract val template: RegularFileProperty

    @get:Input
    abstract val pinnedHost: Property<String>

    @get:Input
    abstract val defaultServerUrl: Property<String>

    @get:Input
    abstract val requireDeployment: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val host = pinnedHost.get()
        if (requireDeployment.get() && (host.isEmpty() || defaultServerUrl.get().isEmpty())) {
            throw GradleException(
                "Release build without deployment settings: set defaultServerUrl and " +
                    "pinnedHost in ~/.android-keystores/vezir/deployment.properties " +
                    "(or \$VEZIR_ANDROID_DEPLOYMENT_PROPS). A published APK must keep its pin."
            )
        }
        val domainConfig = if (host.isEmpty()) "" else """
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="false">$host</domain>
        <pin-set expiration="2027-10-21">
            <!-- ISRG Root X1 (RSA 4096) -->
            <pin digest="SHA-256">C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=</pin>
            <!-- ISRG Root X2 (ECDSA P-384) -->
            <pin digest="SHA-256">diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=</pin>
            <!-- ISRG Root YE (ECDSA P-384, gen-y) -->
            <pin digest="SHA-256">sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=</pin>
            <!-- ISRG Root YR (RSA 4096, gen-y) -->
            <pin digest="SHA-256">fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88=</pin>
        </pin-set>
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </domain-config>"""
        val out = outputDir.get().dir("xml").asFile
        out.mkdirs()
        val text = template.get().asFile.readText().replace("@PINNED_DOMAIN_CONFIG@", domainConfig)
        File(out, "network_security_config.xml").writeText(text)
    }
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
        versionCode = 51
        versionName = "0.17.1"

        // Hard cap on recording duration. v1 = 3 hours, per plan §18.
        // Capture service reads this from BuildConfig at runtime.
        buildConfigField(
            "long",
            "MAX_RECORDING_MILLIS",
            "${3L * 60L * 60L * 1000L}L"
        )

        // Pre-filled server URL on first launch; empty without
        // deployment.properties (the user types their server's URL).
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$deployDefaultServerUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
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
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                // Fall back to the debug keystore so a release-flavour
                // build at least produces a runnable APK on a fresh clone.
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Baseline records all findings present as of v0.5.x so CI can
        // gate on NEW lint regressions (continue-on-error flipped off in
        // .github/workflows/ci.yml) without first paying down the entire
        // historical backlog.  Regenerate with:
        //   ./gradlew lint  (after deleting lint-baseline.xml)
        baseline = file("lint-baseline.xml")
        // A genuinely broken build config should still fail; warnings are
        // baselined, not ignored.
        abortOnError = true
        warningsAsErrors = false
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

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<GenerateNetworkSecurityConfig>(
            "generate${name}NetworkSecurityConfig"
        ) {
            template.set(layout.projectDirectory.file("network_security_config.xml.template"))
            pinnedHost.set(deployPinnedHost)
            defaultServerUrl.set(deployDefaultServerUrl)
            requireDeployment.set(variant.buildType == "release")
        }
        variant.sources.res?.addGeneratedSourceDirectory(
            task, GenerateNetworkSecurityConfig::outputDir
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    // androidx.core:core-splashscreen back-ports the Android 12+ splash
    // API to API 23+. We use it for the brand mark splash on launch.
    implementation("androidx.core:core-splashscreen:1.0.1")
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

    // WorkManager for background periodic tasks (labeling notification).
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // CameraX + ML Kit (M5: QR enrollment).
    // CameraX gives us a Compose-friendly preview + frame analyser.
    // ML Kit barcode-scanning is the bundled flavour: model is included
    // in the APK so a freshly-installed phone can scan offline. The
    // -gms variant downloads the model on demand and saves ~10 MB; we
    // pick bundled to keep enrollment friction at zero.
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    // Bridge between CameraX ImageAnalysis frames and ML Kit detectors,
    // so we don't have to write our own ImageProxy -> InputImage adapter.
    implementation("androidx.camera:camera-mlkit-vision:1.3.0-beta02")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Real org.json impl for JVM unit tests (the android.jar stub throws).
    testImplementation("org.json:json:20240303")
}
