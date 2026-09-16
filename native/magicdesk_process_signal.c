#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

/* The proc directory pins identity for both inspection and pidfd_send_signal.
 * Never fall back to kill(pid): the PID may have been reused after validation. */
static int signal_process(int pid, unsigned uid, unsigned long long start, int sig) {
    if (pid <= 1 || start == 0 || (sig != SIGTERM && sig != SIGKILL)) { errno = EINVAL; return -1; }
    char path[64], data[4096];
    snprintf(path, sizeof(path), "/proc/%d", pid);
    int dir = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dir < 0) return -1;
    int result = -1;
    struct stat owner;
    if (fstat(dir, &owner) < 0) goto done;
    if (owner.st_uid != uid) { errno = ESTALE; goto done; }
    int fd = openat(dir, "stat", O_RDONLY | O_CLOEXEC);
    if (fd < 0) goto done;
    ssize_t size = read(fd, data, sizeof(data) - 1);
    int read_error = errno;
    close(fd);
    if (size <= 0) { errno = size < 0 ? read_error : ESRCH; goto done; }
    data[size] = 0;
    char *end = strrchr(data, ')'), *save = NULL;
    if (!end) { errno = EINVAL; goto done; }
    char *field = strtok_r(end + 1, " ", &save);
    for (int i = 0; field && i < 19; i++) field = strtok_r(NULL, " ", &save);
    if (!field) { errno = EINVAL; goto done; }
    char *tail;
    errno = 0;
    unsigned long long actual = strtoull(field, &tail, 10);
    if (errno || *tail || actual != start) { errno = ESTALE; goto done; }
#ifdef SYS_pidfd_send_signal
    result = (int) syscall(SYS_pidfd_send_signal, dir, sig, NULL, 0);
#else
    errno = ENOSYS;
#endif
done: ;
    int saved = errno;
    close(dir);
    errno = saved;
    return result;
}

static unsigned long long number(const char *text) {
    char *end;
    errno = 0;
    unsigned long long value = strtoull(text, &end, 10);
    if (errno || !*text || *end || *text == '-') { fprintf(stderr, "Invalid process identity\n"); exit(2); }
    return value;
}

int main(int argc, char **argv) {
    if (argc != 5) { fprintf(stderr, "Expected PID UID START_TICKS SIGNAL\n"); return 2; }
    unsigned long long pid = number(argv[1]), uid = number(argv[2]), start = number(argv[3]), sig = number(argv[4]);
    if (pid > INT_MAX || uid > INT_MAX || sig > INT_MAX) return 2;
    if (signal_process((int) pid, (unsigned) uid, start, (int) sig) == 0) return 0;
    if (errno == ENOSYS || errno == EINVAL || errno == EBADF)
        fprintf(stderr, "Safe process signalling is unavailable on this kernel\n");
    else fprintf(stderr, "Process signal failed: %s\n", strerror(errno));
    return 1;
}
