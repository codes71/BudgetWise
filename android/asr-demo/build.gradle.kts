plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.budgetwise.asr.demo"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.budgetwise.asr.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        // The Raj engine is arm64 only; this also drops android-vad's 4 KB aligned x86 and armv7 libraries.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // ggml CPU variant libraries must be extracted so ggml_backend_load_all_from_path can find them.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":asr"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
