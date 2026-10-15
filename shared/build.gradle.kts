// Shared code module (Kotlin Multiplatform).
//
// Targets: Android (the real app, all code in src/androidMain for now),
// iOS (iosArm64 = iPhone, iosSimulatorArm64 = Simulator on Apple-silicon
// Macs), desktop (a JVM build for Windows and macOS, src/desktopMain;
// the app shell and installers are in :desktopApp) and the web
// (Kotlin/Wasm in the browser, src/wasmJsMain; the page is :webApp).
// src/nonWebMain is shared by every version but the web (DataStore,
// Dispatchers.IO — things a browser doesn't have). Portable code moves
// from src/androidMain into src/commonMain package by package;
// Android-only pieces stay in androidMain behind expect/actual
// declarations with iOS versions in src/iosMain and desktop ones in
// src/desktopMain.
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The desktop build's native libraries (FFmpeg for video, JavaFX for web
// pages) are this computer's: each installer is built on its own system
// (Windows, Intel Mac, Apple-silicon Mac) by .github/workflows/desktop.yml.
val hostOs: String = System.getProperty("os.name").lowercase()
val hostArm: Boolean = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }
val ffmpegClassifier: String = when {
    hostOs.startsWith("windows") -> "windows-x86_64"
    hostOs.startsWith("mac") -> if (hostArm) "macosx-arm64" else "macosx-x86_64"
    else -> if (hostArm) "linux-arm64" else "linux-x86_64"
}
val javafxClassifier: String = when {
    hostOs.startsWith("windows") -> "win"
    hostOs.startsWith("mac") -> if (hostArm) "mac-aarch64" else "mac"
    else -> if (hostArm) "linux-aarch64" else "linux"
}

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Windows and macOS.
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Kotlin/Native iOS targets only build on macOS (the iOS workflow); on
    // the Linux Android runner they are skipped automatically.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    // The web: Compose drawn on a <canvas> by Kotlin/Wasm.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    // nonWebMain: commonMain's code for Android, desktop and iOS only.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("nonWeb") {
                withAndroidTarget()
                withJvm()
                group("native")
            }
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
            languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
            languageSettings.optIn("kotlin.io.encoding.ExperimentalEncodingApi")
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            // Compose Multiplatform 1.8.2 = androidx Compose 1.8.x /
            // Material3 1.3.x on Android.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.animation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            // Bundled resources (Audiowide font, Stellar logo) for both apps.
            implementation(compose.components.resources)
            // Coil 3: images on both platforms (Android: OkHttp underneath,
            // iOS: Ktor/NSURLSession).
            implementation("io.coil-kt.coil3:coil-compose:3.2.0")

            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        }
        val nonWebMain by getting {
            dependencies {
                // Settings (PreferencesManager): same "media_viewer_prefs" file
                // on Android as before, a file in the app sandbox on iOS.
                implementation("androidx.datastore:datastore-preferences-core:1.1.7")
            }
        }
        val wasmJsMain by getting {
            dependencies {
                // HTTP and images: the browser's fetch, through Ktor.
                implementation("io.ktor:ktor-client-core:3.1.3")
                implementation("io.ktor:ktor-client-js:3.1.3")
                implementation("io.coil-kt.coil3:coil-network-ktor3:3.2.0")
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
            }
        }
        iosMain.dependencies {
            // HTTP on iOS (NSURLSession underneath) — see HttpEngine.ios.kt.
            implementation("io.ktor:ktor-client-core:3.1.3")
            implementation("io.ktor:ktor-client-darwin:3.1.3")
            // Locks for the iOS ConcurrentHashMap / SharedPreferences stand-ins.
            implementation("org.jetbrains.kotlinx:atomicfu:0.27.0")
            implementation("io.coil-kt.coil3:coil-network-ktor3:3.2.0")
            // Compose Multiplatform's BackHandler (iOS back gesture).
            implementation("org.jetbrains.compose.ui:ui-backhandler:1.8.2")
        }
        val desktopMain by getting {
            dependencies {
                // Dispatchers.Main = the window's (Swing) UI thread.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                // HTTP: OkHttp, with the same client setup as Android.
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
                // Backups (the same streamed JSON format as Android's).
                implementation("com.google.code.gson:gson:2.10.1")
                // AI Tagging: the same ONNX Runtime (and model) as Android.
                implementation("com.microsoft.onnxruntime:onnxruntime:1.18.0")
                // Video: FFmpeg through JavaCV — playback (Bluesky's HLS
                // streams included), upload re-encoding, GIF <-> video. The
                // GPL build (x264); Stellar itself is GPLv3. Only the pieces
                // used, for this computer: JavaCV would otherwise pull in
                // OpenCV and a dozen other libraries for every system.
                val javacv = "1.5.11"
                val ffmpeg = "7.1-$javacv"
                implementation("org.bytedeco:javacv:$javacv") { isTransitive = false }
                implementation("org.bytedeco:javacpp:$javacv") { isTransitive = false }
                implementation("org.bytedeco:javacpp:$javacv:$ffmpegClassifier") { isTransitive = false }
                implementation("org.bytedeco:ffmpeg:$ffmpeg") { isTransitive = false }
                implementation("org.bytedeco:ffmpeg:$ffmpeg:$ffmpegClassifier-gpl") { isTransitive = false }
                // Web pages (Search's browser, live-stream embeds, Bluesky's
                // sign-up check): JavaFX's WebView.
                val javafx = "21.0.6"
                for (module in listOf("base", "graphics", "controls", "media", "web", "swing")) {
                    implementation("org.openjfx:javafx-$module:$javafx:$javafxClassifier") { isTransitive = false }
                }
            }
        }
        androidMain.dependencies {
            implementation(compose.preview)
            implementation("androidx.activity:activity-compose:1.8.2")
            implementation("androidx.core:core-ktx:1.12.0")
            // Performance: installs the baseline profiles Compose/Material/Media3
            // ship inside their AARs, so their hot paths get AOT-compiled on a
            // sideloaded install instead of running interpreted/JIT for the first
            // several launches (the main cause of first-open jank on menus).
            implementation("androidx.profileinstaller:profileinstaller:1.3.1")

            implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")

            implementation("com.squareup.okhttp3:okhttp:4.12.0")
            implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

            implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
            implementation("io.coil-kt.coil3:coil-video:3.2.0")
            // Animated GIFs (comments' GIF-picker GIFs) — see AnimatedGif.android.kt.
            implementation("io.coil-kt.coil3:coil-gif:3.2.0")

            implementation("androidx.media3:media3-exoplayer:1.8.0")
            implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
            implementation("androidx.media3:media3-ui:1.8.0")
            // Feed video preloading + on-disk cache (FeedVideoPool): SimpleCache /
            // CacheDataSource live in datasource, its index DB in database. Both are
            // already transitive deps of exoplayer; declared so the imports are stable.
            implementation("androidx.media3:media3-datasource:1.8.0")
            implementation("androidx.media3:media3-database:1.8.0")
            // Video-thumbnail stitching (VideoThumbnailStitcher): needs
            // media3-transformer 1.8.0+ for EditedMediaItemSequence +
            // experimentalSetForceAudioTrack, which is why compileSdk is 35.
            implementation("androidx.media3:media3-transformer:1.8.0")
            implementation("androidx.media3:media3-effect:1.8.0")
            implementation("androidx.media3:media3-muxer:1.8.0")

            implementation("androidx.datastore:datastore-preferences:1.1.7")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
            implementation("com.google.code.gson:gson:2.10.1")
            // Profile QR codes (ProfileQrScreen): ZXing's encoder only — the QR
            // matrix it produces is drawn by Compose in Stellar's own style.
            implementation("com.google.zxing:core:3.5.3")
            implementation("androidx.work:work-runtime-ktx:2.9.0")

            // Phase 4 — on-device translation (ML Kit Translate + Language Identification).
            // Both models run fully on-device; no server round trip and no API key.
            implementation("com.google.mlkit:translate:17.0.3")
            implementation("com.google.mlkit:language-id:17.0.6")

            // AI Tagging feature: fully local ONNX inference for the e621-trained
            // Z3D-E621-Convnext tagger (see ImageTagger.kt) — no cloud calls, no
            // content-moderation layer, runs entirely on-device via NNAPI/XNNPACK.
            implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

            // Item 8 — VRM/VTuber mode: CameraX for the live front-camera preview
            // that face/hand/body tracking runs against.
            val cameraxVersion = "1.4.2"
            implementation("androidx.camera:camera-core:$cameraxVersion")
            implementation("androidx.camera:camera-camera2:$cameraxVersion")
            implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
            implementation("androidx.camera:camera-view:$cameraxVersion")

            // Item 8, VRM pipeline step 1 — MediaPipe Tasks Vision. All three
            // landmarkers (Face/Hand/Pose) wired up now — see
            // util/FaceLandmarkerHelper.kt, HandLandmarkerHelper.kt,
            // PoseLandmarkerHelper.kt. Version pinned to the latest stable at the
            // time of this session — worth checking
            // https://developers.google.com/mediapipe/solutions/vision/face_landmarker
            // for anything newer before building.
            implementation("com.google.mediapipe:tasks-vision:0.10.14")

            // Item 8, VRM pipeline step 5 — Filament for rendering the VRM's glTF.
            // filament-android is the core renderer; filament-utils-android adds
            // `ModelViewer` (camera/manipulator/render-loop convenience wrapper)
            // and the gltfio glTF loader `ModelViewer.loadModelGlb` uses under the
            // hood — both from the same release train, kept on the same version.
            // Check https://github.com/google/filament/releases for anything newer
            // before building; Filament ships frequently.
            val filamentVersion = "1.51.6"
            implementation("com.google.android.filament:filament-android:$filamentVersion")
            implementation("com.google.android.filament:filament-utils-android:$filamentVersion")
            implementation("com.google.android.filament:gltfio-android:$filamentVersion")
        }
    }
}

android {
    // Same namespace as the old :app module so com.mediaviewer.R keeps
    // working unchanged.
    namespace = "com.mediaviewer"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
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
}

compose.resources {
    // Generated accessor class for composeResources/ (Res.font.audiowide, …).
    packageOfResClass = "com.mediaviewer.resources"
    publicResClass = true
    generateResClass = always
}
