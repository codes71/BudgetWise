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

// Release signing comes from the environment (android-release.yml decodes the keystore from secrets); no
// keystore or password is committed. Without ANDROID_KEYSTORE_FILE the release build stays unsigned.
val releaseKeystoreFile = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull

android {
    namespace = "app.budgetwise"
    compileSdk = 37

    signingConfigs {
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = file(releaseKeystoreFile)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
            }
        }
    }

    defaultConfig {
        applicationId = "app.budgetwise"
        minSdk = 26
        targetSdk = 36
        // Play rejects an upload whose versionCode is not higher than the last one; android-release.yml sets it.
        versionCode = providers.gradleProperty("budgetwise.versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("budgetwise.versionName").getOrElse("0.1.0")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 64-bit ARM only, the ABI the on-device speech models were tested on (see :asr).
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // The app's language can differ from the phone's (the language screen), so every install needs both languages'
    // resources: Play must not split the bundle by the phone's language.
    bundle {
        language {
            enableSplit = false
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
            if (releaseKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Optimized like release (R8, not debuggable) but signed with the debug key and pointed at the local
        // server, so it installs over a debug build without losing data. Use it to judge scrolling and speed;
        // debug builds run without ART optimizations and stutter on any phone.
        create("profile") {
            initWith(getByName("release"))
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
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
}

dependencies {
    implementation(project(":core:data"))
    // Voice entry: on-device speech recognition and the microphone.
    implementation(project(":asr"))
    implementation(project(":feature:voice"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // HiltWorkerFactory for the WorkManager configuration (SyncWorker is built through Hilt).
    implementation(libs.androidx.hilt.work)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.text.google.fonts)
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
