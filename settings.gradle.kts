rootProject.name = "devourer-mcp"

include(
    ":protocol",
    ":radio",
    ":capture",
    ":experiment",
    ":mcp",
)
project(":protocol").projectDir = file("kotlin/protocol")
project(":radio").projectDir = file("kotlin/radio")
project(":capture").projectDir = file("kotlin/capture")
project(":experiment").projectDir = file("kotlin/experiment")
project(":mcp").projectDir = file("kotlin/mcp")

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
