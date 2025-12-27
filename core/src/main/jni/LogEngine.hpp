#ifndef LOG_ENGINE_HPP
#define LOG_ENGINE_HPP

#include <string>
#include <atomic>
#include <regex>
#include <pthread.h>
#include <mutex>
#include <vector>

/**
 * Logcat execution configuration structure.
 * Defines filters and parameters for the underlying logcat process.
 */
struct LogConfig {
    std::string pid;           // Target Process ID to filter
    std::string level = "D";   // Minimum log level (V, D, I, W, E, F)
    std::string tagFilter;     // Tag-specific filters (e.g., "MyApp:V *:S")
    std::string customRegex;   // Initial regex pattern for line-by-line filtering
};

class LogEngine {
public:
    LogEngine();
    ~LogEngine();

    /**
     * Starts the log collection engine.
     * @param config The logging configuration.
     * @return File Descriptor (read-end of the pipe) to be consumed by the Kotlin layer,
     * or -1 if initialization fails.
     */
    int start(const LogConfig& config);

    /**
     * Actively stops log collection and releases all allocated native resources.
     */
    void stop();

    /**
     * Hot-swaps the current Regex filter pattern during runtime.
     * Thread-safe and non-blocking for the worker thread.
     */
    void updateRegex(const std::string& regex);

    /**
     * Updates the filtering pattern using a literal string.
     * Special characters are automatically escaped before being converted to regex.
     */
    void updateLiteral(const std::string& text);

private:
    /**
     * Wrapper for arguments passed to the pthread worker routine.
     */
    struct ThreadArgs {
        LogEngine* engine;
        int kotlin_write_fd; // Write-end of the pipe connected to Kotlin
        std::string cmd;     // Constructed shell command for logcat
    };

    // --- CORE ENGINE FUNCTIONS (Decoupled for Watchdog Optimization) ---

    /**
     * Main background thread routine that manages the logcat lifecycle (Watchdog).
     */
    static void* workerRoutine(void* arg);

    /**
     * Executes a single logcat process iteration (Fork -> Exec -> Monitor).
     */
    void runLogcatIteration(const std::string& cmd, int kotlin_fd);

    /**
     * Core I/O loop: Reads raw stream, applies regex filtering, and writes to output pipe.
     */
    void processLogStream(pid_t child_pid, int read_fd, int kotlin_fd);

    /**
     * Performs a guaranteed write to a file descriptor, handling EINTR and partial writes.
     */
    static ssize_t safeWrite(int fd, const char* buf, size_t len);

    /**
     * Internally compiles and sets the regex pattern using an atomic lock-free mechanism.
     */
    void setPattern(const std::string& pattern);

    // --- STATE VARIABLES (Atomic & Thread-safe) ---

    std::atomic<bool> m_running{false}; // Engine execution state
    pthread_t m_thread{0};             // Background worker thread handle
    LogConfig m_config;                // Current configuration snapshot

    // Spinlock: High-performance synchronization for hot-swapping regex patterns
    std::atomic_flag m_regex_lock = ATOMIC_FLAG_INIT;
    std::regex m_regex;                 // Compiled regex object
    std::atomic<bool> m_regex_ready{false}; // Flag indicating if regex filtering is active

    // Internal management for rapid shutdown and pipe flushing
    std::atomic<int> m_internal_raw_read_fd{-1}; // Current logcat output file descriptor
    std::atomic<bool> m_should_flush_accumulator{false}; // Signal to clear buffer on filter change
};

#endif // LOG_ENGINE_HPP
