plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val defaultLicenseApiUrl = "https://yxgqgkgouvpuzycajkvp.supabase.co/functions/v1/license-api"

val configuredLicenseApiUrl = providers.gradleProperty("REFINEPILOT_LICENSE_API_URL")
    .orElse(providers.environmentVariable("REFINEPILOT_LICENSE_API_URL"))
    .orNull
    ?.trim()
    .orEmpty()
val licenseApiUrl = configuredLicenseApiUrl.ifBlank { defaultLicenseApiUrl }
val escapedLicenseApiUrl = licenseApiUrl.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.refinepilot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.refinepilot.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        buildConfigField("String", "LICENSE_API_BASE_URL", "\"$escapedLicenseApiUrl\"")
        buildConfigField("boolean", "LICENSE_ENFORCEMENT_ENABLED", "true")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
}
