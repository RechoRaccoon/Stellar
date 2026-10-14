// The web version's page: the same shared app as every other version
// (:shared, src/wasmJsMain for the browser parts), drawn by Compose on a
// <canvas> with Kotlin/Wasm.
//
//   gradle :webApp:wasmJsBrowserDevelopmentRun   — run it locally
//   gradle :webApp:wasmJsBrowserDistribution     — the site, in
//                                                  build/dist/wasmJs/productionExecutable
//
// CI (.github/workflows/web.yml) builds it on every push and publishes it
// to GitHub Pages from main.
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("stellar")
        browser {
            commonWebpackConfig {
                outputFileName = "stellar.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
            }
        }
    }
}
