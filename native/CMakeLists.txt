cmake_minimum_required(VERSION 3.15)
project(devourer_mcp_native CXX C)

# Needed at THIS level, not just inside vendor/devourer: CTest only discovers
# tests registered in directories under the project where enable_testing() was
# called. Without it `ctest --test-dir build/native-bridge` reports "No tests
# were found", which reads exactly like a passing run with nothing to do.
enable_testing()

# The vendored devourer is added as a subdirectory rather than built separately:
# its CMakeLists already resolves libusb, generates the PHY tables and defines
# the DEVOURER_HAVE_* macros the bridge compiles against. Reimplementing any of
# that here would be a second source of truth for what is compiled in.
#
# Every backend is on by default, including MT7612U — which upstream defaults
# OFF. "Build every backend supported by the vendored source" is the project's
# rule, and MT7612U is exactly the backend the local bench hardware needs.
set(DEVOURER_MT7612U ON CACHE BOOL "MediaTek MT7612U backend" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../vendor/devourer
                 ${CMAKE_CURRENT_BINARY_DIR}/devourer)

add_executable(devourer-bridge
    bridge/src/main.cpp
    bridge/src/Devices.cpp
    bridge/src/Session.cpp
)

target_compile_features(devourer-bridge PRIVATE cxx_std_17)

# examples/ is on the include path for common/DeviceSession.h — the teardown
# order (device -> interface -> handle -> context) lives there, and getting it
# wrong is a crash rather than a leak. Reusing it keeps one copy of that rule.
target_include_directories(devourer-bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../vendor/devourer/src
    ${CMAKE_CURRENT_SOURCE_DIR}/../vendor/devourer/examples
)

target_link_libraries(devourer-bridge PRIVATE devourer)

if(NOT MSVC)
    target_compile_options(devourer-bridge PRIVATE -Wall -Wextra)
endif()

install(TARGETS devourer-bridge RUNTIME DESTINATION bin)
