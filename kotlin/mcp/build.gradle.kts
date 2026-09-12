plugins {
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":radio"))
    implementation(project(":capture"))
    implementation(project(":experiment"))
    implementation(project(":characterize"))
    implementation(project(":scratchpad"))
    implementation(libs.mcp.sdk)
    implementation(libs.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("org.openipc.devourer.mcp.MainKt")
}
