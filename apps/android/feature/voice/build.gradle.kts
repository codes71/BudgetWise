plugins {
    alias(libs.plugins.android.library)
}

// The microphone for voice entry: 16 kHz mono capture into memory, with a loudness level for the listening screen.
// The screens live in :app with the rest of the UI; speech recognition is :asr.
android {
    namespace = "app.budgetwise.voice"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
