#include "LogEngine.hpp"
#include <unistd.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <sys/epoll.h>
#include <sys/resource.h>
#include <csignal>
#include <cstring>
#include <cstdlib>
#include <cerrno>
#include <string_view>
#include <memory>
#include <string>
#include <android/log.h>

/**
 * LOG TAG for Android Logcat debugging of the engine itself.
 */
#define TAG "LogcatEngine-Native"

/**
 * BRANCH PREDICTION OPTIMIZATION
 * Hints the compiler about the most likely execution paths.
 */
#define likely(x)       __builtin_expect(!!(x), 1)
#define unlikely(x)     __builtin_expect(!!(x), 0)

/**
 * READ BUFFER: 128KB provides a good balance between memory usage and
 * throughput for heavy I/O operations.
 */
static constexpr size_t READ_BUFFER_SIZE = 128 * 1024;

/**
 * EPOLL TIMEOUT: 200ms ensures we don't hog the CPU while staying
 * responsive to thread stop signals.
 */
static constexpr int EPOLL_TIMEOUT_MS = 200;

LogEngine::LogEngine() {
    /**
     * SIGNAL HANDLING
     * SIGCHLD: Ignored to let the kernel automatically reap child processes (no zombies).
     * SIGPIPE: Ignored to prevent app crash if the Kotlin-side pipe is closed unexpectedly.
     */
    signal(SIGCHLD, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
}

LogEngine::~LogEngine() {
    stop();
}

/**
 * START ENGINE
 * Initializes pipes, constructs the command, and spawns the worker thread.
 */
int LogEngine::start(const LogConfig &cfg) {
    if (m_running.exchange(true)) return -1; // Prevent multiple instances

    int p_kt[2]; // Pipe between Native and Kotlin
    if (pipe(p_kt) < 0) {
        m_running.store(false);
        return -1;
    }

    /**
     * PIPE CAPACITY OPTIMIZATION
     * Set buffer to 1MB to handle high-velocity logs without blocking the producer.
     */
    fcntl(p_kt[1], F_SETPIPE_SZ, 1024 * 1024);

    m_config = cfg;
    if (!cfg.customRegex.empty()) updateRegex(cfg.customRegex);

    // Build the logcat shell command
    std::string cmd = "/system/bin/logcat -v time";
    if (!m_config.pid.empty()) cmd += " --pid=" + m_config.pid;
    cmd += (m_config.tagFilter.empty()) ? " *:" + m_config.level : " " + m_config.tagFilter;

    auto args = new ThreadArgs{this, p_kt[1], cmd};
    if (pthread_create(&m_thread, nullptr, workerRoutine, args) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to create worker thread");
        close(p_kt[0]);
        close(p_kt[1]);
        delete args;
        m_running.store(false);
        return -1;
    }
    return p_kt[0]; // Kotlin will read from this FD
}

/**
 * STOP ENGINE
 * Safely terminates the thread and cleans up internal file descriptors.
 */
void LogEngine::stop() {
    if (!m_running.exchange(false, std::memory_order_release)) return;

    // Unblock any pending read in the worker thread
    int fd = m_internal_raw_read_fd.exchange(-1);
    if (fd != -1) close(fd);

    if (m_thread) {
        pthread_join(m_thread, nullptr);
        m_thread = 0;
    }
}

/**
 * WORKER ROUTINE (WATCHDOG)
 * Runs on a dedicated high-priority thread to monitor and restart logcat.
 */
void *LogEngine::workerRoutine(void *arg) {
    setpriority(PRIO_PROCESS, 0, -10); // Boost priority to minimize capture latency
    std::unique_ptr<ThreadArgs> tArgs(static_cast<ThreadArgs *>(arg));
    LogEngine *engine = tArgs->engine;

    while (likely(engine->m_running.load(std::memory_order_acquire))) {
        engine->runLogcatIteration(tArgs->cmd, tArgs->kotlin_write_fd);

        // If engine is still running but iteration stopped, it's a crash; restart.
        if (!engine->m_running.load(std::memory_order_acquire)) break;
        usleep(500000); // Prevent CPU spin in case of persistent command failure
    }

    close(tArgs->kotlin_write_fd);
    return nullptr;
}

/**
 * RUN LOGCAT ITERATION
 * Forks a child process to run the logcat command and pipes its output.
 */
void LogEngine::runLogcatIteration(const std::string &cmd, int kotlin_fd) {
    int raw_p[2]; // Pipe for raw logcat output
    if (pipe(raw_p) < 0) return;

    fcntl(raw_p[0], F_SETFL, O_NONBLOCK);
    m_internal_raw_read_fd.store(raw_p[0]);

    pid_t child_pid = fork();
    if (child_pid == 0) {
        // Child: Redirect stdout/stderr to pipe and exec shell
        close(raw_p[0]);
        dup2(raw_p[1], STDOUT_FILENO);
        dup2(raw_p[1], STDERR_FILENO);
        execl("/system/bin/sh", "sh", "-c", cmd.c_str(), nullptr);
        _exit(1);
    }

    // Parent: Read and process the stream
    close(raw_p[1]);
    processLogStream(child_pid, raw_p[0], kotlin_fd);

    // Cleanup child process
    if (child_pid > 0) {
        kill(child_pid, SIGKILL);
        int status;
        waitpid(child_pid, &status, 0); // Synchronous reap to avoid zombies
    }

    int ifd = m_internal_raw_read_fd.exchange(-1);
    if (ifd != -1) close(ifd);
}

/**
 * PROCESS LOG STREAM
 * Core logic: uses epoll for non-blocking I/O and std::string_view for zero-copy parsing.
 */
void LogEngine::processLogStream(pid_t child_pid, int read_fd, int kotlin_fd) {
    int epoll_fd = epoll_create1(0);
    if (unlikely(epoll_fd < 0)) return;

    struct epoll_event ev{}, events[1];
    ev.events = EPOLLIN;
    ev.data.fd = read_fd;
    epoll_ctl(epoll_fd, EPOLL_CTL_ADD, read_fd, &ev);

    auto read_buf = std::make_unique<char[]>(READ_BUFFER_SIZE);
    std::string accumulator;
    accumulator.reserve(READ_BUFFER_SIZE * 2);

    while (likely(m_running.load(std::memory_order_acquire))) {
        int nfds = epoll_wait(epoll_fd, events, 1, EPOLL_TIMEOUT_MS);

        if (unlikely(nfds < 0)) {
            if (errno == EINTR) continue;
            break;
        }

        if (nfds == 0) { // Timeout: Check if child is still alive
            int status;
            if (waitpid(child_pid, &status, WNOHANG) != 0) break;
            continue;
        }

        ssize_t bytes = read(read_fd, read_buf.get(), READ_BUFFER_SIZE);
        if (unlikely(bytes <= 0)) break;

        accumulator.append(read_buf.get(), bytes);

        /**
         * FAST PARSING
         * Scanning for newlines and using string_view to avoid allocations.
         */
        size_t pos = 0, next;
        while ((next = accumulator.find('\n', pos)) != std::string::npos) {
            std::string_view line(&accumulator[pos], next - pos);

            bool match = true;
            // Hot-path Regex filtering with Spinlock protection
            if (m_regex_ready.load(std::memory_order_acquire)) {
                while (m_regex_lock.test_and_set(std::memory_order_acquire));
                match = std::regex_search(line.begin(), line.end(), m_regex);
                m_regex_lock.clear(std::memory_order_release);
            }

            if (match) {
                // BACKPRESSURE HANDLING: If Kotlin is slow, safeWrite will drop the frame
                if (unlikely(safeWrite(kotlin_fd, &accumulator[pos], (next - pos) + 1) < 0)) break;
            }
            pos = next + 1;
        }
        accumulator.erase(0, pos);

        // Safety: Prevent memory leak if log stream has no newlines
        if (unlikely(accumulator.size() > READ_BUFFER_SIZE * 4)) accumulator.clear();
    }
    close(epoll_fd);
}

/**
 * SAFE WRITE
 * Non-blocking write to the Kotlin pipe. Handles EAGAIN to prevent blocking the engine.
 */
ssize_t LogEngine::safeWrite(int fd, const char *buf, size_t len) {
    size_t total = 0;
    while (total < len) {
        ssize_t s = write(fd, buf + total, len - total);
        if (unlikely(s <= 0)) {
            if (errno == EINTR) continue;
            // EAGAIN means the 1MB buffer is full. We return to avoid blocking.
            if (errno == EAGAIN || errno == EWOULDBLOCK) return (ssize_t) total;
            return -1; // Severe pipe error
        }
        total += s;
    }
    return (ssize_t) total;
}

/**
 * SET REGEX PATTERN
 * Thread-safe regex compilation using a spinlock.
 */
void LogEngine::setPattern(const std::string &pattern) {
    while (m_regex_lock.test_and_set(std::memory_order_acquire));
    try {
        if (pattern.empty()) {
            m_regex_ready.store(false);
        } else {
            // C++17 'optimize' flag improves matching speed for high-volume logs
            m_regex = std::regex(pattern, std::regex_constants::ECMAScript |
                                          std::regex_constants::icase |
                                          std::regex_constants::optimize);
            m_regex_ready.store(true);
        }
    } catch (...) {
        m_regex_ready.store(false);
    }
    m_regex_lock.clear(std::memory_order_release);
}

void LogEngine::updateRegex(const std::string &r) { setPattern(r); }

/**
 * UPDATE LITERAL
 * Escapes regex special characters to perform a safe plain-text search.
 */
void LogEngine::updateLiteral(const std::string &t) {
    static const std::string spec = R"(\^$.*+?()[]{}|)";
    std::string esc;
    esc.reserve(t.size() * 2);
    for (char c: t) {
        if (spec.find(c) != std::string::npos) esc += '\\';
        esc += c;
    }
    setPattern(esc);
}