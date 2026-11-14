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
    implementation(libs.core.sevenzip)
    runtimeOnly(libs.core.sevenzip.platforms)
    implementation(libs.core.jsoup)
    implementation(libs.core.commonmark)
    implementation(libs.core.commonmark.tables)
    implementation(libs.core.commonmark.strikethrough)
    implementation(libs.core.commonmark.autolink)
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

// Exhaustive interruption runs are explicit; ordinary tests retain focused fault cases.
val crashTests =
    listOf(
        "nnomm.core.LoaderRecoveryMatrixTest.*",
        "nnomm.core.LoaderOwnershipV2Test.crashMatrixWithHandChanges",
        "nnomm.core.LoaderOwnershipRegressionTest.crashMatrix",
        "nnomm.core.LoaderOwnershipRegressionTest.crashesDuringRecoveryReplayPromotionEvidence",
        "nnomm.core.DoorstopRepairOrderTest.independentEditorMatrix",
        "nnomm.core.EntryAttackTest.fineGrainedSwapCrashMatrix",
        "nnomm.core.EntryAttackTest.ioFailureAtSwapSteps",
        "nnomm.core.LoaderPartialPlacementTest.reviewEveryPerFileCrashKeepsPreexistingPlayerFiles",
        "nnomm.core.ReviewProbeTest.storePromotionRollsBackAtEveryMutationBoundary",
        "nnomm.core.ReviewProbeTest.configTransitionRollsBackWithPlugins",
        "nnomm.core.ReviewProbeTest.interruptedAdoptionIsRepeatableWithoutGameMutation",
    )

tasks.test { filter { crashTests.forEach { excludeTestsMatching(it) } } }

tasks.register<Test>("crashTests") {
    description = "Runs exhaustive disk interruption and recovery matrices"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { crashTests.forEach { includeTestsMatching(it) } }
}

// Preserve the existing entry point for callers.
tasks.register("loaderRecoveryMatrix") { dependsOn("crashTests") }
