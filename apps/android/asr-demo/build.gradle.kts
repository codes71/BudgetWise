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

        // The phones this targets are arm64; this also drops ONNX Runtime's and android-vad's other ABIs.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
