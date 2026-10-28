#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static _Atomic unsigned *state;
static void expired(int signal) {
    (void)signal;
    atomic_store(state, 2);
}
static _Atomic int spawning;
static void *short_thread(void *unused) { return unused; }
static void *births(void *argument) {
    unsigned round=(unsigned)(uintptr_t)argument;
    atomic_store(&spawning,1);
    for (;;) {
        if (round&1) {
            pid_t child=fork(); assert(child>=0);
            if (!child) _exit(17);
            int status; assert(waitpid(child,&status,0)==child && WEXITSTATUS(status)==17);
        } else {
            pthread_t child; assert(!pthread_create(&child,NULL,short_thread,NULL));
            assert(!pthread_join(child,NULL));
        }
    }
    return NULL;
}
static void birth_races(int namespaced) {
    alarm(20);
    if (namespaced) {
        if (geteuid()) assert(!unshare(CLONE_NEWUSER));
        assert(!unshare(CLONE_NEWPID));
        pid_t init=fork(); assert(init>=0);
        if (init) {
            int status; assert(waitpid(init,&status,0)==init && !status);
            puts("PASS worker completions under continuous ptrace stops: PID-scoped birth races");
            return;
        }
        alarm(20);
    }
    assert(!prctl(PR_SET_CHILD_SUBREAPER,1));
    for (unsigned round=0;round<128;++round) {
        pid_t parent=fork(); assert(parent>=0);
        if (!parent) {
            if (round&2) assert(!prctl(PR_SET_DUMPABLE,0));
            pthread_t thread; assert(!pthread_create(&thread,NULL,births,(void *)(uintptr_t)round));
            while (!atomic_load(&spawning)) sched_yield();
            for (unsigned i=0;i<round%16;++i) sched_yield();
            _exit(round&4 ? 77 : 0);
        }
        unsigned parents=0; int status; pid_t pid;
        /* EVENT_WAIT: all own descendants, including reparented fork children;
         * the alarm fails leaked newborns or unreleased terminal stops. */
        while ((pid=waitpid(-1,&status,0))>0) {
            assert(WIFEXITED(status));
            assert(WEXITSTATUS(status)==(pid==parent ? (round&4 ? 77 : 0) : 17));
            parents+=pid==parent;
        }
        assert(errno==ECHILD && parents==1);
    }
    if (namespaced) _exit(0);
    puts("PASS worker completions under continuous ptrace stops: birth, exit, protected callers, exact reaping");
}

int main(int argc, char **argv) {
    if (argc==2 && (!strcmp(argv[1],"birth") || !strcmp(argv[1],"birth-namespace"))) {
        birth_races(!strcmp(argv[1],"birth-namespace")); return 0;
    }
    int descriptors=argc==2 && !strcmp(argv[1],"descriptors");
    int helpers=argc==2 && !strcmp(argv[1],"helpers");
    assert(argc==1 || descriptors || helpers);
    enum { CAPACITY = 48 };
    unsigned workers=helpers ? CAPACITY : 16;
    state = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    assert(state != MAP_FAILED);
    atomic_init(state, 0);
    struct sigaction action = {.sa_handler = expired};
    assert(!sigaction(SIGALRM, &action, NULL));
    int gate[2];
    assert(!pipe2(gate, O_CLOEXEC));
    pid_t children[CAPACITY];
    for (unsigned i = 0; i < workers; ++i) {
        children[i] = fork();
        assert(children[i] >= 0);
        if (!children[i]) {
            close(gate[1]);
            char byte;
            /* EVENT_WAIT: parent starts the syscall producers together;
             * the runner's outer deadline cancels failed setup. */
            assert(read(gate[0], &byte, 1) == 1);
            close(gate[0]);
            alarm(10);
            while (!atomic_load(state)) {
                if (descriptors) {
                    assert(syscall(SYS_close,-1)==-1 && errno==EBADF);
                } else {
                    uid_t real, effective, saved;
                    assert(!syscall(SYS_getresuid, &real, &effective, &saved));
                }
            }
            _exit(0);
        }
    }
    close(gate[0]);
    char bytes[CAPACITY] = {0};
    assert(write(gate[1], bytes, workers) == (ssize_t)workers);
    close(gate[1]);
    /* These task-affine proc opens need worker completion, unlike the hot
     * identity calls. Neither stream may wait for the other to become idle. */
    for (unsigned i = 0; i < 32; ++i) {
        int fd = open("/proc/self/status", O_RDONLY | O_CLOEXEC);
        assert(fd >= 0);
        char buffer[1024];
        assert(read(fd, buffer, sizeof(buffer)) > 0);
        assert(!close(fd));
        if (helpers) {
            /* A private mapping helper competes with more hot tasks than the
             * general wait batch. Its checkpoints must not depend on idleness. */
            size_t page=(size_t)sysconf(_SC_PAGESIZE);
            fd=memfd_create("fair-helper",MFD_CLOEXEC);
            assert(fd>=0 && !ftruncate(fd,page));
            unsigned char *memory=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE,fd,0);
            assert(memory!=MAP_FAILED && !close(fd));
            memory[0]=(unsigned char)i;
            assert(!mprotect(memory,page,PROT_READ|PROT_EXEC) && memory[0]==i);
            assert(!munmap(memory,page));
        }
    }
    unsigned previous = atomic_exchange(state, 1);
    for (unsigned i = 0; i < workers; ++i) {
        int status;
        /* EVENT_WAIT: every owned producer exits; outer deadline bounds cleanup. */
        assert(waitpid(children[i], &status, 0) == children[i]);
        assert(WIFEXITED(status) && !WEXITSTATUS(status));
    }
    assert(previous == 0);
    assert(!munmap(state, 4096));
    puts("PASS worker completions under continuous ptrace stops");
    return 0;
}
