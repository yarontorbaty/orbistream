# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Bondix classes
-keep class bondix.pkg.** { *; }
-keep class com.orbistream.bondix.** { *; }

# Keep GStreamer native methods
-keep class org.freedesktop.gstreamer.** { *; }
-keep class com.orbistream.streaming.** { *; }

# Keep native method declarations
-keepclasseswithmembernames class * {
    native <methods>;
}

