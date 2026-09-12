plugins {
    alias(libs.plugins.kotlin.serialization)
    // Replaying a transmitted burst into a fake witness is needed by
    // :characterize's tests too, so it lives here rather than in one test set.
    `java-test-fixtures`
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":protocol"))
    api(project(":radio"))
    api(project(":capture"))
    implementation(libs.coroutines.core)
    testFixturesApi(testFixtures(project(":radio")))
    testImplementation(testFixtures(project(":radio")))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
