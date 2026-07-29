#ifndef LOG_ENGINE_HPP
#define LOG_ENGINE_HPP

#include <string>
#include <atomic>
#include <regex>
#include <pthread.h>
#include <vector>
#include <memory>
#include <string_view>
#include <utility>

enum class FilterMode {
    None,
    Regex,
    Literal,
};

/**
 * Immutable configuration snapshot for one native logcat worker.
 */
struct LogConfig {
    std::string pid;           // Target Process ID to filter
    std::string level = "D";   // Minimum log level (V, D, I, W, E, F)
    std::string tagFilter;     // Tag-specific filters (e.g., "MyApp:V *:S")
    std::string customFilter;  // Initial line-by-line filter value
    FilterMode filterMode = FilterMode::None;
};

class LogEngine {
public:
    LogEngine();
    ~LogEngine();

    /**
     * Starts the log collection engine.
     *
     * Ownership contract: the returned descriptor is the read end of a pipe and
     * must be closed by the Kotlin layer. The engine owns the worker thread,
     * stop event fd, child logcat process, and Kotlin pipe write end.
     *
     * @param config The logging configuration.
     * @return File Descriptor (read-end of the pipe) to be consumed by the Kotlin layer,
     * or -1 if initialization fails.
     */
    int start(const LogConfig& config);

    /**
     * Actively stops log collection and releases all allocated native resources.
     * Safe to call repeatedly.
     */
    void stop();

    /**
     * Hot-swaps the current Regex filter pattern during runtime. The compiled
     * filter is published with release/acquire semantics and consumed lock-free
     * by the read loop.
     */
    void updateRegex(const std::string& regex);

    /**
     * Updates the filtering pattern using a literal string.
     * Matches text case-insensitively without routing through regex.
     */
    void updateLiteral(const std::string& text);

private:
    struct ThreadArgs {
        LogEngine* engine;
        int kotlin_write_fd; // Write-end of the pipe connected to Kotlin
        std::vector<std::string> args; // Sanitized argv for logcat
    };

    struct LogFilter {
        enum class Type {
            Regex,
            Literal,
        };

        explicit LogFilter(std::regex regexValue)
            : type(Type::Regex), regex(std::move(regexValue)) {}

        explicit LogFilter(std::string literalValue)
            : type(Type::Literal), literal(std::move(literalValue)) {}

        Type type;
        std::regex regex;
        std::string literal;
    };

    /**
     * Main background thread routine that restarts logcat if the child exits
     * while the engine is still running.
     */
    static void* workerRoutine(void* arg);

    /**
     * Executes a single logcat process iteration (Fork -> Exec -> Monitor).
     */
    void runLogcatIteration(const std::vector<std::string>& args, int kotlin_fd, int stop_fd);

    /**
     * Core I/O loop: Reads raw stream, applies line filtering, and writes to output pipe.
     */
    void processLogStream(pid_t child_pid, int read_fd, int kotlin_fd, int stop_fd);

    /** Performs a non-blocking all-or-drop write to a pipe. */
    static ssize_t safeWrite(int fd, const char* buf, size_t len);

    static bool matchesLiteralCaseInsensitive(std::string_view line, std::string_view literal);

    std::atomic<bool> m_running{false}; // Engine execution state
    pthread_t m_thread{0};             // Background worker thread handle
    LogConfig m_config;                // Current configuration snapshot

    std::shared_ptr<const LogFilter> m_filter;

    std::atomic<int> m_stop_event_fd{-1};
};

#endif // LOG_ENGINE_HPP
