LOCAL_PATH := $(call my-dir)

# Path to GStreamer Android SDK
ifndef GSTREAMER_ROOT_ANDROID
    GSTREAMER_ROOT_ANDROID := $(LOCAL_PATH)/../../../../gstreamer-android
endif

# Select architecture-specific path
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/armv7
else ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
    GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/arm64
else ifeq ($(TARGET_ARCH_ABI),x86)
    GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/x86
else ifeq ($(TARGET_ARCH_ABI),x86_64)
    GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/x86_64
endif

#######################################
# GStreamer integration (must be first)
#######################################

ifndef GSTREAMER_SDK_ROOT_ANDROID
    GSTREAMER_SDK_ROOT_ANDROID := $(GSTREAMER_ROOT)
endif

# GStreamer plugins we need for SRT streaming
GSTREAMER_PLUGINS := \
    coreelements \
    app \
    audioconvert \
    audioresample \
    audiorate \
    videoconvertscale \
    videorate \
    typefindfunctions \
    x264 \
    voaacenc \
    mpegtsmux \
    srt \
    audioparsers \
    videoparsersbad \
    isomp4 \
    alaw \
    mulaw \
    opus \
    rawparse \
    tcp \
    udp \
    opengl \
    opensles

# Extra dependencies
GSTREAMER_EXTRA_DEPS := gstreamer-video-1.0 gstreamer-audio-1.0 gstreamer-app-1.0 gstreamer-net-1.0

# Include GStreamer build integration
include $(GSTREAMER_ROOT)/share/gst-android/ndk-build/gstreamer-1.0.mk

#######################################
# OrbiStream native library
#######################################

include $(CLEAR_VARS)

LOCAL_MODULE := orbistream_native
LOCAL_SRC_FILES := \
    orbistream_jni.cpp \
    srt_streamer.cpp

LOCAL_SHARED_LIBRARIES := gstreamer_android
LOCAL_LDLIBS := -llog -landroid

LOCAL_C_INCLUDES := \
    $(GSTREAMER_ROOT)/include/gstreamer-1.0 \
    $(GSTREAMER_ROOT)/include/glib-2.0 \
    $(GSTREAMER_ROOT)/lib/glib-2.0/include \
    $(GSTREAMER_ROOT)/include

LOCAL_CPPFLAGS := -std=c++17 -fexceptions -frtti -DGSTREAMER_AVAILABLE=1
LOCAL_CFLAGS := -DGSTREAMER_AVAILABLE=1

include $(BUILD_SHARED_LIBRARY)
