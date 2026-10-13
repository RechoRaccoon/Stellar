// Desktop app shell (Windows and macOS): the window, its icon, and the
// installers. All app code lives in :shared (commonMain + desktopMain).
//
//   gradle :desktopApp:run                  — run it
//   gradle :desktopApp:packageMsi            (on Windows; the .exe installer
//                                              is Inno Setup: windows/stellar.iss)
//   gradle :desktopApp:packageDmg           (on a Mac)
//
// Installers can only be built on the system they're for; CI builds all
// of them (.github/workflows/desktop.yml).
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}


dependencies {
    implementation(project(":shared"))
    // Compose's desktop runtime (Skia) for this computer.
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
}

compose.desktop {
    application {
        mainClass = "com.mediaviewer.desktop.MainKt"
        jvmArgs += listOf(
            "-Xmx2g",
            // Crisp text on high-DPI Windows screens.
            "-Dsun.java2d.uiScale.enabled=true",
        )

        buildTypes.release.proguard {
            // Nothing is shrunk: much of the app (JavaCV, JavaFX, Coil) is
            // looked up by name at run time.
            isEnabled.set(false)
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "Stellar"
            packageVersion = "1.0.0"
            description = "Stellar — an open-source social app for the AT Protocol"
            vendor = "Recho Raccoon"
            copyright = "© Recho Raccoon. GPLv3."
            licenseFile.set(rootProject.file("LICENSE"))
            // The whole Java runtime (JavaFX's web view and FFmpeg's loader
            // reach into parts a trimmed one would leave out).
            includeAllModules = true

            windows {
                iconFile.set(project.file("icons/stellar.ico"))
                menuGroup = "Stellar"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Fixed, so a newer installer upgrades the installed app.
                upgradeUuid = "6f1d2b44-8f4e-4c55-9a59-3c1f5e0b7a21"
            }
            macOS {
                iconFile.set(project.file("icons/stellar.icns"))
                bundleID = "rechoraccoon.stellar.desktop"
                dockName = "Stellar"
                appCategory = "public.app-category.social-networking"
            }
            linux {
                iconFile.set(project.file("icons/stellar.png"))
            }
        }
    }
}
