-keep class com.core.logcat.capture.** { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclassmembers class * {
    public <fields>;
    public <methods>;
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * {
    public static final java.lang.String LOG_TAG;
}

-keepattributes *Annotation*

# Preserve AIDL generated classes
-keep class com.core.logcat.capture.ILogControl { *; }
-keep class com.core.logcat.capture.ILogControl$Stub { *; }

# Keep the LogManager singleton
-keep class com.core.logcat.capture.core.LogManager { *; }
