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

// The on-device ASR modules need the NDK, CMake and the llama.cpp submodule. Without the submodule (CI, a
// fresh clone) they are left out so the app and its tests still build; voice work is paused, not removed.
if (settingsDir.resolve("asr/third_party/llama.cpp/.git").exists()) {
    include(":asr")
    include(":asr-demo")
} else {
    logger.lifecycle(
        "Skipping :asr and :asr-demo: run `git submodule update --init apps/android/asr/third_party/llama.cpp` to build them.",
    )
}
include(":nlu")
include(":app")
include(":core:data")
include(":feature:voice")
