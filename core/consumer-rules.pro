# Preserve JNI methods and LogManager class for the LogcatEngine
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.core.logcat.capture.core.LogManager {
    public *;
}

-keep class com.core.logcat.capture.core.LoggerServiceConnection {
    public *;
}

# Preserve AIDL generated interfaces if they are in this package
-keep class com.core.logcat.capture.ILogControl { *; }
-keep class com.core.logcat.capture.ILogControl$Stub { *; }

# Optimization: Allow ProGuard to remove unused C++ helper calls but keep JNI Bridge
-optimizationpasses 5
-allowaccessmodification