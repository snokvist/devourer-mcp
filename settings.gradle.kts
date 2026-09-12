rootProject.name = "devourer-mcp"

include(
    ":protocol",
    ":radio",
    ":capture",
    ":mcp",
)
project(":protocol").projectDir = file("kotlin/protocol")
project(":radio").projectDir = file("kotlin/radio")
project(":capture").projectDir = file("kotlin/capture")
project(":mcp").projectDir = file("kotlin/mcp")

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
