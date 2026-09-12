plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories { mavenCentral() }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
        // Explicit API is enabled per library module (see each build.gradle.kts):
        // it keeps the public surface deliberate, and the :mcp application
        // module has no library consumers so it opts out.
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            // Hardware-dependent tests must never be mistaken for unit tests,
            // nor their absence for a pass. They are tagged "hardware" and
            // excluded unless -PwithHardware is given; the summary says so.
            showStandardStreams = false
        }
        if (!project.hasProperty("withHardware")) {
            useJUnitPlatform { excludeTags("hardware") }
        }
    }
}
