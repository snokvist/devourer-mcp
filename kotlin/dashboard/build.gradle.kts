plugins {
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":protocol"))
    api(project(":radio"))
    api(project(":capture"))
    api(project(":experiment"))
    api(project(":characterize"))
    api(project(":scratchpad"))
    implementation(libs.coroutines.core)
    testImplementation(testFixtures(project(":radio")))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
