// Thin Android app shell: applicationId, manifest, bundled assets and APK
// packaging. All Kotlin code and Android resources live in :shared
// (shared/src/androidMain for now; commonMain as the iOS port proceeds).
plugins {
    id("com.android.application")
}

// The version, from the one place it's kept (shared/.../AppVersion.kt).
val stellarVersion: String = Regex("NAME = \"([0-9.]+)\"")
    .find(rootProject.file("shared/src/commonMain/kotlin/com/mediaviewer/AppVersion.kt").readText())
    ?.groupValues?.get(1) ?: "1.0"

android {
    namespace = "rechoraccoon.stellar"
    compileSdk = 35

    defaultConfig {
        applicationId = "rechoraccoon.stellar"
        minSdk = 26
        targetSdk = 34
        // 2.8 → 208: always higher for a newer release, so the in-app update
        // installs over the old one.
        versionCode = stellarVersion.split('.').let { (it.getOrNull(0)?.toIntOrNull() ?: 1) * 100 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        versionName = stellarVersion
        // Size: only phone processors. The x86/x86_64 copies of every
        // native library (ONNX Runtime, MediaPipe, Filament, ML Kit …) are
        // for emulators and Chromebooks and made up a large share of the
        // APK; no Android phone uses them.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            // Size + speed: R8 drops library code the app never uses — most
            // of all the ~2,000 Material icons it doesn't draw. Stellar's own
            // classes and the native-backed libraries are kept whole and
            // nothing is renamed (proguard-rules.pro), so nothing that's
            // looked up by name can go missing. If a release build ever
            // misbehaves where a debug one doesn't, set this to false.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }

    // lifecycle 2.9's bundled lint checks were compiled against a newer lint
    // than AGP 8.7 ships and crash lintVital (IncompatibleClassChangeError in
    // NonNullableMutableLiveDataDetector). Lint never changes the APK, so
    // skip the release-time lint pass and that detector.
    lint {
        disable += "NullSafeMutableLiveData"
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    // MediaPipe's native loader mmaps these asset files directly; if AAPT
    // compresses them (its default for any extension it doesn't
    // recognize), that mmap fails silently and FaceLandmarker/
    // HandLandmarker/PoseLandmarker.createFromOptions() all fail — which
    // reads as "tracking never starts, everything reports 0" with no
    // visible crash, since VrmModeScreen's helpers catch and log that
    // failure instead of throwing.
    androidResources {
        noCompress += listOf("task", "tflite")
    }
}

dependencies {
    implementation(project(":shared"))
}
