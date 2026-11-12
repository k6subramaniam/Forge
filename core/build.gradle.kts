// :core — pure Kotlin/JVM domain logic. No Android dependencies, so the math that
// must be right (blueprint §4, "Testing") is unit-tested on any JVM in CI.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // The phone ↔ watch wire format (com.forge.core.sync) is JSON, so both apps
    // share one tested codec.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
