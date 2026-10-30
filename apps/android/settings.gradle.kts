pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.gkonovalov.android-vad") }
        }
    }
}

rootProject.name = "budgetwise-android"

// On-device speech recognition (ONNX Runtime from Maven) and its manual device harness. No NDK or submodule needed.
include(":asr")
include(":asr-demo")
include(":nlu")
include(":app")
include(":core:data")
include(":feature:voice")
