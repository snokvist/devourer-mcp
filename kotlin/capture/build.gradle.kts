plugins {
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":protocol"))
    api(project(":radio"))
    implementation(libs.coroutines.core)
    testImplementation(testFixtures(project(":protocol")))
    testImplementation(testFixtures(project(":radio")))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
