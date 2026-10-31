cmake_minimum_required(VERSION 3.22.1)
project(echomute)

# whisper.cpp (MIT) for on-device transcription, linked statically into libechomute.so.
set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp whisper EXCLUDE_FROM_ALL)
# The debug APK is what gets installed, so keep the model code optimised even when Gradle builds with -O0.
foreach(target ggml ggml-base ggml-cpu whisper)
    target_compile_options(${target} PRIVATE -O3)
endforeach()

add_library(echomute SHARED native-lib.cpp fingerprint.cpp whisper-jni.cpp)
target_link_libraries(echomute PRIVATE whisper log)
# Drop the parts of ggml and whisper the bridge never calls.
foreach(target ggml ggml-base ggml-cpu whisper echomute)
    target_compile_options(${target} PRIVATE -ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden)
endforeach()
target_link_options(echomute PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
