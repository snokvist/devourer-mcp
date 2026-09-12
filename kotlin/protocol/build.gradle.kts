plugins {
    alias(libs.plugins.kotlin.serialization)
    // Synthetic frames are shared so the hand-maintained FrameRecord offsets
    // have exactly one test-side writer.
    `java-test-fixtures`
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.serialization.json)
    api(libs.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
