cmake_minimum_required(VERSION 3.10)
project(whisper-android C CXX)

set(WHISPER_DIR ${CMAKE_SOURCE_DIR}/../../../../../whisper.cpp)

# Use whisper.cpp's own CMake build system
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Always optimize whisper.cpp — debug builds without -O3 are 10-50x slower
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

add_subdirectory(${WHISPER_DIR} ${CMAKE_BINARY_DIR}/whisper.cpp)

find_library(LOG_LIB log)

function(build_library target_name)
    add_library(${target_name} SHARED ${CMAKE_SOURCE_DIR}/jni.c)

    target_include_directories(${target_name} PRIVATE
        ${WHISPER_DIR}/include
        ${WHISPER_DIR}/ggml/include
    )

    target_link_libraries(${target_name} whisper ${LOG_LIB} android)

    if (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4)
    endif()

    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE -O3)
        target_compile_options(${target_name} PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
        target_compile_options(${target_name} PRIVATE -ffunction-sections -fdata-sections)
        target_link_options(${target_name} PRIVATE -Wl,--gc-sections)
        target_link_options(${target_name} PRIVATE -Wl,--exclude-libs,ALL)
        target_link_options(${target_name} PRIVATE -flto)
    endif()
endfunction()

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_library("whisper_vfpv4")
endif()

build_library("whisper_jni")
