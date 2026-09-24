#define _GNU_SOURCE
#include "content_stream.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

enum { SIZE = 512 * 1024 };
struct Result { bool done, ok; int fd; };
static void complete(void *context, uint64_t id, int fd) {
    assert(id == 1);
    struct Result *result = context;
    assert(!result->done);
    result->done = true; result->ok = fd >= 0;
    result->fd = fd < 0 ? -1 : fcntl(fd, F_DUPFD_CLOEXEC, 0);
}
static int memory(void) {
    int fd = syscall(SYS_memfd_create, "stream-test", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    assert(fd >= 0); return fd;
}
static void await(struct wl_event_loop *loop, struct Result *result) {
    // EVENT_WAIT: pipe callbacks; CTest deadline fails a stalled transfer.
    while (!result->done) assert(wl_event_loop_dispatch(loop, -1) >= 0);
}
int main(void) {
    struct wl_event_loop *loop = wl_event_loop_create(); assert(loop);
    for (int reading = 0; reading < 2; ++reading) {
        int pipe[2]; assert(pipe2(pipe, O_CLOEXEC) == 0);
        int source = memory(); assert(ftruncate(source, SIZE) == 0);
        char block[4096]; memset(block, 0x5a, sizeof(block));
        for (int i = 0; i < SIZE; i += sizeof(block)) assert(pwrite(source, block, sizeof(block), i) == sizeof(block));
        pid_t pid = fork(); assert(pid >= 0);
        if (!pid) {
            close(pipe[reading ? 0 : 1]);
            for (int i = 0; i < SIZE; i += sizeof(block)) {
                if (reading) assert(write(pipe[1], block, sizeof(block)) == sizeof(block));
                else {
                    size_t used = 0;
                    while (used < sizeof(block)) { ssize_t n = read(pipe[0], block + used, sizeof(block) - used); assert(n > 0); used += n; }
                    for (size_t j = 0; j < sizeof(block); ++j) assert(block[j] == 0x5a);
                }
            }
            close(pipe[reading ? 1 : 0]); _exit(0);
        }
        close(pipe[reading ? 1 : 0]);
        struct Result result = {0};
        struct MdwContentStream *stream = mdw_stream_create(loop, 1, pipe[reading ? 0 : 1], reading, complete, &result);
        assert(stream);
        if (!reading) assert(mdw_stream_source(stream, source));
        close(source); await(loop, &result); assert(result.ok);
        if (reading) {
            assert(lseek(result.fd, 0, SEEK_END) == SIZE);
            assert(pwrite(result.fd, block, 1, 0) < 0 && errno == EPERM);
            for (int i = 0; i < SIZE; i += sizeof(block)) {
                assert(pread(result.fd, block, sizeof(block), i) == sizeof(block));
                for (size_t j = 0; j < sizeof(block); ++j) assert(block[j] == 0x5a);
            }
        }
        close(result.fd);
        int status; assert(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    }
    int pipe[2]; assert(pipe2(pipe, O_CLOEXEC) == 0);
    struct Result rejected = {0};
    struct MdwContentStream *stream = mdw_stream_create(loop, 1, pipe[1], false, complete, &rejected);
    int source = memory(); assert(ftruncate(source, 128 * 1024 * 1024 + 1) == 0);
    assert(!mdw_stream_source(stream, source)); close(source); mdw_stream_cancel(stream);
    assert(rejected.done && !rejected.ok); close(pipe[0]);
    assert(pipe2(pipe, O_CLOEXEC) == 0); close(pipe[0]);
    struct sigaction old, action = {.sa_handler = SIG_DFL}; sigemptyset(&action.sa_mask);
    assert(sigaction(SIGPIPE, &action, &old) == 0);
    struct Result vanished = {0}; source = memory(); assert(write(source, "x", 1) == 1);
    stream = mdw_stream_create(loop, 1, pipe[1], false, complete, &vanished);
    assert(stream && mdw_stream_source(stream, source)); close(source);
    await(loop, &vanished); assert(!vanished.ok);
    struct sigaction current; assert(sigaction(SIGPIPE, NULL, &current) == 0 && current.sa_handler == SIG_DFL);
    assert(sigaction(SIGPIPE, &old, NULL) == 0);
    wl_event_loop_destroy(loop);
    puts("streaming, sealed reads, size limits, cancellation and disappearing receivers passed");
}
