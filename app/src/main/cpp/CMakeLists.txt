cmake_minimum_required(VERSION 3.22.1)

project("orbistream" LANGUAGES CXX C)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# GStreamer configuration
# You need to set GSTREAMER_ROOT to the path of your GStreamer Android SDK
# Download from: https://gstreamer.freedesktop.org/data/pkg/android/
if(NOT DEFINED ENV{GSTREAMER_ROOT_ANDROID})
    set(GSTREAMER_ROOT_ANDROID "${CMAKE_SOURCE_DIR}/../../../../gstreamer-android")
else()
    set(GSTREAMER_ROOT_ANDROID $ENV{GSTREAMER_ROOT_ANDROID})
endif()

# Architecture-specific paths
if(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(GSTREAMER_ROOT ${GSTREAMER_ROOT_ANDROID}/armv7)
elseif(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(GSTREAMER_ROOT ${GSTREAMER_ROOT_ANDROID}/arm64)
elseif(${ANDROID_ABI} STREQUAL "x86")
    set(GSTREAMER_ROOT ${GSTREAMER_ROOT_ANDROID}/x86)
elseif(${ANDROID_ABI} STREQUAL "x86_64")
    set(GSTREAMER_ROOT ${GSTREAMER_ROOT_ANDROID}/x86_64)
endif()

# Check if GStreamer is available
if(EXISTS "${GSTREAMER_ROOT}/lib/gstreamer-1.0")
    set(GSTREAMER_AVAILABLE TRUE)
    message(STATUS "GStreamer found at: ${GSTREAMER_ROOT}")
else()
    set(GSTREAMER_AVAILABLE FALSE)
    message(WARNING "GStreamer not found. Building with stub implementation.")
endif()

# Main streaming library
add_library(orbistream_native SHARED
    orbistream_jni.cpp
    srt_streamer.cpp
)

if(GSTREAMER_AVAILABLE)
    # Include GStreamer headers
    target_include_directories(orbistream_native PRIVATE
        ${GSTREAMER_ROOT}/include/gstreamer-1.0
        ${GSTREAMER_ROOT}/include/glib-2.0
        ${GSTREAMER_ROOT}/lib/glib-2.0/include
    )

    # GStreamer library path
    set(GSTREAMER_LIB_PATH ${GSTREAMER_ROOT}/lib)

    # Link against GStreamer
    target_link_libraries(orbistream_native
        ${GSTREAMER_LIB_PATH}/libgstreamer-1.0.so
        ${GSTREAMER_LIB_PATH}/libgstbase-1.0.so
        ${GSTREAMER_LIB_PATH}/libgstapp-1.0.so
        ${GSTREAMER_LIB_PATH}/libgstvideo-1.0.so
        ${GSTREAMER_LIB_PATH}/libgstaudio-1.0.so
        ${GSTREAMER_LIB_PATH}/libgobject-2.0.so
        ${GSTREAMER_LIB_PATH}/libglib-2.0.so
        android
        log
    )
    
    target_compile_definitions(orbistream_native PRIVATE GSTREAMER_AVAILABLE=1)
else()
    target_link_libraries(orbistream_native
        android
        log
    )
    
    target_compile_definitions(orbistream_native PRIVATE GSTREAMER_AVAILABLE=0)
endif()

