pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // The web build (Kotlin/Wasm) adds its own download sites for Node.js,
    // Yarn and Binaryen to the root project; every module still gets its
    // libraries from the repositories below.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Stellar"
include(":shared", ":androidApp", ":desktopApp", ":webApp")
