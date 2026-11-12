plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
}

kotlin { jvmToolchain(25) }

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.core.coroutines)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.core.compress)
    implementation(libs.core.xz)
    implementation(libs.core.jsoup)
    testImplementation(kotlin("test"))
    testImplementation(libs.core.coroutines.test)
}

// Deliberately separate from unit tests; set NNOMM_HOME to ~/nnomm-sandbox/home.
tasks.register<JavaExec>("realRegistryCheck") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("nnomm.core.io.real.IntegrationCheckKt")
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
            vendor.set(JvmVendorSpec.JETBRAINS)
        }
    )
    if (providers.gradleProperty("takeover").isPresent) args("--takeover")
}

apply(plugin = "com.diffplug.spotless")

configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    kotlin {
        target("src/**/*.kt")
        ktfmt("0.58").kotlinlangStyle()
    }
    kotlinGradle {
        target("build.gradle.kts")
        ktfmt("0.58").kotlinlangStyle()
    }
}

// Exhaustive second-crash/hand-change probe is explicit, keeping unit test runs short.
tasks.test { exclude("**/LoaderRecoveryMatrixTest*") }

tasks.register<Test>("loaderRecoveryMatrix") {
    description = "Runs the offline loader recovery interruption and hand-change matrix"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/LoaderRecoveryMatrixTest*")
}
