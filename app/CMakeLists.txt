cmake_minimum_required(VERSION 3.22.1)
project("whispershare" LANGUAGES C CXX)

option(WHISPERSHARE_VULKAN "Enable Vulkan GPU backend in whisper.cpp" OFF)

include(FetchContent)

# Pin to a known-good whisper.cpp tag. Bump as needed.
# v1.7.4 has stable Android + Vulkan support.
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        v1.7.4
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

if(WHISPERSHARE_VULKAN)
    target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE WHISPERSHARE_VULKAN=1)
endif()
