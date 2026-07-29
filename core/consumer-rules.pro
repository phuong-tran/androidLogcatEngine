# Static JNI symbols in liblogcat_capture.so are name-based and point at
# com.core.logcat.capture.core.LogManager. Keep those native method names even
# when the consuming app enables minification.
-keepclasseswithmembernames class com.core.logcat.capture.core.LogManager {
    native <methods>;
}

# Preserve the optional bound service and generated AIDL contract for apps that
# use the service facade instead of the direct LogcatEngine API.
-keep class com.core.logcat.capture.service.LogcatService { *; }
-keep class com.core.logcat.capture.ILogControl { *; }
-keep class com.core.logcat.capture.ILogControl$* { *; }
