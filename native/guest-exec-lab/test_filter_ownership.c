#define _GNU_SOURCE
#include "seccomp_policy.h"
#include "seccomp_install.h"
#include "seccomp_capture.h"
#include "interception_memory.h"
#include "trace_memory.h"
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/futex.h>
#include <linux/ptrace.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

enum { TASKS = 16, INSNS = 128, MARKER = 617, COOKIE = 0x4d4450 };
struct arena {
    _Atomic unsigned captured, started, ready, release;
    unsigned race;
    struct sock_filter source[5];
};
struct group { uintptr_t arena, images; };
enum phase { IDLE, INSTALL, BIRTH, EXEC, NATIVE, DENIAL };
struct task {
    pid_t pid;
    unsigned group;
    struct md_seccomp_policy *head;
    struct md_seccomp_capture pending;
    struct md_interception_memory *memory;
    struct md_resource_order turn;
    enum phase phase;
    int queued, held;
    long result;
    struct user_pt_regs original;
};
struct tracer {
    struct task tasks[TASKS];
    struct group groups[TASKS];
    unsigned groups_used, controls, exits, denied, commits, rejected, queued, held_conflicts;
};
static struct arena *arena;
static struct md_interception_buffers *images;
static int image_fd;
static char image_descriptor[24];
static void *peer_images;
static size_t image_size;
static int traced, scenario;
static const char *executable;

static struct task *find(struct tracer *s, pid_t pid) {
    for (unsigned i = 0; i < TASKS; ++i) if (s->tasks[i].pid == pid) return &s->tasks[i];
    return NULL;
}
static struct task *add(struct tracer *s, pid_t pid, unsigned group, struct md_seccomp_policy *head,
        struct md_interception_memory *memory) {
    for (unsigned i = 0; i < TASKS; ++i) if (!s->tasks[i].pid) {
        s->tasks[i] = (struct task){.pid = pid, .group = group, .head = md_seccomp_policy_retain(head),
            .memory = memory ? md_memory_fork(memory, 1) : md_memory_new()};
        assert(s->tasks[i].memory);
        return &s->tasks[i];
    }
    abort();
}
static void resume(pid_t pid, int signal) { assert(!ptrace(PTRACE_CONT, pid, NULL, (void *)(long)signal)); }
static void observed(pid_t pid) { assert(!ptrace(PTRACE_SYSCALL, pid, NULL, NULL)); }
static unsigned field(struct task *t, struct tracer *s, size_t offset) {
    unsigned value; copy_memory(t->pid, s->groups[t->group].arena + offset, &value, sizeof(value), 0); return value;
}
static int release_installer(struct tracer *s, struct md_interception_memory *memory) {
    for (unsigned i = 0; i < TASKS; ++i) {
        struct task *t = &s->tasks[i];
        if (t->pid && t->memory == memory && t->held) {
            t->held = 0; observed(t->pid); return 1;
        }
    }
    return 0;
}
static void retire_turn(struct task *t) {
    enum md_resource_order_state state = md_resource_order_state(&t->turn);
    if (state == MD_RESOURCE_ORDER_IDLE) return;
    assert(!(state == MD_RESOURCE_ORDER_QUEUED || state == MD_RESOURCE_ORDER_READY
        ? md_resource_order_cancel(&t->turn) : md_resource_order_complete(&t->turn)));
}
static unsigned evaluate(struct task *t, const struct seccomp_data *call) {
    unsigned decision;
    assert(!md_seccomp_policy_eval(t->head, call, &decision));
    return decision;
}
static void replace_policy(struct task *t, struct md_seccomp_policy *p) {
    md_seccomp_policy_retain(p);
    md_seccomp_policy_release(t->head); t->head = p;
}
static void release_policy(struct task *t) {
    md_seccomp_capture_discard(&t->pending);
    md_seccomp_policy_release(t->head); t->head = NULL;
    retire_turn(t); md_memory_release(t->memory); t->memory = NULL;
}
static void enter(struct tracer *s, struct task *t) {
    struct user_pt_regs r = registers(t->pid);
    struct group *g = &s->groups[t->group];
    if (r.regs[8] == SYS_gettid) {
        assert(r.regs[0] == COOKIE);
        if (r.regs[1]) { g->arena = r.regs[1]; g->images = r.regs[2]; }
        else release_installer(s, t->memory);
        ++s->controls; resume(t->pid, 0); return;
    }
    int install = md_seccomp_filter_call(r.regs[8], r.regs[0], r.regs[1]);
    int birth = r.regs[8] == SYS_clone;
    int exec = r.regs[8] == SYS_execve;
    int mapping = r.regs[8] == SYS_mmap || r.regs[8] == SYS_munmap
        || r.regs[8] == SYS_mprotect || r.regs[8] == SYS_mremap;
    if (install || birth || exec || mapping) {
        if (md_resource_order_state(&t->turn) == MD_RESOURCE_ORDER_IDLE) {
            int ready = md_memory_order_begin(t->memory, &t->turn, NULL); assert(ready >= 0);
            if (!ready) {
                t->queued = 1; ++s->queued;
                unsigned race = g->arena ? field(t, s, offsetof(struct arena, race)) : 0;
                if ((birth && race == 2) || (mapping && race == 3))
                    s->held_conflicts += release_installer(s, t->memory);
                return;
            }
        }
        assert(!md_resource_order_start(&t->turn));
    }
    ++s->controls; t->original = r;
    struct seccomp_data call = {.nr = r.regs[8], .arch = AUDIT_ARCH_AARCH64, .instruction_pointer = r.pc};
    memcpy(call.args, r.regs, sizeof(call.args));
    unsigned decision = evaluate(t, &call), type = decision & SECCOMP_RET_ACTION_FULL;
    if (type == SECCOMP_RET_ERRNO) {
        t->result = md_seccomp_policy_error(decision); t->phase = DENIAL;
        int nr = -1; struct iovec io = {&nr, sizeof(nr)};
        assert(!ptrace(PTRACE_SETREGSET, t->pid, (void *)NT_ARM_SYSTEM_CALL, &io));
        observed(t->pid); return;
    }
    assert(type == SECCOMP_RET_ALLOW);
    t->phase = birth ? BIRTH : exec ? EXEC : install ? INSTALL : NATIVE;
    if (install) {
        /* Listener creation is covered; handling USER_NOTIF decisions is not. */
        assert(r.regs[8] != SYS_seccomp || !(r.regs[1]
            & ~(SECCOMP_FILTER_FLAG_TSYNC | SECCOMP_FILTER_FLAG_NEW_LISTENER)));
        struct sock_fprog p; copy_memory(t->pid, r.regs[2], &p, sizeof(p), 0);
        assert(p.len && p.len <= INSNS && !t->pending.policy);
        struct sock_filter code[INSNS];
        copy_memory(t->pid, (uintptr_t)p.filter, code, p.len * sizeof(*p.filter), 0);
        p.filter = code;
        const uint32_t delegated[] = {SYS_seccomp, SYS_prctl};
        assert(!md_seccomp_capture_prepare(&t->pending, images, g->images,
            t->head, &p, delegated, 2, 201, NULL));
        r.regs[2] = t->pending.program; set_registers(t->pid, &r);
        /* Only this installer is held. A sibling deliberately changes the
         * source or requests fork after the immutable capture. */
        if (field(t, s, offsetof(struct arena, race)) && !field(t, s, offsetof(struct arena, captured))) {
            unsigned one = 1; copy_memory(t->pid, g->arena + offsetof(struct arena, captured), &one, sizeof(one), 1);
            t->held = 1; return;
        }
    }
    observed(t->pid);
}
static void complete(struct tracer *s, struct task *t) {
    ++s->exits;
    struct ptrace_syscall_info info = {0};
    assert(ptrace(PTRACE_GET_SYSCALL_INFO, t->pid, (void *)sizeof(info), &info) >= 0);
    assert(info.op == PTRACE_SYSCALL_INFO_EXIT);
    struct user_pt_regs r = registers(t->pid);
    if (t->phase == INSTALL) {
        unsigned flags = md_seccomp_filter_flags(t->original.regs[8], t->original.regs[1]);
        struct md_seccomp_policy *committed = NULL;
        int published = md_seccomp_capture_complete(&t->pending, flags, (long)r.regs[0], &committed);
        assert(published >= 0);
        if (published) {
            ++s->commits;
            replace_policy(t, committed);
            if (flags & SECCOMP_FILTER_FLAG_TSYNC)
                for (unsigned i = 0; i < TASKS; ++i)
                    if (s->tasks[i].pid && s->tasks[i].group == t->group) replace_policy(&s->tasks[i], t->head);
        } else ++s->rejected;
        md_seccomp_policy_release(committed);
        unsigned long result = r.regs[0]; r = t->original; r.regs[0] = result;
        set_registers(t->pid, &r);
    } else if (t->phase == DENIAL) {
        r.regs[0] = t->result; set_registers(t->pid, &r);
    }
    retire_turn(t);
    t->phase = IDLE; resume(t->pid, 0);
}
static int supervise(pid_t leader) {
    struct tracer *s = calloc(1, sizeof(*s)); assert(s);
    s->groups_used = 1; add(s, leader, 0, NULL, NULL);
    unsigned living = 1; int leader_status = -1;
    while (living) {
        for (unsigned i = 0; i < TASKS; ++i) {
            struct task *t = &s->tasks[i];
            if (t->pid && t->queued && md_resource_order_state(&t->turn) == MD_RESOURCE_ORDER_READY) {
                t->queued = 0; enter(s, t);
            }
        }
        int status; pid_t pid = waitpid(-1, &status, __WALL); assert(pid > 0);
        unsigned event = (unsigned)status >> 16;
        struct task *t = find(s, pid);
        if (event == PTRACE_EVENT_EXEC) {
            unsigned long old; assert(!ptrace(PTRACE_GETEVENTMSG, pid, NULL, &old));
            if ((pid_t)old != pid) {
                struct task *executing = find(s, old); assert(executing);
                if (t) { release_policy(t); t->pid = 0; --living; }
                t = executing; t->pid = pid;
            }
        }
        assert(t);
        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            if (pid == leader) leader_status = status;
            else assert((WIFEXITED(status) && !WEXITSTATUS(status))
                || (scenario == 16 && WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL));
            release_policy(t); t->pid = 0; --living; continue;
        }
        assert(WIFSTOPPED(status));
        int signal = WSTOPSIG(status);
        if (event == PTRACE_EVENT_SECCOMP) { enter(s, t); continue; }
        if (event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_CLONE || event == PTRACE_EVENT_VFORK) {
            unsigned long child; assert(!ptrace(PTRACE_GETEVENTMSG, pid, NULL, &child));
            unsigned group = t->group;
            if (!(t->original.regs[0] & CLONE_THREAD)) {
                assert(s->groups_used < TASKS); group = s->groups_used++;
                s->groups[group].arena = s->groups[t->group].arena;
                s->groups[group].images = s->groups[t->group].images;
            }
            struct md_interception_memory *memory = md_memory_fork(t->memory, !!(t->original.regs[0] & CLONE_VM));
            assert(memory); add(s, child, group, t->head, memory); md_memory_release(memory); ++living;
            /* Birth, not parent's eventual return, owns filter inheritance. */
            retire_turn(t); observed(pid); continue;
        }
        if (event == PTRACE_EVENT_EXEC) {
            s->groups[t->group].arena = 0;
            s->groups[t->group].images = 0;
            retire_turn(t);
            struct md_interception_memory *memory = md_memory_new_image(t->memory); assert(memory);
            md_memory_release(t->memory); t->memory = memory;
            observed(pid); continue;
        }
        if (signal == (SIGTRAP | 0x80)) { complete(s, t); continue; }
        if (signal == SIGSTOP) {
            assert(!ptrace(PTRACE_SETOPTIONS, pid, NULL, (void *)(PTRACE_O_TRACESECCOMP | PTRACE_O_TRACESYSGOOD |
                PTRACE_O_TRACECLONE | PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK | PTRACE_O_TRACEEXEC | PTRACE_O_EXITKILL)));
            signal = 0;
        } else if (signal == SIGSYS) {
            siginfo_t info; assert(!ptrace(PTRACE_GETSIGINFO, pid, NULL, &info));
            assert(info.si_code == 1 && info.si_syscall == SYS_mount); ++s->denied;
            struct user_pt_regs r = registers(pid);
            struct seccomp_data call = {.nr = info.si_syscall, .arch = info.si_arch,
                .instruction_pointer = (uintptr_t)info.si_call_addr};
            memcpy(call.args, r.regs, sizeof(call.args));
            unsigned decision = evaluate(t, &call);
            unsigned type = decision & SECCOMP_RET_ACTION_FULL;
            assert(type == SECCOMP_RET_ALLOW || type == SECCOMP_RET_ERRNO);
            r.regs[0] = type == SECCOMP_RET_ALLOW ? MARKER : md_seccomp_policy_error(decision);
            set_registers(pid, &r); signal = 0;
        }
        resume(pid, signal);
    }
    printf("OWNERSHIP_STOPS control=%u exits=%u denied=%u commits=%u rejected=%u queued=%u\n",
        s->controls, s->exits, s->denied, s->commits, s->rejected, s->queued);
    if (scenario == 7 || scenario == 9) assert(s->controls == 2 && s->exits == 1 && s->denied == 1);
    if (scenario == 8 || scenario == 17 || scenario == 18) assert(s->held_conflicts == 1);
    free(s); return leader_status;
}
static void notify(void) { syscall(SYS_gettid, COOKIE, 0); }
static void prepare_arena(void) {
    arena = mmap(NULL, sizeof(*arena), PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(arena != MAP_FAILED);
    struct stat st; assert(!fstat(image_fd, &st));
    void *peer = mmap(NULL, st.st_size, PROT_READ, MAP_SHARED, image_fd, 0);
    assert(peer != MAP_FAILED);
    peer_images = peer; image_size = st.st_size;
    errno = 0;
    assert(mprotect(peer, st.st_size, PROT_READ | PROT_WRITE) == -1 && errno == EACCES);
    errno = 0;
    assert(mmap(NULL, st.st_size, PROT_READ | PROT_WRITE, MAP_SHARED, image_fd, 0) == MAP_FAILED && errno == EPERM);
    errno = 0; char byte = 0;
    assert(pwrite(image_fd, &byte, 1, 0) == -1 && errno == EPERM);
}
static void announce_arena(void) { syscall(SYS_gettid, COOKIE, arena, peer_images); }
static long probe(void) {
    errno = 0; long value = syscall(traced ? SYS_mount : SYS_getppid, 0, 0, 0, 0, 0, 0);
    long result = value < 0 ? -errno : traced ? value : MARKER;
    /* A kernel-only witness prevents a correct replay result from hiding
     * a different program captured by the actual kernel filter chain. */
    errno = 0; long native = syscall(SYS_getppid);
    assert(result == (native < 0 ? -errno : MARKER));
    return result;
}
static void make_policy(struct sock_filter code[5], unsigned error) {
    code[0] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
    code[1] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, traced ? SYS_mount : SYS_getppid, 1, 0);
    code[2] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getppid, 0, 1);
    code[3] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | error);
    code[4] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
}
static long install(unsigned error, unsigned flags, int prctl_api) {
    struct sock_filter code[5]; make_policy(code, error);
    struct sock_fprog p = {5, code};
    return prctl_api ? prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &p) : syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, flags, &p);
}
static void set_flag(_Atomic unsigned *p, unsigned value) {
    atomic_store(p, value); syscall(SYS_futex, p, FUTEX_WAKE, INT32_MAX, NULL, NULL, 0);
}
static void await_flag(_Atomic unsigned *p) {
    /* EVENT_WAIT: fixture peer completes the named phase; suite alarm bounds failure. */
    while (!atomic_load(p)) syscall(SYS_futex, p, FUTEX_WAIT, 0, NULL, NULL, 0);
}
static void wait_capture(void) {
    /* Parent ptrace writes do not issue a guest futex wake. This bounded
     * fixture handshake yields; production admission has no such polling. */
    while (!atomic_load(&arena->captured)) sched_yield();
}
static void *mutation_peer(void *unused) {
    (void)unused; set_flag(&arena->started, 1); wait_capture();
    arena->source[3].k = SECCOMP_RET_ERRNO | EPERM; notify(); return NULL;
}
static void *death_peer(void *unused) {
    (void)unused; set_flag(&arena->started, 1); wait_capture();
    assert(!kill(getpid(), SIGKILL)); abort();
}
static void *mapping_peer(void *unused) {
    (void)unused; set_flag(&arena->started, 1); wait_capture();
    assert(mmap(peer_images, image_size, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0) == peer_images);
    struct md_seccomp_image *forged = peer_images;
    make_policy(forged->code, EPERM);
    forged->program = (struct sock_fprog){5, forged->code};
    set_flag(&arena->ready, 1); await_flag(&arena->release);
    assert(mmap(peer_images, image_size, PROT_READ, MAP_SHARED | MAP_FIXED, image_fd, 0) == peer_images);
    return NULL;
}
static int mapping_process(void *unused) { mapping_peer(unused); return 0; }
static void *policy_peer(void *opaque) {
    unsigned private_error = (uintptr_t)opaque;
    if (private_error) assert(!install(private_error, 0, 1));
    set_flag(&arena->ready, (unsigned)syscall(SYS_gettid)); await_flag(&arena->release);
    assert(probe() == -(long)(private_error ? private_error : EACCES)); return NULL;
}
static void exec_child(unsigned expected) {
    char code[16]; snprintf(code, sizeof(code), "%u", expected);
    execl(executable, executable, "exec-child", traced ? "1" : "0", code, image_descriptor, NULL); abort();
}
static void *exec_peer(void *unused) { (void)unused; assert(!install(EBUSY, 0, 0)); exec_child(EBUSY); return NULL; }
static pthread_barrier_t install_start;
static void *install_peer(void *opaque) {
    unsigned error = (uintptr_t)opaque;
    pthread_barrier_wait(&install_start);
    for (unsigned i = 0; i < 8; ++i) assert(!install(error, 0, i % 2) && probe() == -(long)error);
    return NULL;
}
static void *fork_peer(void *unused) {
    (void)unused; set_flag(&arena->started, 1); wait_capture();
    pid_t child = fork(); assert(child >= 0);
    if (!child) { assert(probe() == -EACCES); _exit(0); }
    int status; assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(probe() == -EACCES); return NULL;
}
static void io_loop(unsigned count) {
    int fds[2]; assert(!pipe(fds)); struct timespec a, b;
    assert(!clock_gettime(CLOCK_MONOTONIC, &a));
    for (unsigned i = 0; i < count; ++i) { char v = 7, r; assert(write(fds[1], &v, 1) == 1 && read(fds[0], &r, 1) == 1 && r == v); }
    assert(!clock_gettime(CLOCK_MONOTONIC, &b));
    printf("OWNERSHIP_IO traced=%d count=%u ns=%lld\n", traced, count,
        (long long)(b.tv_sec - a.tv_sec) * 1000000000 + b.tv_nsec - a.tv_nsec);
    close(fds[0]); close(fds[1]);
}
static void selector(void) {
    const unsigned calls[] = {SYS_seccomp, SYS_prctl, SYS_clone, SYS_execve,
        SYS_mmap, SYS_mprotect, SYS_mremap, SYS_munmap};
    struct sock_filter code[32]; unsigned count = 0;
    code[count++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
    for (unsigned i = 0; i < sizeof(calls) / sizeof(*calls); ++i) {
        code[count++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, calls[i], 0, 1);
        code[count++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | 201);
    }
    const struct sock_filter control[] = {
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_gettid, 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, COOKIE, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | 201),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    memcpy(code + count, control, sizeof(control)); count += sizeof(control) / sizeof(*control);
    struct sock_fprog p = {count, code};
    assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p));
}
static void installer_pointer(int prctl_api) {
    struct sock_filter code[5]; make_policy(code, EACCES);
    struct sock_fprog p = {5, code}, wrong = p;
    uintptr_t address = (uintptr_t)&p;
    struct sock_filter guard_code[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, prctl_api ? SYS_prctl : SYS_seccomp, 0, 4),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[2])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)address, 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[2]) + 4),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, address >> 32, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
    };
    struct sock_fprog guard = {sizeof(guard_code) / sizeof(*guard_code), guard_code};
    assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &guard));
    assert(!(prctl_api ? prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &p)
        : syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p)) && probe() == -EACCES);
    errno = 0;
    assert((prctl_api ? prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &wrong)
        : syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &wrong)) == -1 && errno == EPERM);
    assert(probe() == -EACCES);
}
static void exercise(void) {
    sigset_t empty; sigemptyset(&empty); assert(!sigprocmask(SIG_SETMASK, &empty, NULL));
    prepare_arena();
    if (traced) { assert(!ptrace(PTRACE_TRACEME, 0, NULL, NULL)); assert(!raise(SIGSTOP)); }
    assert(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    if (traced) selector();
    announce_arena();
    if (scenario == 17 || scenario == 18) {
        arena->race = 3;
        pthread_t peer = 0; pid_t child = -1; void *stack = NULL;
        size_t stack_size = 1024 * 1024;
        if (scenario == 18) {
            stack = mmap(NULL, stack_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            assert(stack != MAP_FAILED);
            child = clone(mapping_process, (char *)stack + stack_size, CLONE_VM | SIGCHLD, NULL);
            assert(child > 0);
        } else assert(!pthread_create(&peer, NULL, mapping_peer, NULL));
        await_flag(&arena->started);
        assert(!install(EACCES, 0, 0));
        if (!traced) set_flag(&arena->captured, 1);
        await_flag(&arena->ready);
        assert(probe() == -EACCES);
        set_flag(&arena->release, 1);
        if (child > 0) {
            int status; assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
            assert(!munmap(stack, stack_size));
        } else assert(!pthread_join(peer, NULL));
        assert(!install(EBUSY, 0, 1) && probe() == -EBUSY); return;
    }
    if (scenario == 16) {
        arena->race = 1;
        pthread_t peer; assert(!pthread_create(&peer, NULL, death_peer, NULL));
        await_flag(&arena->started);
        assert(!install(EACCES, 0, 0));
        if (!traced) set_flag(&arena->captured, 1);
        pthread_join(peer, NULL); abort();
    }
    if (scenario == 12 || scenario == 13) { installer_pointer(scenario == 13); return; }
    if (scenario == 14 || scenario == 15) {
        if (scenario == 15) close(STDIN_FILENO);
        int listener = install(EACCES, SECCOMP_FILTER_FLAG_NEW_LISTENER, 0);
        assert(scenario == 15 ? listener == 0 : listener > 0);
        assert(probe() == -EACCES && !close(listener));
        assert(!install(EPERM, 0, 1) && probe() == -EPERM); return;
    }
    if (scenario == 10) {
        assert(!pthread_barrier_init(&install_start, NULL, 2));
        pthread_t a, b; assert(!pthread_create(&a, NULL, install_peer, (void *)(uintptr_t)EACCES));
        assert(!pthread_create(&b, NULL, install_peer, (void *)(uintptr_t)EPERM));
        assert(!pthread_join(a, NULL) && !pthread_join(b, NULL) && probe() == MARKER);
        assert(!pthread_barrier_destroy(&install_start)); return;
    }
    if (scenario == 1) {
        make_policy(arena->source, EACCES); arena->race = 1;
        pthread_t peer; assert(!pthread_create(&peer, NULL, mutation_peer, NULL));
        await_flag(&arena->started);
        struct sock_fprog p = {5, arena->source}; assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p));
        if (!traced) set_flag(&arena->captured, 1);
        assert(!pthread_join(peer, NULL) && arena->source[3].k == (SECCOMP_RET_ERRNO | EPERM)
            && probe() == -EACCES); return;
    }
    if (scenario == 2 || scenario == 3) {
        pthread_t peer; assert(!pthread_create(&peer, NULL, policy_peer, (void *)(uintptr_t)(scenario == 3 ? EBUSY : 0)));
        await_flag(&arena->ready);
        long result = install(EACCES, SECCOMP_FILTER_FLAG_TSYNC, 0);
        assert(result == (scenario == 3 ? atomic_load(&arena->ready) : 0));
        assert(probe() == (scenario == 3 ? MARKER : -EACCES));
        set_flag(&arena->release, 1); assert(!pthread_join(peer, NULL)); return;
    }
    if (scenario == 8) {
        pthread_t peer; assert(!pthread_create(&peer, NULL, fork_peer, NULL)); arena->race = 2;
        await_flag(&arena->started);
        assert(!install(EACCES, SECCOMP_FILTER_FLAG_TSYNC, 0));
        if (!traced) set_flag(&arena->captured, 1);
        assert(!pthread_join(peer, NULL) && probe() == -EACCES); return;
    }
    assert(!install(EACCES, 0, 0) && probe() == -EACCES);
    if (scenario == 11) {
        pid_t child = vfork(); assert(child >= 0);
        if (!child) {
            execl(executable, executable, "exec-child", traced ? "1" : "0", "13", image_descriptor, NULL); _exit(127);
        }
        int status; assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
        assert(probe() == -EACCES);
    } else if (scenario == 0) {
        struct sock_filter bad[] = {BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 1), BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)};
        struct sock_fprog p = {2, bad}; errno = 0;
        assert(syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p) == -1 && errno == EINVAL);
        assert(probe() == -EACCES && !install(EPERM, 0, 1) && probe() == -EPERM);
    } else if (scenario == 4 || scenario == 5) {
        pid_t child = fork(); assert(child >= 0);
        if (!child) {
            assert(probe() == -EACCES);
            if (scenario == 5) exec_child(EACCES);
            assert(!install(EPERM, 0, 1) && probe() == -EPERM); _exit(0);
        }
        int status; assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
        assert(probe() == -EACCES);
    } else if (scenario == 6) {
        pthread_t peer; assert(!pthread_create(&peer, NULL, exec_peer, NULL)); pthread_join(peer, NULL); abort();
    } else io_loop(scenario == 7 ? 1024 : 65536);
}
int main(int argc, char **argv) {
    setbuf(stdout, NULL); alarm(45); executable = argv[0];
    if (argc == 5 && !strcmp(argv[1], "exec-child")) {
        image_fd = atoi(argv[4]); snprintf(image_descriptor, sizeof(image_descriptor), "%d", image_fd);
        traced = atoi(argv[2]); prepare_arena(); announce_arena();
        assert(probe() == -(long)atoi(argv[3]));
        assert(!install(EPERM, 0, 1) && probe() == -EPERM); return 0;
    }
    const char *names[] = {"chain-reject", "snapshot-race", "tsync", "tsync-failure", "fork", "exec", "thread-exec", "io-small", "fork-tsync", "io-large", "concurrent-install", "vfork-exec", "seccomp-pointer", "prctl-pointer", "listener", "listener-zero", "installer-death", "mapping-race", "shared-mm-race"};
    unsigned passed = 0;
    for (scenario = 0; scenario < (int)(sizeof(names) / sizeof(*names)); ++scenario) {
        for (traced = 0; traced <= 1; ++traced) {
            printf("OWNERSHIP_BEGIN name=%s traced=%d\n", names[scenario], traced);
            assert(!md_buffers_create_readonly(TASKS, sizeof(struct md_seccomp_image), &images));
            /* Explicit fixture transport across exec. The child must not inherit
             * the pre-seal writer mapping, even temporarily during guest work. */
            image_fd = fcntl(md_buffers_fd(images), F_DUPFD, 3); assert(image_fd >= 0);
            snprintf(image_descriptor, sizeof(image_descriptor), "%d", image_fd);
            pid_t child = fork(); assert(child >= 0);
            if (!child) { md_buffers_release(images); images = NULL; exercise(); _exit(0); }
            close(image_fd);
            int status;
            if (traced) status = supervise(child); else assert(waitpid(child, &status, 0) == child);
            int ok = scenario == 16 ? WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL
                : WIFEXITED(status) && !WEXITSTATUS(status);
            printf("%s ownership=%s traced=%d status=%#x\n", ok ? "PASS" : "FAIL", names[scenario], traced, status);
            assert(ok); ++passed;
            /* Every completed or reaped installer returned its loan. */
            struct md_interception_buffer loans[TASKS] = {0};
            for (unsigned i = 0; i < TASKS; ++i) assert(!md_buffers_take(images, &loans[i]));
            for (unsigned i = 0; i < TASKS; ++i) assert(!md_buffer_release(&loans[i]));
            md_buffers_release(images); images = NULL;
        }
    }
    printf("OWNERSHIP_SUMMARY passed=%u integration=not-implemented\n", passed);
}
