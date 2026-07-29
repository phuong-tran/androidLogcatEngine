#include "LogEngine.hpp"
#include <unistd.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <sys/epoll.h>
#include <sys/resource.h>
#include <sys/eventfd.h>
#include <csignal>
#include <cstring>
#include <cstdlib>
#include <cerrno>
#include <string_view>
#include <memory>
#include <string>
#include <algorithm>
#include <sstream>
#include <cctype>
#include <limits.h>
#include <android/log.h>

#define TAG "LogcatEngine-Native"

#define likely(x)       __builtin_expect(!!(x), 1)
#define unlikely(x)     __builtin_expect(!!(x), 0)

static constexpr size_t READ_BUFFER_SIZE = 128 * 1024;
static constexpr int EPOLL_TIMEOUT_MS = 200;

namespace {

bool isValidPid(const std::string& pid) {
    return pid.empty() || std::all_of(pid.begin(), pid.end(), [](unsigned char c) {
        return std::isdigit(c);
    });
}

bool isValidLevel(const std::string& level) {
    return level.size() == 1 && std::strchr("VDIWEFS", level[0]) != nullptr;
}

bool appendTagFilterArgs(const std::string& tagFilter,
                         const std::string& level,
                         std::vector<std::string>& args) {
    if (tagFilter.empty()) {
        args.push_back("*:" + level);
        return true;
    }

    std::istringstream stream(tagFilter);
    std::string token;
    bool hasToken = false;
    while (stream >> token) {
        if (!token.empty() && token[0] == '-') {
            return false;
        }
        args.push_back(token);
        hasToken = true;
    }
    return hasToken;
}

void setCloseOnExec(int fd) {
    int flags = fcntl(fd, F_GETFD);
    if (flags != -1) {
        fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
    }
}

void blockSigpipeForCurrentThread() {
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, SIGPIPE);
    pthread_sigmask(SIG_BLOCK, &set, nullptr);
}

void unblockSigpipeInForkedChild() {
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, SIGPIPE);
    sigprocmask(SIG_UNBLOCK, &set, nullptr);
}

void closeIfValid(int fd) {
    if (fd != -1) close(fd);
}

} // namespace

LogEngine::LogEngine() {
}

LogEngine::~LogEngine() {
    stop();
}

int LogEngine::start(const LogConfig &cfg) {
    if (m_running.exchange(true)) return -1; // Prevent multiple instances

    int p_kt[2]; // Pipe between Native and Kotlin
    if (pipe(p_kt) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "start(): pipe() failed: %s", strerror(errno));
        m_running.store(false);
        return -1;
    }
    setCloseOnExec(p_kt[0]);
    setCloseOnExec(p_kt[1]);

    int writeFlags = fcntl(p_kt[1], F_GETFL);
    if (writeFlags != -1 && fcntl(p_kt[1], F_SETFL, writeFlags | O_NONBLOCK) == -1) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "start(): F_SETFL O_NONBLOCK failed: %s", strerror(errno));
    }

    // Best-effort burst buffer. Android may reject this for unprivileged apps.
    if (fcntl(p_kt[1], F_SETPIPE_SZ, 1024 * 1024) == -1) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "start(): F_SETPIPE_SZ failed: %s", strerror(errno));
        // Not fatal, continue with default pipe size
    }

    int stopFd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (stopFd == -1) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "start(): eventfd() failed: %s", strerror(errno));
        close(p_kt[0]);
        close(p_kt[1]);
        m_running.store(false);
        return -1;
    }
    m_stop_event_fd.store(stopFd, std::memory_order_release);

    m_config = cfg;
    if (m_config.level.empty()) {
        m_config.level = "D";
    } else if (m_config.level.size() == 1) {
        m_config.level[0] = static_cast<char>(std::toupper(
                static_cast<unsigned char>(m_config.level[0])));
    }

    if (!isValidPid(m_config.pid) || !isValidLevel(m_config.level)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "start(): invalid pid or log level");
        close(p_kt[0]);
        close(p_kt[1]);
        closeIfValid(m_stop_event_fd.exchange(-1, std::memory_order_acq_rel));
        m_running.store(false);
        return -1;
    }

    std::vector<std::string> args = {"/system/bin/logcat", "-v", "time"};
    if (!m_config.pid.empty()) args.push_back("--pid=" + m_config.pid);
    if (!appendTagFilterArgs(m_config.tagFilter, m_config.level, args)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "start(): invalid tag filter");
        close(p_kt[0]);
        close(p_kt[1]);
        closeIfValid(m_stop_event_fd.exchange(-1, std::memory_order_acq_rel));
        m_running.store(false);
        return -1;
    }

    switch (m_config.filterMode) {
        case FilterMode::Literal:
            updateLiteral(m_config.customFilter);
            break;
        case FilterMode::Regex:
            updateRegex(m_config.customFilter);
            break;
        case FilterMode::None:
            updateRegex("");
            break;
    }

    auto threadArgs = new ThreadArgs{this, p_kt[1], std::move(args)};
    if (pthread_create(&m_thread, nullptr, workerRoutine, threadArgs) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to create worker thread: %s",
                            strerror(errno));
        close(p_kt[0]);
        close(p_kt[1]);
        closeIfValid(m_stop_event_fd.exchange(-1, std::memory_order_acq_rel));
        delete threadArgs;
        m_running.store(false);
        return -1;
    }
    return p_kt[0]; // Kotlin will read from this FD
}

void LogEngine::stop() {
    bool wasRunning = m_running.exchange(false, std::memory_order_acq_rel);
    if (!wasRunning && !m_thread) return;

    int stopFd = m_stop_event_fd.load(std::memory_order_acquire);
    if (stopFd != -1) {
        uint64_t wakeValue = 1;
        ssize_t ignored = write(stopFd, &wakeValue, sizeof(wakeValue));
        (void) ignored;
    }

    if (m_thread) {
        pthread_join(m_thread, nullptr);
        m_thread = 0;
    }

    closeIfValid(m_stop_event_fd.exchange(-1, std::memory_order_acq_rel));
    std::shared_ptr<const LogFilter> emptyFilter;
    std::atomic_store_explicit(&m_filter, emptyFilter, std::memory_order_release);
}

void *LogEngine::workerRoutine(void *arg) {
    blockSigpipeForCurrentThread();
    setpriority(PRIO_PROCESS, 0, -10); // Boost priority to minimize capture latency
    std::unique_ptr<ThreadArgs> tArgs(static_cast<ThreadArgs *>(arg));
    LogEngine *engine = tArgs->engine;

    while (likely(engine->m_running.load(std::memory_order_acquire))) {
        int stopFd = engine->m_stop_event_fd.load(std::memory_order_acquire);
        engine->runLogcatIteration(tArgs->args, tArgs->kotlin_write_fd, stopFd);

        // If engine is still running but iteration stopped, it's a crash; restart.
        if (!engine->m_running.load(std::memory_order_acquire)) break;
        usleep(500000); // Prevent CPU spin in case of persistent command failure
    }

    close(tArgs->kotlin_write_fd);
    return nullptr;
}

void LogEngine::runLogcatIteration(const std::vector<std::string> &args, int kotlin_fd, int stop_fd) {
    int raw_p[2]; // Pipe for raw logcat output
    if (pipe(raw_p) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "runLogcatIteration(): pipe() failed: %s", strerror(errno));
        return;
    }
    setCloseOnExec(raw_p[0]);

    if (fcntl(raw_p[0], F_SETFL, O_NONBLOCK) == -1) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "runLogcatIteration(): F_SETFL O_NONBLOCK failed: %s", strerror(errno));
        // Not fatal: we still continue, although blocking could increase latency.
    }

    // Build argv before fork. In the child of a multithreaded process we only
    // call async-signal-safe syscalls before execv().
    std::vector<char*> argv;
    argv.reserve(args.size() + 1);
    for (const auto& argValue : args) {
        argv.push_back(const_cast<char*>(argValue.c_str()));
    }
    argv.push_back(nullptr);

    pid_t child_pid = fork();
    if (child_pid == 0) {
        unblockSigpipeInForkedChild();
        close(raw_p[0]);
        if (dup2(raw_p[1], STDOUT_FILENO) == -1 ||
            dup2(raw_p[1], STDERR_FILENO) == -1) {
            _exit(126);
        }
        close(raw_p[1]);

        execv("/system/bin/logcat", argv.data());
        _exit(127);
    }

    if (child_pid < 0) {
        // fork failed
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "runLogcatIteration(): fork() failed: %s", strerror(errno));
        close(raw_p[0]);
        close(raw_p[1]);
        return;
    }

    // Parent: Read and process the stream
    close(raw_p[1]);
    processLogStream(child_pid, raw_p[0], kotlin_fd, stop_fd);

    // Cleanup child process
    if (child_pid > 0) {
        // Try a graceful kill first
        if (kill(child_pid, SIGTERM) == -1 && errno != ESRCH) {
            __android_log_print(ANDROID_LOG_WARN, TAG,
                                "runLogcatIteration(): SIGTERM failed: %s", strerror(errno));
        }

        int status;
        bool childExited = false;
        for (int attempt = 0; attempt < 10; ++attempt) {
            pid_t result = waitpid(child_pid, &status, WNOHANG);
            if (result == child_pid || (result == -1 && errno == ECHILD)) {
                childExited = true;
                break;
            }
            if (result == -1) break;
            usleep(50000);
        }

        if (!childExited) {
            if (kill(child_pid, SIGKILL) == -1 && errno != ESRCH) {
                __android_log_print(ANDROID_LOG_WARN, TAG,
                                    "runLogcatIteration(): SIGKILL failed: %s", strerror(errno));
            }
            if (waitpid(child_pid, &status, 0) == -1 && errno != ECHILD) {
                __android_log_print(ANDROID_LOG_WARN, TAG,
                                    "runLogcatIteration(): waitpid() failed: %s", strerror(errno));
            }
        }
    }

    close(raw_p[0]);
}

void LogEngine::processLogStream(pid_t child_pid, int read_fd, int kotlin_fd, int stop_fd) {
    int epoll_fd = epoll_create1(0);
    if (unlikely(epoll_fd < 0)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "processLogStream(): epoll_create1() failed: %s", strerror(errno));
        return;
    }

    struct epoll_event ev{}, events[2];
    ev.events = EPOLLIN | EPOLLHUP | EPOLLERR;
    ev.data.fd = read_fd;
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, read_fd, &ev) == -1) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "processLogStream(): epoll_ctl(ADD) failed: %s", strerror(errno));
        close(epoll_fd);
        return;
    }

    if (stop_fd != -1) {
        struct epoll_event stopEv{};
        stopEv.events = EPOLLIN | EPOLLHUP | EPOLLERR;
        stopEv.data.fd = stop_fd;
        if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, stop_fd, &stopEv) == -1) {
            __android_log_print(ANDROID_LOG_WARN, TAG,
                                "processLogStream(): epoll_ctl(ADD stop) failed: %s", strerror(errno));
        }
    }

    auto read_buf = std::make_unique<char[]>(READ_BUFFER_SIZE);
    std::string accumulator;
    accumulator.reserve(READ_BUFFER_SIZE * 2);
    bool outputClosed = false;

    while (likely(m_running.load(std::memory_order_acquire))) {
        int nfds = epoll_wait(epoll_fd, events, 2, EPOLL_TIMEOUT_MS);

        if (unlikely(nfds < 0)) {
            if (errno == EINTR) continue;
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                                "processLogStream(): epoll_wait() failed: %s", strerror(errno));
            break;
        }

        if (nfds == 0) { // Timeout: Check if child is still alive
            int status;
            pid_t r = waitpid(child_pid, &status, WNOHANG);
            if (r == -1 && errno != ECHILD) {
                __android_log_print(ANDROID_LOG_WARN, TAG,
                                    "processLogStream(): waitpid(WNOHANG) failed: %s", strerror(errno));
            }
            if (r != 0) break; // child exited or error
            continue;
        }

        for (int i = 0; i < nfds; ++i) {
            if (events[i].data.fd == stop_fd) {
                uint64_t wakeValue;
                while (read(stop_fd, &wakeValue, sizeof(wakeValue)) > 0) {}
                close(epoll_fd);
                return;
            }

            if (events[i].data.fd != read_fd) continue;

            bool inputClosed = false;
            while (true) {
                ssize_t bytes = read(read_fd, read_buf.get(), READ_BUFFER_SIZE);
                if (unlikely(bytes < 0)) {
                    if (errno == EINTR) continue;
                    if (errno == EAGAIN || errno == EWOULDBLOCK) break;
                    __android_log_print(ANDROID_LOG_WARN, TAG,
                                        "processLogStream(): read() failed: %s", strerror(errno));
                    close(epoll_fd);
                    return;
                }

                if (unlikely(bytes == 0)) {
                    inputClosed = true;
                    break;
                }

                accumulator.append(read_buf.get(), static_cast<size_t>(bytes));

                // Acquire pairs with updateRegex/updateLiteral release stores. Each
                // batch sees one immutable filter snapshot without locking the hot path.
                auto filter = std::atomic_load_explicit(&m_filter, std::memory_order_acquire);

                size_t pos = 0, next;
                while ((next = accumulator.find('\n', pos)) != std::string::npos) {
                    std::string_view line(&accumulator[pos], next - pos);

                    bool match = true;
                    if (filter) {
                        if (filter->type == LogFilter::Type::Regex) {
                            match = std::regex_search(line.begin(), line.end(), filter->regex);
                        } else {
                            match = matchesLiteralCaseInsensitive(line, filter->literal);
                        }
                    }

                    if (match) {
                        ssize_t written = safeWrite(kotlin_fd, &accumulator[pos], (next - pos) + 1);
                        if (unlikely(written < 0)) {
                            __android_log_print(ANDROID_LOG_WARN, TAG,
                                                "processLogStream(): safeWrite() severe error: %s",
                                                strerror(errno));
                            outputClosed = true;
                            m_running.store(false, std::memory_order_release);
                            break;
                        }
                    }
                    pos = next + 1;
                }
                if (outputClosed) break;
                accumulator.erase(0, pos);

                // Bound memory if a producer writes a very long unterminated line.
                if (unlikely(accumulator.size() > READ_BUFFER_SIZE * 4)) accumulator.clear();
            }

            if (inputClosed) {
                close(epoll_fd);
                return;
            }
            if (outputClosed) break;
        }
        if (outputClosed) break;
    }
    close(epoll_fd);
}

ssize_t LogEngine::safeWrite(int fd, const char *buf, size_t len) {
    if (len > PIPE_BUF) {
        return 0;
    }

    while (true) {
        ssize_t s = write(fd, buf, len);
        if (likely(s == static_cast<ssize_t>(len))) return s;
        if (unlikely(s < 0 && errno == EINTR)) continue;
        if (unlikely(s < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))) return 0;
        return -1;
    }
}

/**
 * UPDATE REGEX
 * Compiles outside the hot path and atomically publishes an immutable filter snapshot.
 */
void LogEngine::updateRegex(const std::string &r) {
    try {
        if (r.empty()) {
            std::shared_ptr<const LogFilter> emptyFilter;
            std::atomic_store_explicit(&m_filter, emptyFilter, std::memory_order_release);
        } else {
            // C++17 'optimize' flag improves matching speed for high-volume logs
            auto compiled = std::regex(r, std::regex_constants::ECMAScript |
                                          std::regex_constants::icase |
                                          std::regex_constants::optimize);
            std::shared_ptr<const LogFilter> filter =
                    std::make_shared<LogFilter>(std::move(compiled));
            std::atomic_store_explicit(
                    &m_filter,
                    filter,
                    std::memory_order_release);
        }
    } catch (...) {
        std::shared_ptr<const LogFilter> emptyFilter;
        std::atomic_store_explicit(&m_filter, emptyFilter, std::memory_order_release);
    }
}

/**
 * UPDATE LITERAL
 * Publishes a fast literal search filter without routing through std::regex.
 */
void LogEngine::updateLiteral(const std::string &t) {
    if (t.empty()) {
        std::shared_ptr<const LogFilter> emptyFilter;
        std::atomic_store_explicit(&m_filter, emptyFilter, std::memory_order_release);
        return;
    }

    std::shared_ptr<const LogFilter> filter = std::make_shared<LogFilter>(t);
    std::atomic_store_explicit(
            &m_filter,
            filter,
            std::memory_order_release);
}

bool LogEngine::matchesLiteralCaseInsensitive(std::string_view line, std::string_view literal) {
    if (literal.empty()) return true;
    if (literal.size() > line.size()) return false;

    for (size_t start = 0; start <= line.size() - literal.size(); ++start) {
        bool match = true;
        for (size_t i = 0; i < literal.size(); ++i) {
            auto lineChar = static_cast<unsigned char>(line[start + i]);
            auto literalChar = static_cast<unsigned char>(literal[i]);
            if (std::tolower(lineChar) != std::tolower(literalChar)) {
                match = false;
                break;
            }
        }
        if (match) return true;
    }
    return false;
}
