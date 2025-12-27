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

/**
 * COMPILER BRANCH HINTS
 * likely/unlikely macros assist the CPU branch predictor to optimize
 * high-frequency execution paths.
 */
#define likely(x)       __builtin_expect(!!(x), 1)
#define unlikely(x)     __builtin_expect(!!(x), 0)

// High-capacity buffer for heavy I/O throughput
static constexpr size_t READ_BUFFER_SIZE = 128 * 1024;
// Balanced timeout for epoll to maintain responsiveness and CPU efficiency
static constexpr int EPOLL_TIMEOUT_MS = 200;

LogEngine::LogEngine() {
    /**
     * SIGNAL MANAGEMENT
     * SIGCHLD: Ignored to prevent zombie processes from child logcat forks.
     * SIGPIPE: Ignored to prevent crash if the Kotlin-side pipe closes unexpectedly.
     */
    signal(SIGCHLD, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
}

LogEngine::~LogEngine() {
    stop();
}

// --- CONTROL LAYER ---

int LogEngine::start(const LogConfig &cfg) {
    // Ensure thread-safe single-instance execution
    if (m_running.exchange(true)) return -1;

    int p_kt[2];
    if (pipe(p_kt) < 0) {
        m_running.store(false);
        return -1;
    }

    /**
     * PIPE OPTIMIZATION
     * Increase pipe buffer size to 1MB to prevent blocking under high-velocity logging.
     */
    fcntl(p_kt[1], F_SETPIPE_SZ, 1024 * 1024);

    m_config = cfg;
    if (!cfg.customRegex.empty()) updateRegex(cfg.customRegex);

    // Constructing the logcat command based on filter parameters
    std::string cmd = "/system/bin/logcat -v time";
    if (!m_config.pid.empty()) cmd += " --pid=" + m_config.pid;
    cmd += (m_config.tagFilter.empty()) ? " *:" + m_config.level : " " + m_config.tagFilter;

    auto args = new ThreadArgs{this, p_kt[1], cmd};
    if (pthread_create(&m_thread, nullptr, workerRoutine, args) != 0) {
        close(p_kt[0]);
        close(p_kt[1]);
        delete args;
        m_running.store(false);
        return -1;
    }
    return p_kt[0]; // Return the read-end File Descriptor to Kotlin
}

void LogEngine::stop() {
    /**
     * ATOMIC STATE CHANGE
     * We use release memory order to ensure all prior writes
     * are visible to the worker thread.
     */
    if (!m_running.exchange(false, std::memory_order_release)) return;

    /**
     * CLEANUP INTERNAL READ PIPE
     * Replacing shutdown() with close() as per Linux pipe semantics.
     * Closing the read-end will trigger EPOLLHUP/EOF in the worker loop,
     * effectively unblocking epoll_wait and read() calls.
     */
    int fd = m_internal_raw_read_fd.exchange(-1);
    if (fd != -1) {
        // Just close is enough for pipes to signal EOF to listeners
        close(fd);
    }

    if (m_thread) {
        // Wait for the background thread to finish its watchdog cycle
        pthread_join(m_thread, nullptr);
        m_thread = 0;
    }
}

// --- NATIVE WORKER LAYER (WATCHDOG IMPLEMENTATION) ---

void *LogEngine::workerRoutine(void *arg) {
    // Set high process priority for the capture thread
    setpriority(PRIO_PROCESS, 0, -10);
    std::unique_ptr<ThreadArgs> tArgs(static_cast<ThreadArgs *>(arg));
    LogEngine *engine = tArgs->engine;

    /**
     * WATCHDOG LOOP
     * Automatically restarts the logcat process if it terminates unexpectedly
     * as long as the engine is still marked as running.
     */
    while (likely(engine->m_running.load(std::memory_order_acquire))) {
        engine->runLogcatIteration(tArgs->cmd, tArgs->kotlin_write_fd);

        if (!engine->m_running.load(std::memory_order_acquire)) break;

        // Throttling: Delay 0.5s before restart to avoid CPU spinning on command errors
        usleep(500000);
    }

    /**
     * [fdsan Fix]
     * The write-end FD owned by Native is closed EXCLUSIVELY here
     * when the worker thread terminates.
     */
    close(tArgs->kotlin_write_fd);
    return nullptr;
}

void LogEngine::runLogcatIteration(const std::string &cmd, int kotlin_fd) {
    int raw_p[2];
    if (pipe(raw_p) < 0) return;
    fcntl(raw_p[0], F_SETFL, O_NONBLOCK);
    m_internal_raw_read_fd.store(raw_p[0]);

    pid_t child_pid = fork();
    if (child_pid == 0) {
        // Child process: Redirect stdout/stderr and execute logcat
        close(raw_p[0]);
        dup2(raw_p[1], STDOUT_FILENO);
        dup2(raw_p[1], STDERR_FILENO);
        execl("/system/bin/sh", "sh", "-c", cmd.c_str(), nullptr);
        _exit(1);
    }
    close(raw_p[1]);

    // Enter the stream processing loop
    processLogStream(child_pid, raw_p[0], kotlin_fd);

    // Cleanup child process once processing finishes or stops
    kill(child_pid, SIGKILL);
    waitpid(child_pid, nullptr, 0);

    int ifd = m_internal_raw_read_fd.exchange(-1);
    if (ifd != -1) close(ifd);
}

void LogEngine::processLogStream(pid_t child_pid, int read_fd, int kotlin_fd) {
    /**
     * EPOLL MULTIPLEXING SETUP
     * Using epoll to monitor the logcat pipe. This is more power-efficient
     * than busy-waiting.
     */
    int epoll_fd = epoll_create1(0);
    struct epoll_event ev{}, events[1];
    ev.events = EPOLLIN;
    ev.data.fd = read_fd;
    epoll_ctl(epoll_fd, EPOLL_CTL_ADD, read_fd, &ev);

    void *rawPtr = nullptr;
    posix_memalign(&rawPtr, 64, READ_BUFFER_SIZE);
    std::unique_ptr<char, void (*)(void *)> read_buf(static_cast<char *>(rawPtr), free);

    std::string acc;
    acc.reserve(256 * 1024);

    /**
     * MAIN DATA PROCESSING LOOP
     * Optimized to rely on Pipe EOF for exit detection.
     */
    while (likely(m_running.load(std::memory_order_acquire))) {
        int nfds = epoll_wait(epoll_fd, events, 1, EPOLL_TIMEOUT_MS);

        // If epoll times out, we check if the child is still alive as a backup
        if (nfds == 0) {
            if (unlikely(waitpid(child_pid, nullptr, WNOHANG) != 0)) break;
            continue;
        }

        if (unlikely(m_should_flush_accumulator.exchange(false))) acc.clear();

        /**
         * RELIABLE EXIT CONDITION
         * When the logcat process dies, the write-end of the pipe is closed.
         * read() will then return 0 (EOF) or -1 (Error).
         */
        ssize_t bytes = read(read_fd, read_buf.get(), READ_BUFFER_SIZE);
        if (unlikely(bytes <= 0)) break;

        acc.append(read_buf.get(), bytes);

        const char *start = acc.data();
        const char *end = start + acc.size();
        const char *current = start;

        // Vectorized line splitting using SIMD-optimized memchr
        while (current < end) {
            const char *next_nl = static_cast<const char *>(memchr(current, '\n', end - current));
            if (!next_nl) break;

            std::string_view line(current, next_nl - current);
            bool match = true;

            // Atomic regex filtering with Spinlock protection
            if (m_regex_ready.load(std::memory_order_acquire)) {
                while (m_regex_lock.test_and_set(std::memory_order_acquire));
                match = std::regex_search(line.begin(), line.end(), m_regex);
                m_regex_lock.clear(std::memory_order_release);
            }

            if (match) {
                // High-performance bulk write to Kotlin pipe
                safeWrite(kotlin_fd, current, (next_nl - current) + 1);
            }
            current = next_nl + 1;
        }
        acc.erase(0, current - start);
    }

    /**
     * POST-LOOP CLEANUP
     * Ensure epoll is closed. The actual process reaping (waitpid)
     * is handled by the calling function (runLogcatIteration).
     */
    close(epoll_fd);
}

// --- REGEX & UTILITY FUNCTIONS ---

void LogEngine::setPattern(const std::string &pattern) {
    while (m_regex_lock.test_and_set(std::memory_order_acquire));
    try {
        if (pattern.empty()) {
            m_regex_ready.store(false);
        } else {
            // Compile with 'optimize' flag for faster matching at the cost of setup time
            m_regex = std::regex(pattern, std::regex_constants::ECMAScript |
                                          std::regex_constants::icase |
                                          std::regex_constants::optimize);
            m_regex_ready.store(true);
        }
        m_should_flush_accumulator.store(true, std::memory_order_release);
    } catch (...) { m_regex_ready.store(false); }
    m_regex_lock.clear(std::memory_order_release);
}

void LogEngine::updateRegex(const std::string &r) { setPattern(r); }

void LogEngine::updateLiteral(const std::string &t) {
    // Escape regex reserved characters to allow safe plain-text searching
    static const std::string spec = R"(\^$.*+?()[]{}|)";
    std::string esc;
    esc.reserve(t.size() * 2);
    for (char c: t) {
        if (spec.find(c) != std::string::npos) esc += '\\';
        esc += c;
    }
    setPattern(esc);
}

ssize_t LogEngine::safeWrite(int fd, const char *buf, size_t len) {
    size_t total = 0;
    while (total < len) {
        ssize_t s = write(fd, buf + total, len - total);
        if (unlikely(s <= 0)) {
            if (errno == EINTR) continue; // Retry if interrupted by system signal
            return -1;
        }
        total += s;
    }
    return (ssize_t) total;
}
