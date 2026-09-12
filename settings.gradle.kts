rootProject.name = "devourer-mcp"

include(
    ":protocol",
    ":radio",
    ":capture",
    ":experiment",
    ":characterize",
    ":scratchpad",
    ":dashboard",
    ":mcp",
)
project(":protocol").projectDir = file("kotlin/protocol")
project(":radio").projectDir = file("kotlin/radio")
project(":capture").projectDir = file("kotlin/capture")
project(":experiment").projectDir = file("kotlin/experiment")
project(":characterize").projectDir = file("kotlin/characterize")
project(":scratchpad").projectDir = file("kotlin/scratchpad")
project(":dashboard").projectDir = file("kotlin/dashboard")
project(":mcp").projectDir = file("kotlin/mcp")

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
