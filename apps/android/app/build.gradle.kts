plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Debug talks to the Mac dev server through `adb reverse tcp:9002 tcp:9002`, or over the LAN with
// -Pbudgetwise.apiBaseUrl=http://<mac-lan-ip>:9002/api/v1/ (the debug network config allows cleartext to any host).
// Release has its own property and must be HTTPS.
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
    // HiltWorkerFactory for the WorkManager configuration (SyncWorker is built through Hilt).
    implementation(libs.androidx.hilt.work)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // ComponentActivity for createComposeRule, merged into the debug manifest that unit tests run against.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
