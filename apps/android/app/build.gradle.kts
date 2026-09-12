plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release channel plumbing — CI/local overrides via -PpulseVersionCode / -PpulseVersionName.
val pulseVersionCode = (project.findProperty("pulseVersionCode") as String?)?.toInt() ?: 15
val pulseVersionName = (project.findProperty("pulseVersionName") as String?) ?: "0.5.2-native"

android {
    namespace = "app.pulse.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.pulse.chat"
        minSdk = 21
        targetSdk = 35
        versionCode = pulseVersionCode
        versionName = pulseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Deployment knobs. The baked default is the repo's public HTTPS CDN —
        // reachable from ANY device on earth. The old http://10.0.2.2:* emulator
        // defaults were unreachable on real phones AND cleartext (Android blocks
        // plain http → "CLEARTEXT communication not permitted"). Emulator dev:
        // -PpulseGateway=http://10.0.2.2:81 -PpulseSocket=http://10.0.2.2:3003
        buildConfigField("String", "PULSE_GATEWAY", "\"${project.findProperty("pulseGateway") ?: ""}\"")
        // Blank socket base = realtime relay disabled (no public relay yet) —
        // the client then stays offline-first instead of reconnect-spamming a
        // dead address forever.
        buildConfigField("String", "PULSE_SOCKET", "\"${project.findProperty("pulseSocket") ?: ""}\"")
    }

    signingConfigs {
        create("release") {
            // The committed distribution keystore (GS-distribution model): every
            // build — sandbox, CI, future waves — signs identically, so the
            // LiveUpdater can overwrite-install without an uninstall.
            storeFile = rootProject.file("keystores/pulse-release.keystore")
            storePassword = "pulse-live-update"
            keyAlias = "pulse"
            keyPassword = "pulse-live-update"
            // ALL signing schemes, explicitly. AGP defaults skimp on the v1 JAR
            // signature for modern minSdks, and some ROM installers reject such
            // APKs with "There was a problem parsing the package" — the exact
            // field report from v0.1.2. v1 costs ~20KB and removes the entire
            // class of picky-parser failures. minSdk itself is now 21 so even
            // pre-Android-8 devices can parse+install (a device below minSdk
            // reports the SAME parse error — the last surviving cause).
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            // Minify stays OFF for the shipped release: the 3–4GB sandbox cannot
            // survive R8, and CI runners are reserved for verify/build parity.
            // Re-enable alongside CI-only builds when the pipeline owns releases.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time etc. must survive on API < 26 — desugar at final DEX time.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":feature-chat"))
    implementation(project(":feature-calls"))
    implementation(project(":feature-hub"))
    implementation(project(":feature-settings"))

    // SessionViewModel/OnboardingViewModel encode/decode the persisted endpoint
    // overrides directly — :protocol exposes serialization as `implementation`,
    // so :app needs the runtime on its own classpath (same coordinate).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // OutboxWorker (Wave 0 offline core) — the expedited flusher lives in :app.
    implementation(libs.androidx.work.runtime)

    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
