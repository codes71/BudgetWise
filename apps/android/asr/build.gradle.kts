plugins {
    alias(libs.plugins.android.library)
}

// freococo's ONNX export (freococo.onnx and vocab.json) is published at huggingface.co/thura2/freococo-myanmar-asr-onnx;
// the default pins its first commit, as Omnilingual is pinned. Another host can be given with
// -Pbudgetwise.freococoModelBaseUrl=https://<host>/<dir>/ (the directory holding both files). Either way the app
// checks each file's size and sha256 (VoicePack), so a host can never change what is installed.
fun baseUrl(raw: String) = if (raw.endsWith("/")) raw else "$raw/"
val freococoModelBaseUrl = baseUrl(
    providers.gradleProperty("budgetwise.freococoModelBaseUrl").getOrElse("https://huggingface.co/thura2/freococo-myanmar-asr-onnx/resolve/e5041e673745d9fcd62872710820c22d8ccb9307/"),
)
check(freococoModelBaseUrl.startsWith("https://")) {
    "budgetwise.freococoModelBaseUrl must be HTTPS, was $freococoModelBaseUrl"
}

android {
    namespace = "app.budgetwise.asr"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        // Reported in Transcript.modelId, so a transcript names the runtime that produced it.
        buildConfigField("String", "ONNXRUNTIME_VERSION", "\"${libs.versions.onnxruntime.get()}\"")
        buildConfigField("String", "FREOCOCO_MODEL_BASE_URL", "\"$freococoModelBaseUrl\"")

        // onnxruntime-android ships four ABIs; the test APK only needs the phones' arm64 one.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.onnxruntime.android)
    implementation(libs.android.vad.webrtc)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
