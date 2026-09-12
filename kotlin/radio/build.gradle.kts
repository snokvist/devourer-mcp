plugins {
    alias(libs.plugins.kotlin.serialization)
    // The fake radios live here, in the module that owns the contract, so that
    // :experiment, :characterize and :mcp can all test against one fake
    // instead of three drifting copies.
    `java-test-fixtures`
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":protocol"))
    implementation(libs.coroutines.core)
    testFixturesApi(testFixtures(project(":protocol")))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
