#define _GNU_SOURCE
#include "seccomp_policy.h"
#include "seccomp_install.h"
#include "seccomp_capture.h"
#include "seccomp_reply.h"
#include "signal_actions.h"
#include "signal_view.h"
#include "fd_object.h"
#include <assert.h>
#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

static void actions(void) {
    struct md_signal_actions *parent=md_signal_actions_new(); assert(parent);
    assert(!md_signal_actions_exclusive(NULL) && md_signal_actions_exclusive(parent));
    struct md_kernel_action value;
    assert(md_signal_actions_read(parent,SIGUSR1,&value)==-ENODATA);
    assert(md_signal_actions_caught(parent,SIGUSR1)==-ENODATA);
    struct md_signal_actions *initial=md_signal_actions_exec(NULL); assert(initial);
    assert(!md_signal_actions_caught(initial,SIGCHLD));
    assert(md_signal_actions_read(initial,SIGCHLD,&value)==-ENODATA);
    md_signal_actions_release(initial);
    struct md_kernel_action caught={.handler=0x1000,.flags=SA_RESETHAND|SA_RESTART,
        .restorer=0x2000,.mask=MD_SIGNAL_SYS_BIT};
    struct md_kernel_action ignored={.handler=(uintptr_t)SIG_IGN,.flags=SA_RESTART,
        .restorer=0x3000,.mask=MD_SIGNAL_SYS_BIT};
    assert(!md_signal_actions_commit(parent,SIGUSR1,&caught));
    assert(!md_signal_actions_commit(parent,SIGSYS,&ignored));
    assert(md_signal_actions_caught(parent,SIGUSR1)==1);
    assert(!md_signal_actions_caught(parent,SIGSYS));
    struct md_signal_actions *shared=md_signal_actions_fork(parent,1,0);
    struct md_signal_actions *forked=md_signal_actions_fork(parent,0,0);
    assert(shared==parent && forked && forked!=parent);
    assert(!md_signal_actions_exclusive(parent) && md_signal_actions_exclusive(forked));
    assert(!md_signal_actions_enter(shared,SIGUSR1,&value) && value.handler==caught.handler);
    assert(!md_signal_actions_read(parent,SIGUSR1,&value) && value.handler==(uintptr_t)SIG_DFL);
    assert(value.flags==caught.flags && value.mask==caught.mask && value.restorer==caught.restorer);
    assert(!md_signal_actions_read(forked,SIGUSR1,&value) && value.handler==caught.handler);
    assert(!md_signal_actions_caught(parent,SIGUSR1));
    assert(md_signal_actions_caught(forked,SIGUSR1)==1);
    assert(md_signal_actions_enter(parent,SIGUSR1,&value)==-EINVAL);
    const uintptr_t entry=0x4000;
    struct md_kernel_action masked=caught;
    masked.mask|=UINT64_C(1)<<(SIGUSR2-1);
    value=md_signal_view_native(SIGUSR1,masked,entry);
    assert(value.handler==entry && value.mask==(UINT64_C(1)<<(SIGUSR2-1)));
    assert(value.flags==(masked.flags|SA_SIGINFO) && value.restorer==masked.restorer);
    value=md_signal_view_native(SIGSYS,masked,entry);
    assert(value.mask==(UINT64_C(1)<<(SIGUSR2-1)) && (value.flags&SA_NODEFER));
    value=md_signal_view_native(SIGUSR1,masked,0);
    assert(value.handler==masked.handler && value.mask==(UINT64_C(1)<<(SIGUSR2-1)));
    value=md_signal_view_action(SIGUSR1,value,&masked,0);
    assert(value.handler==masked.handler && value.mask==masked.mask && value.flags==masked.flags);
    value=md_signal_view_native(SIGUSR1,caught,entry); value.handler=(uintptr_t)SIG_DFL;
    value=md_signal_actions_decode(parent,SIGUSR1,value,entry);
    assert(value.handler==(uintptr_t)SIG_DFL && value.flags==caught.flags && value.mask==caught.mask);
    struct md_signal_actions *exec=md_signal_actions_exec(forked);
    struct md_signal_actions *cleared=md_signal_actions_fork(forked,0,1);
    assert(exec && cleared);
    struct md_signal_actions *reset[]={exec,cleared};
    for (unsigned i=0;i<2;++i) {
        assert(!md_signal_actions_read(reset[i],SIGUSR1,&value));
        assert(!value.handler && !value.flags && !value.restorer && !value.mask);
        assert(!md_signal_actions_read(reset[i],SIGSYS,&value));
        assert(value.handler==(uintptr_t)SIG_IGN && !value.flags && !value.restorer && !value.mask);
        assert(md_signal_actions_read(reset[i],SIGUSR2,&value)==-ENODATA);
        assert(!md_signal_actions_caught(reset[i],SIGUSR2));
        assert(!md_signal_actions_commit(reset[i],SIGUSR2,&caught));
        assert(md_signal_actions_caught(reset[i],SIGUSR2)==1);
        md_signal_actions_release(reset[i]);
    }
    assert(!md_signal_actions_fork(parent,1,1) && errno==EINVAL);
    assert(md_signal_actions_commit(parent,SIGKILL,&caught)==-EINVAL);
    assert(md_signal_actions_commit(parent,NSIG,&caught)==-EINVAL);
    md_signal_actions_release(parent);
    assert(md_signal_actions_exclusive(shared));
    assert(!md_signal_actions_commit(shared,SIGUSR1,&caught));
    md_signal_actions_release(shared); md_signal_actions_release(forked);
    puts("PASS signal action ownership: unknown state, CLONE_SIGHAND, fork, clear, exec and reset normalization");
}

struct activity_test { struct md_fd_object object; unsigned changes; int active; };
static void activity_changed(struct md_fd_object *object,int active) {
    struct activity_test *t=(void *)object;
    ++t->changes; t->active=active;
}
static void activity_destroy(struct md_fd_object *object) { (void)object; }
static void policy_users(void) {
    static const struct md_fd_object_type type={.destroy=activity_destroy};
    struct activity_test activity={0};
    md_fd_object_init(&activity.object,&type);
    struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={1,code};
    struct md_seccomp_policy *parent=NULL,*next=NULL,*left=NULL,*right=NULL;
    assert(!md_seccomp_policy_capture(NULL,&program,&parent));
    assert(!md_seccomp_policy_bind_listener(parent,&activity.object,activity_changed));
    assert(activity.changes==1 && !activity.active);
    md_seccomp_policy_replace(&left,parent);
    md_seccomp_policy_replace(&right,parent);
    assert(activity.changes==2 && activity.active);
    for (unsigned i=0;i<1024;++i) {
        assert(!md_seccomp_policy_capture(left,&program,&next));
        md_seccomp_policy_replace(&left,next);
        md_seccomp_policy_release(next);
    }
    assert(activity.changes==2 && activity.active);
    md_seccomp_policy_replace(&right,NULL);
    assert(activity.changes==2 && activity.active);
    md_seccomp_policy_replace(&left,NULL);
    assert(activity.changes==3 && !activity.active);
    /* Descriptor destruction can precede the last task without retaining or
     * dereferencing a dead listener. A capture alone is not a running task. */
    md_seccomp_policy_replace(&left,parent);
    assert(activity.changes==4 && activity.active);
    md_fd_object_release(&activity.object);
    md_seccomp_policy_replace(&left,NULL);
    md_seccomp_policy_release(parent);
    puts("PASS filter users: fork, descendants, atomic head replacement, last-task HUP and closed listener");
}

static void replies(void) {
    const struct seccomp_notif_resp cases[] = {
        {.id=17,.val=333}, {.id=17,.error=-ERANGE}, {.id=17,.error=42,.val=1},
        {.id=17,.flags=SECCOMP_USER_NOTIF_FLAG_CONTINUE}, {.id=17,.val=INT64_MIN},
    };
    for (unsigned i=0;i<sizeof(cases)/sizeof(*cases);++i) for (unsigned order=0;order<4;++order) {
        struct md_seccomp_replies receiver={.id=17};
        struct md_seccomp_reply *sender=NULL;
        struct seccomp_notif_resp native;
        assert(md_seccomp_reply_prepare(&receiver,&cases[i],&native,&sender)==1);
        assert(native.id==17 && native.val>INT32_MAX && !native.error && !native.flags);
        if (order<2) { md_seccomp_reply_complete(sender,order==0,0); sender=NULL; }
        long value; int continuing;
        assert(md_seccomp_reply_result(&receiver,native.val,&value,&continuing)==1);
        assert(value==(cases[i].error ? cases[i].error : cases[i].val));
        assert(continuing==!!cases[i].flags);
        md_seccomp_replies_clear(&receiver);
        if (sender) md_seccomp_reply_complete(sender,order==2,0);
    }
    struct md_seccomp_replies receiver={.id=17};
    struct md_seccomp_reply *a=NULL,*b=NULL;
    struct seccomp_notif_resp first,second;
    assert(md_seccomp_reply_prepare(&receiver,&cases[0],&first,&a)==1);
    assert(md_seccomp_reply_prepare(&receiver,&cases[1],&second,&b)==1);
    assert(first.val!=second.val);
    md_seccomp_reply_complete(a,1,-ENOENT);
    long value; int continuing;
    assert(!md_seccomp_reply_result(&receiver,first.val,&value,&continuing));
    assert(md_seccomp_reply_result(&receiver,second.val,&value,&continuing)==1 && value==-ERANGE);
    md_seccomp_replies_clear(&receiver);
    md_seccomp_reply_complete(b,1,-ENOENT);
    struct seccomp_notif_resp invalid={.id=17,.flags=4};
    a=NULL;
    assert(!md_seccomp_reply_prepare(&receiver,&invalid,&first,&a) && !a && first.flags==4);
    invalid.flags=SECCOMP_USER_NOTIF_FLAG_CONTINUE; invalid.val=1;
    assert(!md_seccomp_reply_prepare(&receiver,&invalid,&first,&a) && !a);
    invalid.flags=0;
    assert(md_seccomp_reply_prepare(&receiver,&invalid,&first,&a)==-ENOENT);
    assert(!md_seccomp_reply_result(&receiver,-EINTR,&value,&continuing) && value==-EINTR && !continuing);
    puts("PASS notification replies: kernel commit witness, sender death, cancellation and completion order");
}

static void capture(void) {
    struct md_interception_buffers *pool = NULL, *ordinary = NULL, *small = NULL;
    assert(!md_buffers_create_readonly(1, sizeof(struct md_seccomp_image), &pool));
    assert(md_buffers_peer_readonly(pool));
    int fd = md_buffers_fd(pool);
    size_t size = md_buffers_size(pool);
    void *peer = mmap(NULL, size, PROT_READ, MAP_SHARED, fd, 0);
    assert(peer != MAP_FAILED);
    int seals = fcntl(fd, F_GET_SEALS);
    assert((seals & (F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL | F_SEAL_FUTURE_WRITE))
        == (F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL | F_SEAL_FUTURE_WRITE));
    errno = 0;
    assert(mprotect(peer, size, PROT_READ | PROT_WRITE) == -1 && errno == EACCES);
    errno = 0;
    assert(mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0) == MAP_FAILED && errno == EPERM);
    errno = 0; char byte = 0;
    assert(pwrite(fd, &byte, 1, 0) == -1 && errno == EPERM);
    struct sock_filter code[] = {BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES)};
    struct sock_fprog source = {1, code};
    const uint32_t calls[] = {SYS_seccomp, SYS_prctl};
    struct md_seccomp_capture c = {0}, blocked = {0};
    uintptr_t base = (uintptr_t)peer;
    assert(!md_seccomp_capture_prepare(&c, pool, base, NULL, &source, calls, 2, 201, NULL));
    assert(md_seccomp_capture_prepare(&c, pool, base, NULL, &source, calls, 2, 201, NULL) == -EBUSY);
    assert(md_seccomp_capture_prepare(&blocked, pool, base, NULL, &source, calls, 2, 201, NULL) == -EAGAIN);
    const struct md_seccomp_image *image = (void *)c.program;
    assert(image->program.filter == image->code && image->program.len == 7);
    code[0].k = SECCOMP_RET_ERRNO | EPERM;
    struct seccomp_data call = {.nr = SYS_read}; uint32_t decision;
    assert(!md_seccomp_program_eval(&image->program, &call, &decision) && decision == (SECCOMP_RET_ERRNO | EACCES));
    struct md_seccomp_policy *committed = NULL;
    /* A second owner may still be reading the snapshot after native completion. */
    struct md_interception_buffer pin = {0};
    assert(!md_buffer_pin(&c.buffer, &pin));
    assert(md_seccomp_capture_complete(&c, 0, 0, &committed) == 1);
    assert(!c.policy && !c.buffer.pool && !c.program);
    assert(!md_seccomp_policy_eval(committed, &call, &decision) && decision == (SECCOMP_RET_ERRNO | EACCES));
    assert(md_seccomp_capture_prepare(&blocked, pool, base, NULL, &source, calls, 2, 201, NULL) == -EAGAIN);
    assert(!md_buffer_release(&pin));
    md_seccomp_policy_release(committed); committed = NULL;
    const struct { unsigned flags; long result; int published; } results[] = {
        {0, -EINVAL, 0}, {SECCOMP_FILTER_FLAG_TSYNC, 123, 0},
        {SECCOMP_FILTER_FLAG_NEW_LISTENER, 0, 1}, {SECCOMP_FILTER_FLAG_NEW_LISTENER, 5, 1},
    };
    for (unsigned i = 0; i < sizeof(results) / sizeof(*results); ++i) {
        assert(!md_seccomp_capture_prepare(&c, pool, base, NULL, &source, calls, 2, 201, NULL));
        assert(md_seccomp_capture_complete(&c, results[i].flags, results[i].result, &committed) == results[i].published);
        assert(!c.buffer.pool && !!committed == results[i].published);
        md_seccomp_policy_release(committed); committed = NULL;
    }
    assert(!md_seccomp_capture_prepare(&c, pool, base, NULL, &source, calls, 2, 201, NULL));
    md_seccomp_capture_discard(&c); md_seccomp_capture_discard(&c);
    assert(md_seccomp_capture_prepare(&c, pool, UINTPTR_MAX - 8, NULL, &source, calls, 2, 201, NULL) == -EOVERFLOW);
    assert(md_seccomp_capture_prepare(&c, pool, base, NULL, &source, NULL, 2, 201, NULL) == -EINVAL);
    assert(!c.buffer.pool && !c.policy && !c.program);
    assert(!md_buffers_create(1, sizeof(struct md_seccomp_image), 0, 0, &ordinary));
    assert(!md_buffers_peer_readonly(ordinary));
    assert(md_seccomp_capture_prepare(&c, ordinary, base, NULL, &source, calls, 2, 201, NULL) == -EINVAL);
    assert(!md_buffers_create_readonly(1, 1, &small));
    assert(md_seccomp_capture_prepare(&c, small, base, NULL, &source, calls, 2, 201, NULL) == -ENOSPC);
    assert(!md_seccomp_capture_prepare(&c, pool, base, NULL, &source, calls, 2, 201, NULL));
    md_buffers_release(pool); /* The pending operation, not this reference, owns the slot. */
    image = (void *)c.program;
    assert(!md_seccomp_program_eval(&image->program, &call, &decision) && decision == (SECCOMP_RET_ERRNO | EPERM));
    md_seccomp_capture_discard(&c);
    assert(!munmap(peer, size)); md_buffers_release(ordinary); md_buffers_release(small);
    puts("PASS immutable capture transport: seals, pinning, exhaustion, completion, rejection and reuse");
}

static int evaluate(struct sock_filter *code, unsigned count, uint32_t *result) {
    struct sock_fprog p = {.len = count, .filter = code};
    struct seccomp_data call = {0};
    return md_seccomp_program_eval(&p, &call, result);
}
static void ownership(void) {
    struct sock_filter code[] = {BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES)};
    struct sock_fprog program = {1, code};
    struct md_seccomp_policy *parent = NULL, *child = NULL;
    struct seccomp_data call = {.nr = SYS_getpid};
    uint32_t result;
    assert(!md_seccomp_policy_capture(NULL, &program, &parent));
    assert(!md_seccomp_policy_may_trace(NULL) && !md_seccomp_policy_may_trace(parent));
    code[0].k = SECCOMP_RET_ERRNO | EPERM;
    assert(!md_seccomp_policy_eval(parent, &call, &result) && result == (SECCOMP_RET_ERRNO | EACCES));
    assert(!md_seccomp_policy_capture(parent, &program, &child));
    md_seccomp_policy_release(parent);
    assert(!md_seccomp_policy_eval(child, &call, &result) && result == (SECCOMP_RET_ERRNO | EPERM));
    struct md_seccomp_policy *sibling = md_seccomp_policy_retain(child);
    code[0].k = SECCOMP_RET_ALLOW;
    assert(!md_seccomp_policy_capture(child, &program, &parent));
    md_seccomp_policy_release(child);
    assert(!md_seccomp_policy_eval(parent, &call, &result) && result == (SECCOMP_RET_ERRNO | EPERM));
    md_seccomp_policy_release(parent);
    assert(!md_seccomp_policy_eval(sibling, &call, &result) && result == (SECCOMP_RET_ERRNO | EPERM));
    md_seccomp_policy_release(sibling);
    parent=NULL; child=NULL;
    code[0].k=SECCOMP_RET_TRACE|55;
    assert(!md_seccomp_policy_capture(NULL,&program,&parent) && md_seccomp_policy_may_trace(parent));
    code[0].k=SECCOMP_RET_ALLOW;
    assert(!md_seccomp_policy_capture(parent,&program,&child) && md_seccomp_policy_may_trace(child));
    md_seccomp_policy_release(parent); md_seccomp_policy_release(child);
    assert(!md_seccomp_policy_eval(NULL, &call, &result) && result == SECCOMP_RET_ALLOW);
    parent = NULL;
    for (unsigned i = 0; i < 8192; ++i) {
        assert(!md_seccomp_policy_capture(parent, &program, &child));
        md_seccomp_policy_release(parent); parent = child;
    }
    md_seccomp_policy_release(parent);
    puts("PASS immutable policy copies, shared ancestry, branch retirement and iterative release");
}
static void routing(void) {
    struct sock_filter code[] = {BPF_STMT(BPF_RET | BPF_A, 0)};
    struct sock_fprog source = {1, code};
    struct md_seccomp_policy *policy = NULL;
    assert(!md_seccomp_policy_capture(NULL, &source, &policy));
    assert(md_seccomp_policy_may_trace(policy));
    struct sock_filter routed[16], saved[16]; memset(routed, 0x5a, sizeof(routed));
    memcpy(saved, routed, sizeof(routed));
    const uint32_t calls[] = {SYS_seccomp, SYS_prctl};
    assert(md_seccomp_policy_route(policy, calls, SIZE_MAX, 201, routed, 16, NULL) == -E2BIG);
    assert(md_seccomp_policy_route(policy, calls, 2, 201, routed, 6, NULL) == -ENOSPC);
    assert(!memcmp(saved, routed, sizeof(routed)));
    int count = md_seccomp_policy_route(policy, calls, 2, 201, routed, 16, NULL);
    assert(count == 7);
    struct sock_fprog p = {count, routed}; uint32_t result;
    struct seccomp_data call = {.nr = SYS_seccomp};
    assert(!md_seccomp_program_eval(&p, &call, &result) && result == (SECCOMP_RET_TRACE | 201));
    call.nr = SYS_prctl;
    assert(!md_seccomp_program_eval(&p, &call, &result) && result == (SECCOMP_RET_TRACE | 201));
    call.nr = SYS_read;
    assert(!md_seccomp_program_eval(&p, &call, &result) && !result);
    assert(routed[5].code == (BPF_ALU | BPF_AND | BPF_K) && !routed[5].k);
    const struct md_seccomp_route scope={.delegation_pc=UINT64_C(0x123456780004)};
    count=md_seccomp_policy_route(policy,calls,2,201,routed,16,&scope);
    assert(count==13);
    p=(struct sock_fprog){count,routed};
    for (unsigned i=0;i<2;++i) {
        call=(struct seccomp_data){.nr=calls[i],.instruction_pointer=scope.delegation_pc};
        assert(!md_seccomp_program_eval(&p,&call,&result) && result==(SECCOMP_RET_TRACE|201));
        call.instruction_pointer^=UINT64_C(1)<<32;
        assert(!md_seccomp_program_eval(&p,&call,&result) && !result);
        call.instruction_pointer=scope.delegation_pc+4;
        assert(!md_seccomp_program_eval(&p,&call,&result) && !result);
    }
    call=(struct seccomp_data){.nr=SYS_read,.instruction_pointer=scope.delegation_pc};
    assert(!md_seccomp_program_eval(&p,&call,&result) && !result);
    assert(routed[5].code==(BPF_JMP|BPF_JA) && routed[5].k==5);
    md_seccomp_policy_release(policy);
    struct sock_filter *large = calloc(BPF_MAXINSNS, sizeof(*large)); assert(large);
    source = (struct sock_fprog){BPF_MAXINSNS, large};
    assert(!md_seccomp_policy_capture(NULL, &source, &policy));
    assert(md_seccomp_policy_route(policy, calls, 2, 201, routed, 16, NULL) == -E2BIG);
    struct sock_filter *exact=malloc(sizeof(*exact)*BPF_MAXINSNS); assert(exact);
    assert(md_seccomp_policy_route(policy,NULL,0,0,exact,BPF_MAXINSNS,NULL)==BPF_MAXINSNS);
    assert(!memcmp(exact,large,sizeof(*exact)*BPF_MAXINSNS)); free(exact);
    md_seccomp_policy_release(policy); free(large);
    puts("PASS selective policy routing, original accumulator, size bounds and unchanged failure output");
}
static void notification_routing(void) {
    struct sock_filter code[] = {BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF)};
    struct sock_fprog source = {1, code};
    struct md_seccomp_policy *older = NULL, *newer = NULL, *head = NULL;
    assert(!md_seccomp_policy_capture(NULL, &source, &older));
    assert(!md_seccomp_policy_capture(older, &source, &newer));
    code[0].k = SECCOMP_RET_ALLOW;
    assert(!md_seccomp_policy_capture(newer, &source, &head));
    struct seccomp_data call = {.nr = SYS_read}; uint32_t result;
    const struct md_seccomp_policy *winner = NULL;
    assert(!md_seccomp_policy_decide(head, &call, &result, &winner));
    assert(result == SECCOMP_RET_USER_NOTIF && winner == newer);
    const uint32_t calls[] = {SYS_seccomp};
    const struct md_seccomp_route route = {.number = SYS_getpid,
        .instruction_pointer = UINT64_C(0xabc123456004)};
    struct sock_filter out[32], before[32]; memset(out, 0x5a, sizeof(out));
    memcpy(before, out, sizeof(out));
    assert(md_seccomp_policy_route(newer, calls, 1, 51, out, 17, &route) == -ENOSPC);
    assert(!memcmp(before, out, sizeof(out)));
    int length = md_seccomp_policy_route(newer, calls, 1, 51, out, 32, &route);
    assert(length == 18);
    struct sock_fprog program = {length, out};
    call.nr = SYS_getpid; call.instruction_pointer = route.instruction_pointer;
    call.args[5] = (uintptr_t)winner;
    assert(!md_seccomp_program_eval(&program, &call, &result) && result == SECCOMP_RET_USER_NOTIF);
    call.args[5] = (uintptr_t)older;
    assert(!md_seccomp_program_eval(&program, &call, &result) && result == SECCOMP_RET_ALLOW);
    call.instruction_pointer ^= UINT64_C(1) << 32;
    assert(!md_seccomp_program_eval(&program, &call, &result) && result == SECCOMP_RET_USER_NOTIF);
    call.nr = SYS_seccomp;
    assert(!md_seccomp_program_eval(&program, &call, &result) && result == (SECCOMP_RET_TRACE | 51));
    md_seccomp_policy_release(head); md_seccomp_policy_release(newer); md_seccomp_policy_release(older);
    puts("PASS notification routing: exact 64-bit PC/token, newest winner, selective entry and atomic failure");
}
static void installation(void) {
    assert(md_seccomp_filter_call(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC));
    assert(md_seccomp_filter_call(SYS_prctl, PR_SET_SECCOMP, SECCOMP_MODE_FILTER));
    assert(!md_seccomp_filter_call(SYS_prctl, PR_SET_SECCOMP, SECCOMP_MODE_STRICT));
    assert(!md_seccomp_filter_call(SYS_seccomp, SECCOMP_GET_ACTION_AVAIL, 0));
    assert(!md_seccomp_filter_flags(SYS_prctl, SECCOMP_MODE_FILTER));
    assert(md_seccomp_filter_committed(0, 0) && !md_seccomp_filter_committed(0, -EINVAL));
    assert(!md_seccomp_filter_committed(SECCOMP_FILTER_FLAG_TSYNC, 123));
    assert(!md_seccomp_filter_committed(SECCOMP_FILTER_FLAG_TSYNC | SECCOMP_FILTER_FLAG_TSYNC_ESRCH, -ESRCH));
    assert(md_seccomp_filter_committed(SECCOMP_FILTER_FLAG_NEW_LISTENER, 0));
    assert(md_seccomp_filter_committed(SECCOMP_FILTER_FLAG_NEW_LISTENER, 5));
    assert(!md_seccomp_filter_committed(SECCOMP_FILTER_FLAG_NEW_LISTENER, -EBUSY));
    assert(!md_seccomp_listener_flags(SECCOMP_FILTER_FLAG_NEW_LISTENER));
    assert(!md_seccomp_listener_flags(SECCOMP_FILTER_FLAG_NEW_LISTENER|SECCOMP_FILTER_FLAG_TSYNC|SECCOMP_FILTER_FLAG_TSYNC_ESRCH));
    assert(md_seccomp_listener_flags(SECCOMP_FILTER_FLAG_NEW_LISTENER|SECCOMP_FILTER_FLAG_TSYNC)==-EINVAL);
    assert(md_seccomp_listener_flags(SECCOMP_FILTER_FLAG_NEW_LISTENER|SECCOMP_FILTER_FLAG_WAIT_KILLABLE_RECV)==-EOPNOTSUPP);
    assert(md_seccomp_listener_flags(SECCOMP_FILTER_FLAG_NEW_LISTENER|0x80000000U)==-EINVAL);
    puts("PASS filter installation: both APIs, listener FD zero/positive and TSYNC failure");
}
static void virtual_notification_routing(void) {
    const uint32_t actions[]={SECCOMP_RET_ALLOW,SECCOMP_RET_LOG,SECCOMP_RET_KILL_PROCESS,
        SECCOMP_RET_KILL_THREAD,SECCOMP_RET_TRAP|73,SECCOMP_RET_ERRNO|EACCES,
        SECCOMP_RET_TRACE|65535,SECCOMP_RET_USER_NOTIF,SECCOMP_RET_USER_NOTIF|65535,0x12340000};
    const struct md_seccomp_route route={.notify_cookie=219};
    struct sock_filter out[32],saved[32];
    for (unsigned dynamic=0;dynamic<=1;++dynamic) {
        for (unsigned i=0;i<sizeof(actions)/sizeof(*actions);++i) {
            struct sock_filter code[]={
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,actions[i],1,0),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EBUSY),
                BPF_STMT(BPF_RET|BPF_K,actions[i])};
            if (dynamic) code[3]=(struct sock_filter)BPF_STMT(BPF_RET|BPF_A,0);
            struct sock_fprog source={4,code};
            struct md_seccomp_policy *policy=NULL;
            assert(!md_seccomp_policy_capture(NULL,&source,&policy));
            int expected=(actions[i]&SECCOMP_RET_ACTION_FULL)==SECCOMP_RET_USER_NOTIF;
            assert(md_seccomp_policy_may_notify(policy)==(dynamic || expected));
            memset(out,0x5a,sizeof(out)); memcpy(saved,out,sizeof(out));
            assert(md_seccomp_policy_route(policy,NULL,0,0,out,3,&route)==-ENOSPC);
            assert(!memcmp(out,saved,sizeof(out)));
            int length=md_seccomp_policy_route(policy,NULL,0,0,out,32,&route);
            assert(length==(dynamic ? 10 : 4));
            struct sock_fprog rewritten={length,out};
            struct seccomp_data call={.nr=SYS_getpid,.args={actions[i]}};
            uint32_t decision;
            assert(!md_seccomp_policy_eval(policy,&call,&decision) && decision==actions[i]);
            assert(!md_seccomp_program_eval(&rewritten,&call,&decision));
            assert(decision==(expected ? SECCOMP_RET_TRACE|219 : actions[i]));
            ++call.args[0];
            assert(!md_seccomp_program_eval(&rewritten,&call,&decision)
                && decision==(SECCOMP_RET_ERRNO|EBUSY));
            md_seccomp_policy_release(policy);
        }
    }
    for (unsigned malformed=0;malformed<3;++malformed) {
        struct sock_filter code[]={BPF_STMT(BPF_JMP|BPF_JA,1),BPF_STMT(BPF_RET|BPF_A,0)};
        if (malformed==1) code[0]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,0,1,0);
        if (malformed==2) code[1]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_IMM,0);
        struct sock_fprog source={2,code}; struct md_seccomp_policy *policy=NULL;
        assert(!md_seccomp_policy_capture(NULL,&source,&policy));
        memcpy(out,saved,sizeof(out));
        assert(md_seccomp_policy_route(policy,NULL,0,0,out,32,&route)==-EINVAL);
        assert(!memcmp(out,saved,sizeof(out)));
        md_seccomp_policy_release(policy);
    }
    puts("PASS virtual notification routing: constant/dynamic results, unchanged non-notify actions and original branch bounds");
}
static void owned_admission(void) {
    const uint32_t actions[]={SECCOMP_RET_ALLOW,SECCOMP_RET_ERRNO|EACCES,SECCOMP_RET_TRAP|3,
        SECCOMP_RET_USER_NOTIF,SECCOMP_RET_TRACE|42,SECCOMP_RET_KILL_PROCESS};
    const struct md_seccomp_route route={.delegation_pc=UINT64_C(0x123456780004),
        .owned_admission=1,.notify_cookie=219,.admission_pc=UINT64_C(0x123456780104),
        .substitution_pc=UINT64_C(0x123456780204),.substitution_number=SYS_getppid};
    for (unsigned a=0;a<sizeof(actions)/sizeof(*actions);++a) for (int dynamic=0;dynamic<2;++dynamic) {
        struct sock_filter source[]={BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getppid,0,2),
            BPF_STMT(BPF_LD|BPF_W|BPF_IMM,actions[a]),BPF_STMT(BPF_RET|BPF_A,0),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        if (!dynamic) source[3]=(struct sock_filter)BPF_STMT(BPF_RET|BPF_K,actions[a]);
        struct sock_fprog original={5,source};
        struct md_seccomp_policy *policy=NULL; assert(!md_seccomp_policy_capture(NULL,&original,&policy));
        struct sock_filter out[64],saved[64]; memset(out,0x5a,sizeof(out)); memcpy(saved,out,sizeof(out));
        assert(md_seccomp_policy_route(policy,NULL,0,201,out,4,&route)==-ENOSPC);
        assert(!memcmp(out,saved,sizeof(out)));
        int size=md_seccomp_policy_route(policy,NULL,0,201,out,64,&route); assert(size>0);
        struct sock_fprog program={size,out}; uint32_t result;
        for (unsigned gate=0;gate<2;++gate) for (unsigned pc=0;pc<3;++pc) {
            struct seccomp_data call={.nr=SYS_getppid,.instruction_pointer=(gate ? route.substitution_pc : route.delegation_pc)
                +(pc==1 ? 4 : pc==2 ? UINT64_C(1)<<32 : 0)};
            uint32_t expected=actions[a];
            if (!pc && (dynamic || expected!=SECCOMP_RET_ALLOW)) expected=SECCOMP_RET_TRACE|201;
            else if (expected==SECCOMP_RET_USER_NOTIF) expected=SECCOMP_RET_TRACE|219;
            assert(!md_seccomp_program_eval(&program,&call,&result) && result==expected);
            call.nr=SYS_read;
            assert(!md_seccomp_program_eval(&program,&call,&result) && result==SECCOMP_RET_ALLOW);
        }
        /* The original constant-ALLOW exit does not acquire an IP dependency. */
        assert(out[4].code==(BPF_RET|BPF_K) && out[4].k==SECCOMP_RET_ALLOW);
        md_seccomp_policy_release(policy);
    }
    puts("PASS owned admission: exact gate, original policy, dynamic returns and unchanged native ALLOW");
    struct sock_filter deny[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES)},out[64];
    struct sock_fprog original={1,deny}; struct md_seccomp_policy *policy=NULL;
    assert(!md_seccomp_policy_capture(NULL,&original,&policy));
    int size=md_seccomp_policy_route(policy,NULL,0,201,out,64,&route); assert(size>0);
    struct sock_fprog program={size,out}; uint32_t result;
    struct seccomp_data call={.nr=SYS_gettid,.instruction_pointer=route.admission_pc};
    assert(!md_seccomp_program_eval(&program,&call,&result) && result==(SECCOMP_RET_TRACE|201));
    call.nr=SYS_openat;
    assert(!md_seccomp_program_eval(&program,&call,&result) && result==(SECCOMP_RET_ERRNO|EACCES));
    call.nr=SYS_gettid; call.instruction_pointer+=4;
    assert(!md_seccomp_program_eval(&program,&call,&result) && result==(SECCOMP_RET_ERRNO|EACCES));
    call.nr=route.substitution_number; call.instruction_pointer=route.substitution_pc;
    assert(!md_seccomp_program_eval(&program,&call,&result) && result==(SECCOMP_RET_TRACE|201));
    call.nr=SYS_accept4;
    assert(!md_seccomp_program_eval(&program,&call,&result) && result==(SECCOMP_RET_ERRNO|EACCES));
    md_seccomp_policy_release(policy);
}
int main(void) {
    owned_admission();
    actions();
    policy_users();
    replies();
    ownership(); routing(); installation(); capture(); notification_routing(); virtual_notification_routing();
    const unsigned errors[] = {0, 1, EACCES, 4095, 4096, 65535};
    for (unsigned i=0;i<sizeof(errors)/sizeof(*errors);++i)
        assert(md_seccomp_policy_error(SECCOMP_RET_ERRNO|errors[i]) == -(long)(errors[i]>4095 ? 4095 : errors[i]));
    uint32_t result = 123;
    struct sock_filter code[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_MEM, 0),
        BPF_STMT(BPF_RET | BPF_A, 0),
    };
    assert(evaluate(code, 2, &result) == -EINVAL && result == 123);
    code[0] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 1);
    assert(evaluate(code, 2, &result) == -EINVAL);
    code[0].k = sizeof(struct seccomp_data);
    assert(evaluate(code, 2, &result) == -EINVAL);
    code[0] = (struct sock_filter)BPF_STMT(BPF_ST, BPF_MEMWORDS);
    assert(evaluate(code, 2, &result) == -EINVAL);
    code[0] = (struct sock_filter)BPF_STMT(BPF_JMP | BPF_JA, UINT32_MAX);
    assert(evaluate(code, 2, &result) == -EINVAL);
    code[0].k = 1;
    assert(evaluate(code, 2, &result) == -EINVAL);
    code[0].k = 0;
    assert(!evaluate(code, 2, &result) && !result);
    code[0] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_IMM, 3);
    assert(evaluate(code, 1, &result) == -EINVAL);
    code[0] = (struct sock_filter)BPF_STMT(BPF_ALU | BPF_MOD | BPF_K, 1);
    assert(evaluate(code, 2, &result) == -EOPNOTSUPP);
    assert(evaluate(code, 0, &result) == -EINVAL);
    assert(md_seccomp_policy_merge(SECCOMP_RET_ERRNO | 5, SECCOMP_RET_ERRNO | 13) == (SECCOMP_RET_ERRNO | 13));
    assert(md_seccomp_policy_merge(SECCOMP_RET_ERRNO, SECCOMP_RET_KILL_PROCESS) == SECCOMP_RET_KILL_PROCESS);
    assert(md_seccomp_policy_merge(SECCOMP_RET_KILL_PROCESS, SECCOMP_RET_ALLOW) == SECCOMP_RET_KILL_PROCESS);
    puts("PASS policy evaluator bounds and signed action precedence");
}
