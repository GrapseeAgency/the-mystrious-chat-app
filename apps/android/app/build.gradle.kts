plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release channel plumbing — CI/local overrides via -PpulseVersionCode / -PpulseVersionName.
val pulseVersionCode = (project.findProperty("pulseVersionCode") as String?)?.toInt() ?: 1
val pulseVersionName = (project.findProperty("pulseVersionName") as String?) ?: "0.1.0-native"

android {
    namespace = "app.pulse.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.pulse.chat"
        minSdk = 26
        targetSdk = 35
        versionCode = pulseVersionCode
        versionName = pulseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Deployment knobs (PulseEndpoints comment promised this wave) — a public
        // gateway can be baked in with -PpulseGateway=https://… without code changes.
        buildConfigField("String", "PULSE_GATEWAY", "\"${project.findProperty("pulseGateway") ?: "http://10.0.2.2:81"}\"")
        buildConfigField("String", "PULSE_SOCKET", "\"${project.findProperty("pulseSocket") ?: "http://10.0.2.2:3003"}\"")
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
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":feature-chat"))
    implementation(project(":feature-calls"))
    implementation(project(":feature-stories"))
    implementation(project(":feature-hub"))
    implementation(project(":feature-settings"))

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
}
