#define _GNU_SOURCE
#include "process_owner.h"
#include "event_wait.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/signalfd.h>
#include <sys/wait.h>

#define MD_GRACE_NS 2000000000LL
#define MD_REAP_NS 10000000000LL
static const int watched[] = {SIGCHLD, SIGTERM, SIGINT, SIGHUP, SIGQUIT};

long md_process_signals_open(struct md_process_signals *state) {
    struct md_kernel_action action = {0};
    uint64_t mask = (1ULL << (SIGCHLD - 1)) | (1ULL << (SIGTERM - 1)) |
                    (1ULL << (SIGINT - 1)) | (1ULL << (SIGHUP - 1)) | (1ULL << (SIGQUIT - 1));
    long r = RAW4(rt_sigprocmask, SIG_BLOCK, &mask, &state->previous_mask, 8);
    if (r < 0) return r;
    for (unsigned i = 0; i < sizeof(watched) / sizeof(*watched); ++i) {
        r = RAW4(rt_sigaction, watched[i], &action, &state->previous[i], 8);
        if (r < 0) return r;
    }
    r = RAW4(signalfd4, -1, &mask, 8, SFD_CLOEXEC | SFD_NONBLOCK);
    if (r >= 0) state->fd = (int)r;
    return r;
}
long md_process_signals_restore(const struct md_process_signals *state) {
    for (unsigned i = 0; i < sizeof(watched) / sizeof(*watched); ++i) {
        long r = RAW4(rt_sigaction, watched[i], &state->previous[i], 0, 8);
        if (r < 0) return r;
    }
    return RAW4(rt_sigprocmask, SIG_SETMASK, &state->previous_mask, 0, 8);
}
long md_process_signals_read(int fd) {
    int cancel = 0;
    for (;;) {
        struct signalfd_siginfo signal;
        long n = RAW3(read, fd, &signal, sizeof(signal));
        if (n == -EINTR) continue;
        if (n == -EAGAIN) return cancel;
        if (n != sizeof(signal)) return n < 0 ? n : -EIO;
        if (signal.ssi_signo != SIGCHLD && !cancel) cancel = signal.ssi_signo;
    }
}
long md_process_signal(int fd, int signal) {
    long r = RAW4(pidfd_send_signal, fd, signal, 0, 0);
    return r == -ESRCH ? 0 : r;
}
int md_process_status(int status) {
    return (status & 127) ? 128 + (status & 127) : (status >> 8) & 255;
}

long md_process_close_fds(const int *keep, unsigned count) {
    long dir = RAW4(openat, AT_FDCWD, "/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
    if (dir < 0) return dir;
    struct entry { uint64_t ino; int64_t offset; unsigned short size; unsigned char type; char name[]; };
    char buffer[4096];
    long n;
    while ((n = RAW3(getdents64, dir, buffer, sizeof(buffer))) > 0) {
        for (long offset = 0; offset < n;) {
            struct entry *entry = (void *)(buffer + offset);
            if (entry->size < offsetof(struct entry, name) + 1 || entry->size > n - offset) {
                RAW1(close, dir);
                return -EIO;
            }
            offset += entry->size;
            unsigned long fd = 0;
            const char *name = entry->name;
            while (*name >= '0' && *name <= '9' && fd <= INT_MAX) fd = fd * 10 + (*name++ - '0');
            if (*name || fd < 3 || fd > INT_MAX || fd == (unsigned long)dir) continue;
            int retained = 0;
            for (unsigned i = 0; i < count; ++i) if (keep[i] == (int)fd) retained = 1;
            if (!retained) RAW1(close, fd);
        }
    }
    RAW1(close, dir);
    return n < 0 ? n : 0;
}

/* Only cancellation scans proc. waitid proves ownership; pidfds exclude PID reuse.
 * Quiescence is established separately by wait4(ECHILD), never by this snapshot. */
long md_process_signal_children(int signal) {
    long dir = RAW4(openat, AT_FDCWD, "/proc", O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
    if (dir < 0) return dir;
    struct entry { uint64_t ino; int64_t offset; unsigned short size; unsigned char type; char name[]; };
    char buffer[4096];
    long result = 0, n;
    while ((n = RAW3(getdents64, dir, buffer, sizeof(buffer))) > 0) {
        for (long offset = 0; offset < n;) {
            struct entry *entry = (void *)(buffer + offset);
            if (entry->size < offsetof(struct entry, name) + 1 || entry->size > n - offset) {
                result = -EIO;
                goto done;
            }
            offset += entry->size;
            unsigned long pid = 0;
            const char *name = entry->name;
            while (*name >= '0' && *name <= '9' && pid <= INT_MAX) pid = pid * 10 + (*name++ - '0');
            if (*name || !pid || pid > INT_MAX) continue;
            long fd = RAW2(pidfd_open, pid, 0);
            if (fd < 0) {
                if (fd == -ESRCH || fd == -EACCES || fd == -EPERM) continue;
                result = fd;
                goto done;
            }
            siginfo_t info = {0};
            long r = RAW5(waitid, P_PIDFD, fd, &info, WEXITED | WNOHANG | WNOWAIT | __WALL, 0);
            if (!r && !info.si_pid) {
                r = md_process_signal(fd, signal);
                if (!r && signal != SIGKILL) r = md_process_signal(fd, SIGCONT);
            } else if (r == -ECHILD || r == -ESRCH) r = 0;
            RAW1(close, fd);
            if (r < 0) { result = r; goto done; }
        }
    }
    if (n < 0) result = n;
done:
    RAW1(close, dir);
    return result;
}

long md_process_guard(long owner, const char *bootstrap,
                      char **argv, char **env, const struct md_process_signals *inherited) {
    struct md_process_signals signals;
    /* A login shell owns foreground job control inside its inherited PTY session.
     * Headless launches get a private session; neither path changes process ownership. */
    long tty = RAW4(openat, AT_FDCWD, "/dev/tty", O_RDWR | O_NOCTTY | O_CLOEXEC, 0);
    if (tty >= 0) RAW1(close, tty);
    if ((tty < 0 && RAW0(setsid) < 0) || RAW2(prctl, PR_SET_CHILD_SUBREAPER, 1) < 0 ||
        RAW2(prctl, PR_SET_PDEATHSIG, SIGTERM) < 0 || md_process_signals_open(&signals) < 0)
        return 125;
    RAW1(close, inherited->fd);
    if (RAW0(getppid) != owner) return 125;
    long guest = RAW5(clone, SIGCHLD, 0, 0, 0, 0);
    if (guest < 0) return 125;
    if (!guest) {
        RAW1(close, signals.fd);
        if (md_process_signals_restore(inherited) < 0) RAW1(exit_group, 125);
        md_die("execute namespace guest", RAW3(execve, bootstrap, argv, env));
    }
    int failed = md_process_close_fds(&signals.fd, 1) < 0;
    int root_status = 125, root_seen = 0, result = failed ? 125 : 0, phase = failed ? 2 : 0;
    int64_t deadline = failed ? md_event_now() + MD_REAP_NS : INT64_MAX;
    for (;;) {
        long cancel = md_process_signals_read(signals.fd);
        if (cancel && !phase) {
            result = cancel < 0 ? 125 : 128 + cancel;
            phase = 1;
            deadline = md_event_now() + MD_GRACE_NS;
        }
        int status;
        long pid;
        while ((pid = RAW4(wait4, -1, &status, WNOHANG | __WALL, 0)) > 0)
            if (pid == guest) {
                root_status = md_process_status(status); root_seen = 1;
                if ((status & 127) || root_status == 125) {
                    root_status = 125;
                    if (!phase) { result = 125; phase = 1; deadline = md_event_now() + MD_GRACE_NS; }
                }
            }
        if (pid == -ECHILD) return result ? result : root_seen ? root_status : 125;
        if (pid < 0 && pid != -EINTR) return 125;
        if (phase && md_process_signal_children(phase == 1 ? SIGTERM : SIGKILL) < 0)
            return 125; /* No ownership proof means no broad kill fallback. */
        struct pollfd fds[] = {{signals.fd, POLLIN, 0}};
        /* EVENT_WAIT: child exits or owner cancellation/death.
         * Grace expiration escalates TERM to KILL; final expiry reports incomplete cleanup. */
        long r = md_event_wait(fds, 1, deadline);
        if (r == -ETIMEDOUT && phase == 1) {
            phase = 2;
            deadline = md_event_now() + MD_REAP_NS;
        } else if (r < 0) return 125;
    }
}
