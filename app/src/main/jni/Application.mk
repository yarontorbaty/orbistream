# Application.mk for OrbiStream

# Target ABIs
APP_ABI := armeabi-v7a arm64-v8a x86 x86_64

# Use C++ STL
APP_STL := c++_shared

# Minimum Android platform
APP_PLATFORM := android-26

# Build for release
APP_OPTIM := release

# Compiler flags
APP_CPPFLAGS := -std=c++17 -fexceptions -frtti

