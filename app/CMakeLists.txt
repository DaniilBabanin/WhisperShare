cmake_minimum_required(VERSION 3.22.1)
project("whispershare" LANGUAGES C CXX)

option(WHISPERSHARE_VULKAN "Enable Vulkan GPU backend in whisper.cpp" OFF)

include(FetchContent)

# Pin to a known-good whisper.cpp tag (currently v1.8.4). Bump as needed.
# Vulkan on Android needs >= v1.7.6 (vulkan-shaders-gen cross-compile fix).
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        v1.8.4
    GIT_SHALLOW    TRUE
)

# whisper.cpp build options
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

if(WHISPERSHARE_VULKAN)
    message(STATUS "WhisperShare: enabling Vulkan backend")
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)

    # whisper.cpp's Vulkan path needs glslc to compile shader sources.
    # The Android NDK ships one under shader-tools/<host>/. Locate it so the
    # build works on a fresh checkout without a system-wide Vulkan SDK.
    if(NOT Vulkan_GLSLC_EXECUTABLE)
        foreach(host_tag IN ITEMS linux-x86_64 darwin-x86_64 darwin-arm64 windows-x86_64)
            set(_candidate "${ANDROID_NDK}/shader-tools/${host_tag}/glslc")
            if(EXISTS "${_candidate}")
                set(Vulkan_GLSLC_EXECUTABLE "${_candidate}" CACHE FILEPATH "" FORCE)
                break()
            endif()
            if(EXISTS "${_candidate}.exe")
                set(Vulkan_GLSLC_EXECUTABLE "${_candidate}.exe" CACHE FILEPATH "" FORCE)
                break()
            endif()
        endforeach()
        message(STATUS "WhisperShare: Vulkan_GLSLC_EXECUTABLE=${Vulkan_GLSLC_EXECUTABLE}")
    endif()

    # whisper.cpp >= 1.7.5 uses vulkan.hpp (Vulkan-Hpp) which the NDK doesn't ship.
    # Pull the headers via FetchContent and put them ahead of the NDK sysroot so
    # the C headers (Vulkan-Headers submodule) match the C++ wrapper version.
    # Header-only usage: SOURCE_SUBDIR points at a directory with no
    # CMakeLists.txt so FetchContent_MakeAvailable() downloads the sources
    # without calling add_subdirectory(). (Single-argument
    # FetchContent_Populate() is deprecated by CMake 3.30 / CMP0169.)
    FetchContent_Declare(
        vulkan_hpp
        GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Hpp.git
        GIT_TAG        v1.3.290
        GIT_SHALLOW    TRUE
        SOURCE_SUBDIR  cmake-subdir-not-used
    )
    FetchContent_MakeAvailable(vulkan_hpp)
    include_directories(BEFORE SYSTEM
        "${vulkan_hpp_SOURCE_DIR}/Vulkan-Headers/include"
        "${vulkan_hpp_SOURCE_DIR}"
    )
endif()

# ARMv8.2 fp16 + dotprod — Tensor G4 supports both.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
add_compile_options(-march=armv8.2-a+fp16+dotprod)

FetchContent_MakeAvailable(whisper)

add_library(${CMAKE_PROJECT_NAME} SHARED
    src/main/cpp/whisper_jni.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${whisper_SOURCE_DIR}/include
    ${whisper_SOURCE_DIR}/ggml/include
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(${CMAKE_PROJECT_NAME}
    whisper
    ${log-lib}
    ${android-lib}
)

# NDK r27's toolchain no longer injects max-page-size, so the .so would link
# with 4 KB LOAD segments — Google Play requires 16 KB alignment on Android 15+
# devices (verified in soundscript 2026-06 via llvm-readelf after the same fix).
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

if(WHISPERSHARE_VULKAN)
    target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE WHISPERSHARE_VULKAN=1)
endif()
