cmake_minimum_required(VERSION 3.22.1)
project(echomute)

# whisper.cpp (MIT) for on-device transcription. ggml is built as shared libraries with its CPU backend loaded at
# runtime, so that on arm64 the variant matching the phone's CPU features (dotprod, fp16, i8mm) is picked.
set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
endif()
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)

# The debug APK is what gets installed, so keep the model code optimised even when Gradle builds with -O0,
# and let the linker drop unused code.
set(CMAKE_C_FLAGS_DEBUG "-O3")
set(CMAKE_CXX_FLAGS_DEBUG "-O3")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections")
set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} -Wl,--gc-sections")

get_filename_component(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp ABSOLUTE)
add_subdirectory(${WHISPER_DIR} whisper EXCLUDE_FROM_ALL)

add_library(echomute SHARED native-lib.cpp fingerprint.cpp whisper-jni.cpp)
target_compile_options(echomute PRIVATE -fvisibility=hidden)
target_link_libraries(echomute PRIVATE whisper ggml log)
# Build every CPU backend variant so they are packaged alongside libechomute.so.
get_property(all_targets DIRECTORY ${WHISPER_DIR}/ggml/src PROPERTY BUILDSYSTEM_TARGETS)
foreach(target ${all_targets})
    if (target MATCHES "^ggml-cpu")
        add_dependencies(echomute ${target})
    endif()
endforeach()
