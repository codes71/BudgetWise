plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Debug talks to the Mac dev server through `adb reverse tcp:9002 tcp:9002`. Override with
// -Pbudgetwise.apiBaseUrl=... . Release has its own property and must be HTTPS.
fun baseUrl(raw: String) = if (raw.endsWith("/")) raw else "$raw/"
val debugApiBaseUrl = baseUrl(
    providers.gradleProperty("budgetwise.apiBaseUrl").getOrElse("http://127.0.0.1:9002/api/v1/"),
)
val releaseApiBaseUrl = baseUrl(
    providers.gradleProperty("budgetwise.releaseApiBaseUrl").getOrElse("https://api.budgetwise.invalid/api/v1/"),
)
check(releaseApiBaseUrl.startsWith("https://")) {
    "budgetwise.releaseApiBaseUrl must be HTTPS, was $releaseApiBaseUrl"
}

android {
    namespace = "app.budgetwise"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.budgetwise"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // llama.cpp target (see :asr); WS3 may extend.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        // llama.cpp loads its CPU backend variants from nativeLibraryDir at runtime (contract amendment 8).
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core:data"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
