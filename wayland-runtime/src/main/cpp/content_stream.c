#include "content_stream.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <pthread.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

enum { MAX_BYTES = 128 * 1024 * 1024, BLOCK = 64 * 1024 };
struct MdwContentStream {
    struct wl_event_source *ready, *deadline;
    struct wl_event_loop *loop;
    uint64_t id;
    int pipe, file;
    bool reading;
    size_t position, size, buffered, consumed;
    MdwStreamDone done;
    void *context;
    unsigned char buffer[BLOCK];
};

static void finish(struct MdwContentStream *s, bool success) {
    if (s->ready) wl_event_source_remove(s->ready);
    if (s->deadline) wl_event_source_remove(s->deadline);
    if (success && s->reading) success = fcntl(s->file, F_ADD_SEALS,
        F_SEAL_WRITE | F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_SEAL) == 0;
    close(s->pipe);
    s->done(s->context, s->id, success ? s->file : -1);
    if (s->file >= 0) close(s->file);
    free(s);
}

void mdw_stream_cancel(struct MdwContentStream *s) { if (s) finish(s, false); }
static int expired(void *data) { finish(data, false); return 0; }

static ssize_t write_pipe(int fd, const void *bytes, size_t size) {
    // A disappearing receiver is a transfer failure, not a compositor crash.
    sigset_t blocked, previous, pending;
    sigemptyset(&blocked); sigaddset(&blocked, SIGPIPE);
    if (pthread_sigmask(SIG_BLOCK, &blocked, &previous) != 0) { errno = EIO; return -1; }
    sigpending(&pending);
    ssize_t result = write(fd, bytes, size);
    int error = errno;
    if (result < 0 && error == EPIPE && !sigismember(&pending, SIGPIPE)) {
        const struct timespec immediate = {0};
        // Consume only this write's signal, without changing the process-wide disposition.
        while (sigtimedwait(&blocked, NULL, &immediate) < 0 && errno == EINTR) { }
    }
    pthread_sigmask(SIG_SETMASK, &previous, NULL);
    errno = error;
    return result;
}

static int transfer(int fd, uint32_t mask, void *data) {
    struct MdwContentStream *s = data;
    if (mask & WL_EVENT_ERROR) { finish(s, false); return 0; }
    if (s->reading) {
        ssize_t count = read(fd, s->buffer, BLOCK);
        if (count < 0 && (errno == EAGAIN || errno == EINTR)) return 0;
        if (count <= 0) { finish(s, count == 0); return 0; }
        if ((size_t)count > MAX_BYTES - s->position ||
                pwrite(s->file, s->buffer, count, s->position) != count) {
            finish(s, false); return 0;
        }
        s->position += count;
    } else {
        if (s->consumed == s->buffered) {
            if (s->position == s->size) { finish(s, true); return 0; }
            size_t count = s->size - s->position;
            if (count > BLOCK) count = BLOCK;
            ssize_t read_bytes = pread(s->file, s->buffer, count, s->position);
            if (read_bytes < 0 && errno == EINTR) return 0;
            if (read_bytes <= 0) { finish(s, false); return 0; }
            s->buffered = read_bytes; s->consumed = 0;
        }
        ssize_t count = write_pipe(fd, s->buffer + s->consumed, s->buffered - s->consumed);
        if (count < 0 && (errno == EAGAIN || errno == EINTR)) return 0;
        if (count <= 0) { finish(s, false); return 0; }
        s->consumed += count; s->position += count;
        if (s->position == s->size) { finish(s, true); return 0; }
    }
    return 0;
}

struct MdwContentStream *mdw_stream_create(struct wl_event_loop *loop,
        uint64_t id, int pipe, bool reading, MdwStreamDone done, void *context) {
    struct MdwContentStream *s = calloc(1, sizeof(*s));
    if (!s) { close(pipe); return NULL; }
    *s = (struct MdwContentStream){.loop = loop, .id = id, .pipe = pipe, .file = -1,
        .reading = reading, .done = done, .context = context};
    if (fcntl(pipe, F_SETFL, fcntl(pipe, F_GETFL) | O_NONBLOCK) < 0) goto fail;
    if (reading) {
        s->file = syscall(SYS_memfd_create, "wayland-content", MFD_CLOEXEC | MFD_ALLOW_SEALING);
        if (s->file < 0) goto fail;
        s->ready = wl_event_loop_add_fd(loop, pipe, WL_EVENT_READABLE, transfer, s);
        if (!s->ready) goto fail;
    }
    // EVENT_WAIT: pipe readiness/source reply; deadline rejects an incomplete transfer.
    s->deadline = wl_event_loop_add_timer(loop, expired, s);
    if (!s->deadline || wl_event_source_timer_update(s->deadline, 30000) < 0) goto fail;
    return s;
fail:
    if (s->ready) wl_event_source_remove(s->ready);
    if (s->deadline) wl_event_source_remove(s->deadline);
    if (s->file >= 0) close(s->file);
    close(pipe); free(s); return NULL;
}

bool mdw_stream_source(struct MdwContentStream *s, int fd) {
    struct stat st;
    if (!s || s->reading || s->file >= 0 || fd < 0 || fstat(fd, &st) < 0 ||
            !S_ISREG(st.st_mode) || st.st_size < 0 || st.st_size > MAX_BYTES) return false;
    s->file = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (s->file < 0) return false;
    s->size = st.st_size;
    s->ready = wl_event_loop_add_fd(s->loop, s->pipe, WL_EVENT_WRITABLE, transfer, s);
    return s->ready != NULL;
}
