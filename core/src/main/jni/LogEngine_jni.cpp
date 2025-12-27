#include <jni.h>
#include <string>
#include <cstring>
#include "LogEngine.hpp"
#include <android/log.h>

/**
 * COMPILER OPTIMIZATION MACROS
 * likely/unlikely provide hints to the CPU branch predictor to minimize
 * pipeline stalls for frequently executed code paths.
 */
#define likely(x)       __builtin_expect(!!(x), 1)
#define unlikely(x)     __builtin_expect(!!(x), 0)

/**
 * GLOBAL ENGINE INSTANCE
 * The LogEngine is managed as a static singleton. Its lifecycle persists
 * for the duration of the native library's load time.
 */
static LogEngine g_logEngine;

/**
 * HELPER: Safe JNI String Conversion
 * Converts a Java jstring to a C++ std::string.
 * Includes NULL checks and ensures JNI memory is released to prevent local reference leaks.
 */
std::string jstringToStdString(JNIEnv *env, jstring jstr) {
    if (unlikely(!jstr)) return "";

    // GetStringUTFChars allocates a new UTF-8 string copy in the JNI heap
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    if (unlikely(!chars)) {
        // Return empty if allocation fails (usually due to OOM)
        return "";
    }

    std::string result(chars);

    // Crucial: Release the JNI reference immediately after copying to std::string
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

/**
 * JNI BRIDGE: configureAndStart
 * Initializes the engine with filter parameters and starts the capture thread.
 * @return File Descriptor (read-end) for Kotlin's FileChannel consumption.
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

    return g_logEngine.start(config);
}

/**
 * JNI BRIDGE: stop
 * Signals the engine to terminate the worker thread and close all internal pipes.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_core_logcat_capture_core_LogManager_stop(JNIEnv *env, jobject thiz) {
    g_logEngine.stop();
}

/**
 * JNI BRIDGE: updateRegex
 * Optimized hot-swap for regex filters. Bypasses helper functions to reduce
 * overhead on high-frequency UI updates.
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
    }
}

/**
 * JNI BRIDGE: updateLiteral
 * Hot-swaps the search filter using a literal string. The engine internally
 * handles character escaping for regex safety.
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
    }
}
