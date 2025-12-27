#include <jni.h>
#include <string>
#include <cstring>
#include "LogEngine.hpp"
#include <android/log.h>

/**
 * LOG TAG for JNI layer diagnostics.
 * Allows filtering JNI-specific lifecycle events in Android Studio Logcat.
 */
#define TAG "LogcatEngine-JNI"

/**
 * COMPILER BRANCH HINTS
 * Assists the CPU branch predictor to prioritize the most frequent execution paths.
 */
#define likely(x)       __builtin_expect(!!(x), 1)
#define unlikely(x)     __builtin_expect(!!(x), 0)

/**
 * STATIC ENGINE INSTANCE
 * Managed as a singleton. The engine's lifecycle is tied to the native library
 * loading state within the JVM.
 */
static LogEngine g_logEngine;

/**
 * HELPER: Safe JNI String to Std::String Conversion
 * @param env JNI interface pointer.
 * @param jstr The Java string to convert.
 * @return C++ string copy. Returns empty string on NULL input or allocation failure.
 */
std::string jstringToStdString(JNIEnv *env, jstring jstr) {
    if (unlikely(!jstr)) return "";

    // GetStringUTFChars creates a modified UTF-8 copy of the java string
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    if (unlikely(!chars)) {
        // Log error if JNI fails to allocate memory for the string chars
        __android_log_print(ANDROID_LOG_ERROR, TAG, "jstringToStdString: GetStringUTFChars failed (OOM?)");
        return "";
    }

    std::string result(chars);

    // Immediate release to prevent JNI local reference table overflow
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

/**
 * JNI BRIDGE: configureAndStart
 * Configures the LogEngine with provided filters and returns a pipe File Descriptor.
 * * @return jint: A valid File Descriptor (read-end) on success, or -1 on failure.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_core_logcat_capture_core_LogManager_configureAndStart(
        JNIEnv *env, jobject thiz, jstring pid, jstring tags, jstring level, jstring regex
) {
    LogConfig config;

    config.pid = jstringToStdString(env, pid);
    config.tagFilter = jstringToStdString(env, tags);
    config.level = jstringToStdString(env, level);
    config.customRegex = jstringToStdString(env, regex);

    jint fd = g_logEngine.start(config);

    if (likely(fd > 0)) {
        __android_log_print(ANDROID_LOG_DEBUG, TAG, "Engine started. Native Pipe FD: %d", fd);
    } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to start Engine. Check LogConfig parameters.");
    }

    return fd;
}

/**
 * JNI BRIDGE: stop
 * Triggers the shutdown sequence for the background thread and its child processes.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_core_logcat_capture_core_LogManager_stop(JNIEnv *env, jobject thiz) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "Initiating Engine shutdown...");
    g_logEngine.stop();
}

/**
 * JNI BRIDGE: updateRegex
 * Hot-swaps the current regex filter pattern without interrupting the capture stream.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_core_logcat_capture_core_LogManager_updateRegex(JNIEnv *env, jobject thiz, jstring regex) {
    if (unlikely(!regex)) {
        g_logEngine.updateRegex("");
        return;
    }

    const char *cRegex = env->GetStringUTFChars(regex, nullptr);
    if (likely(cRegex)) {
        g_logEngine.updateRegex(cRegex);
        env->ReleaseStringUTFChars(regex, cRegex);
    } else {
        __android_log_print(ANDROID_LOG_WARN, TAG, "updateRegex: Failed to extract JNI string chars");
    }
}

/**
 * JNI BRIDGE: updateLiteral
 * Hot-swaps the filter with a literal string. Characters are escaped internally for safety.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_core_logcat_capture_core_LogManager_updateLiteral(JNIEnv *env, jobject thiz, jstring text) {
    if (unlikely(!text)) {
        g_logEngine.updateLiteral("");
        return;
    }

    const char *cText = env->GetStringUTFChars(text, nullptr);
    if (likely(cText)) {
        g_logEngine.updateLiteral(cText);
        env->ReleaseStringUTFChars(text, cText);
    } else {
        __android_log_print(ANDROID_LOG_WARN, TAG, "updateLiteral: Failed to extract JNI string chars");
    }
}