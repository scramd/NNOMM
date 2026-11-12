import com.codingfeline.buildkonfig.compiler.FieldSpec
import dev.nucleusframework.desktop.application.dsl.*


plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)


    alias(libs.plugins.nucleus)


    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.buildkonfig)
}

val appVersion = "0.1.0"


java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
}

kotlin {
    jvm()
    jvmToolchain(25)
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)

            implementation(libs.nucleus.core)
            implementation(libs.nucleus.nucleus.application)
            implementation(libs.nucleus.decorated.window.tao)
            implementation(libs.nucleus.window.tao)
            implementation(libs.nucleus.darkmode)
            implementation(libs.nucleus.taskbar)
            implementation(libs.nucleus.updater)
            implementation(libs.nucleus.native.http)

            implementation(libs.nucleus.native.http.ktor)
            implementation(libs.nucleus.aot)

            implementation(libs.nucleus.fs.watcher)
            
            implementation(libs.nucleus.notif.win)
            implementation(libs.nucleus.notif.mac)
            implementation(libs.nucleus.notif.linux)

            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutinesSwing)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs.compose)

            implementation(libs.coil)
            implementation(libs.coil.ktor)
            



            implementation("net.sf.sevenzipjbinding:sevenzipjbinding:16.02-2.01")
            implementation("net.sf.sevenzipjbinding:sevenzipjbinding-all-platforms:16.02-2.01")
            implementation("org.slf4j:slf4j-simple:2.0.17")
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

buildkonfig {
    packageName = "nnomm.app"
    defaultConfigs {
        buildConfigField(FieldSpec.Type.STRING, "VERSION", appVersion)
    }
}

nucleus.application {
    mainClass = "nnomm.app.MainKt"
    jvmArgs += listOf(
        "--enable-native-access=ALL-UNNAMED",
    )

    val authorEmail = "327495124+clankagent@users.noreply.github.com"
    nativeDistributions {
        targetFormats(
            TargetFormat.Portable,
            TargetFormat.AppImage,
            TargetFormat.Msi,
            TargetFormat.Rpm,
            TargetFormat.Deb,
            TargetFormat.Flatpak,
            TargetFormat.Zip
        )

        modules(
            "java.base", "java.desktop", "java.logging",
            "jdk.security.auth",
            "java.management",
            "java.naming",
            "jdk.unsupported"
        )
        
        appName = "NNOMM"
        packageName = "NNOMM"
        packageVersion = appVersion
        vendor = "NNOMM"
        description = "Mod manager for Nuclear Option"

        artifactName = $$"${name}-${version}-${os}-${arch}.${ext}"

        cleanupNativeLibs = true
        enableAotCache = false
        homepage = "https://github.com/scramd/NNOMM"
        compressionLevel = CompressionLevel.Normal
        fileAssociation(
            mimeType = "application/nommpack",
            extension = "nommpack",
            description = "NNOMM modpack",
            linuxIconFile = project.file("icons/icon.png"),
            windowsIconFile = project.file("icons/icon.ico"),
            macOSIconFile = project.file("icons/icon.icns"),
        )
        windows {
            upgradeUuid = "b0eccd25-0931-477e-8ff4-c6693ee69964"
            menuGroup = "NNOMM"
            shortcut = true
            iconFile.set(project.file("icons/icon.ico"))
            nsis {
                runAfterFinish = true
                deleteAppDataOnUninstall = false
                installerIcon.set(project.file("icons/icon.ico"))
                uninstallerIcon.set(project.file("icons/icon.ico"))
                license.set(rootProject.file("LICENSE"))
            }
        }

        linux {
            shortcut = true
            packageName = "nnomm"
            appCategory = "Utility"
            debMaintainer = "clankagent <${authorEmail}>"
            iconFile.set(project.file("icons/icon.png"))
        }

        macOS {
            bundleID = "io.github.scramd.nnomm"
        }

        publish {
            publishMode = PublishMode.Never
            github {
                enabled = true
                owner = "scramd"
                repo = "NNOMM"
                token = System.getenv("GITHUB_TOKEN")
                channel = ReleaseChannel.Latest
                releaseType = ReleaseType.Release
            }
        }
    }
    buildTypes {
        release {
            proguard {
                version = "7.9.1"
                isEnabled = false
                optimize = false
                obfuscate.set(false)
                joinOutputJars.set(true)
            }
        }
    }
}

// The renderer runs the same JVM compilation and resources as the desktop app.
val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
tasks.register<JavaExec>("renderShots") {
    group = "verification"
    description = "Render registered Compose states with CPU Skia, without a window"
    dependsOn("jvmMainClasses")
    classpath = files(jvmMainCompilation.output.allOutputs, configurations.getByName("jvmRuntimeClasspath"))
    mainClass.set("nnomm.ui.shots.RenderShotsKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)); vendor.set(JvmVendorSpec.JETBRAINS) })
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("java.awt.headless", "true")
    environment("SKIKO_RENDER_API", "SOFTWARE")
    args(layout.buildDirectory.dir("shots").get().asFile.absolutePath)
}
