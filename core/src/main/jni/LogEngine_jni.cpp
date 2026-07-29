#include <jni.h>
#include <string>
#include <cstring>
#include "LogEngine.hpp"
#include <android/log.h>

#define TAG "LogcatEngine-JNI"

#define likely(x)       __builtin_expect(!!(x), 1)
#define unlikely(x)     __builtin_expect(!!(x), 0)

/**
 * Process-wide engine instance. Kotlin serializes lifecycle calls because this
 * native singleton intentionally supports one active capture per process.
 */
static LogEngine g_logEngine;

/**
 * Copies a nullable Java string into native memory and releases JNI storage
 * immediately. Empty string is the native representation for absent filters.
 */
std::string jstringToStdString(JNIEnv *env, jstring jstr) {
    if (unlikely(!jstr)) return "";

    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    if (unlikely(!chars)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "jstringToStdString: GetStringUTFChars failed (OOM?)");
        return "";
    }

    std::string result(chars);

    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

FilterMode filterModeFromString(const std::string &mode) {
    if (mode == "literal") return FilterMode::Literal;
    if (mode == "regex") return FilterMode::Regex;
    return FilterMode::None;
}

/**
 * Configures the native singleton and returns the read end of the Kotlin pipe,
 * or -1 if startup fails.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_core_logcat_capture_core_LogManager_configureAndStart(
        JNIEnv *env,
        jobject thiz,
        jstring pid,
        jstring tags,
        jstring level,
        jstring filter,
        jstring filterMode
) {
    LogConfig config;

    config.pid = jstringToStdString(env, pid);
    config.tagFilter = jstringToStdString(env, tags);
    config.level = jstringToStdString(env, level);
    config.customFilter = jstringToStdString(env, filter);
    config.filterMode = filterModeFromString(jstringToStdString(env, filterMode));

    jint fd = g_logEngine.start(config);

    if (likely(fd >= 0)) {
        __android_log_print(ANDROID_LOG_DEBUG, TAG, "Engine started. Native Pipe FD: %d", fd);
    } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to start Engine. Check LogConfig parameters.");
    }

    return fd;
}

/** Stops the native singleton and releases owned descriptors/child process. */
extern "C" JNIEXPORT void JNICALL
Java_com_core_logcat_capture_core_LogManager_stop(JNIEnv *env, jobject thiz) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "Initiating Engine shutdown...");
    g_logEngine.stop();
}

/** Publishes a new regex filter snapshot without restarting capture. */
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

/** Publishes a new literal filter snapshot without restarting capture. */
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
