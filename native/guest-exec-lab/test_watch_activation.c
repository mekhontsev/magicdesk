#define _GNU_SOURCE
#include "../guest-runtime/src/event_wait.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

/* Negative native controls. A successful assertion of a kernel restriction is
 * not a claim that runtime watch activation already handles that restriction. */
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL activation:%d %s errno=%d\n", \
    __LINE__, #x, errno); exit(1); } } while (0)
static int install(int number, unsigned action, unsigned flags) {
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, number, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, action),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {sizeof(rules) / sizeof(*rules), rules};
    return syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, flags, &program);
}
struct peer { int ready[2], release[2]; atomic_int tid; };
static void event(int fd) {
    /* EVENT_WAIT: explicit peer handshake, deadline is fixture failure. */
    CHECK(md_event_wait_fd(fd, POLLIN, md_event_now() + 5000000000LL) >= 0);
}
static void *diverge(void *opaque) {
    struct peer *peer = opaque;
    atomic_store(&peer->tid, syscall(SYS_gettid));
    CHECK(!install(-1, SECCOMP_RET_ALLOW, 0));
    CHECK(write(peer->ready[1], "r", 1) == 1);
    event(peer->release[0]); char byte;
    CHECK(read(peer->release[0], &byte, 1) == 1 && byte == 'g');
    return NULL;
}
int main(void) {
    CHECK(getuid() == 2000);
    CHECK(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    CHECK(!install(-1, SECCOMP_RET_ALLOW, 0));
    struct peer peer;
    atomic_init(&peer.tid, 0);
    CHECK(!pipe2(peer.ready, O_CLOEXEC) && !pipe2(peer.release, O_CLOEXEC));
    pthread_t thread; CHECK(!pthread_create(&thread, NULL, diverge, &peer));
    event(peer.ready[0]); char byte;
    CHECK(read(peer.ready[0], &byte, 1) == 1 && byte == 'r');
    int result = install(-1, SECCOMP_RET_ALLOW, SECCOMP_FILTER_FLAG_TSYNC);
    CHECK(result == atomic_load(&peer.tid));
    printf("OBSERVED activation limit: divergent peer rejects TSYNC with its TID=%d\n", result);
    CHECK(write(peer.release[1], "g", 1) == 1);
    /* EVENT_WAIT: join the explicitly released peer. The runner's outer bound
     * cancels the entire fixture if this finite shutdown cannot complete. */
    CHECK(!pthread_join(thread, NULL));
    CHECK(!install(-1, SECCOMP_RET_ALLOW, SECCOMP_FILTER_FLAG_TSYNC));
    close(peer.ready[0]); close(peer.ready[1]); close(peer.release[0]); close(peer.release[1]);

    int listener = install(SYS_getuid, SECCOMP_RET_USER_NOTIF, SECCOMP_FILTER_FLAG_NEW_LISTENER);
    CHECK(listener >= 0);
    errno = 0;
    CHECK(install(SYS_getppid, SECCOMP_RET_USER_NOTIF, SECCOMP_FILTER_FLAG_NEW_LISTENER) == -1
        && errno == EBUSY);
    CHECK(!install(SYS_getppid, SECCOMP_RET_USER_NOTIF, 0));
    errno = 0; CHECK(syscall(SYS_getppid) == -1 && errno == ENOSYS);
    puts("OBSERVED activation limit: second listener EBUSY; listenerless filter does not inherit one");
    close(listener);
    return 0;
}
