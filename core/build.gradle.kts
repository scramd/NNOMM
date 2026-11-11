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
    testImplementation(kotlin("test"))
    testImplementation(libs.core.coroutines.test)
}
