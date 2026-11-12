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
#include <linux/audit.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/wait.h>
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
    const struct md_seccomp_gate gates[]={
        {UINT64_C(0x123456780204),UINT64_C(0x123456780204),SYS_getppid,SECCOMP_RET_TRACE|201},
        {UINT64_C(0x123456780104),UINT64_C(0x123456780104),SYS_gettid,SECCOMP_RET_TRACE|201},
        {UINT64_C(0x123456780004),UINT64_C(0x123456780004),-1,SECCOMP_RET_TRACE|201}};
    const struct md_seccomp_route route={.delegation_pc=gates[2].first,
        .notify_cookie=219,.gates=gates,.gate_count=3};
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
            struct seccomp_data call={.nr=SYS_getppid,.instruction_pointer=(gate ? gates[0].first : route.delegation_pc)
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
    struct seccomp_data call={.nr=SYS_gettid,.instruction_pointer=gates[1].first};
    assert(!md_seccomp_program_eval(&program,&call,&result) && result==(SECCOMP_RET_TRACE|201));
    call.nr=SYS_openat;
    assert(!md_seccomp_program_eval(&program,&call,&result) && result==(SECCOMP_RET_ERRNO|EACCES));
    call.nr=SYS_gettid; call.instruction_pointer+=4;
    assert(!md_seccomp_program_eval(&program,&call,&result) && result==(SECCOMP_RET_ERRNO|EACCES));
    call.nr=gates[0].number; call.instruction_pointer=gates[0].first;
    assert(!md_seccomp_program_eval(&program,&call,&result) && result==(SECCOMP_RET_TRACE|201));
    call.nr=SYS_accept4;
    assert(!md_seccomp_program_eval(&program,&call,&result) && result==(SECCOMP_RET_ERRNO|EACCES));
    md_seccomp_policy_release(policy);
}
extern char policy_test_svc[];
static __attribute__((noinline)) long policy_test_call(long number) {
    register long x0 __asm__("x0")=0;
    register long x8 __asm__("x8")=number;
    __asm__ volatile(".global policy_test_svc\npolicy_test_svc:\nsvc #0"
        : "+r"(x0) : "r"(x8) : "memory","cc");
    return x0;
}
static void ip_guards(void) {
    const uint64_t original=UINT64_C(0x123400004),alias=UINT64_C(0x234500004);
    struct md_seccomp_gate gate={alias-4,alias+4,-1,SECCOMP_RET_TRACE|201};
    struct md_seccomp_route route={.gates=&gate,.gate_count=1,.entry_first=1};
    for (unsigned half=0;half<2;++half) for (unsigned invert=0;invert<2;++invert) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4*half),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(original>>(32*half)),0,1),
            BPF_STMT(BPF_RET|BPF_K,invert ? SECCOMP_RET_ALLOW : SECCOMP_RET_TRAP|7),
            BPF_STMT(BPF_RET|BPF_K,invert ? SECCOMP_RET_TRAP|7 : SECCOMP_RET_ALLOW)};
        struct sock_fprog source={4,code};
        struct md_seccomp_policy *policy=NULL;
        assert(!md_seccomp_policy_capture(NULL,&source,&policy));
        struct sock_filter out[128],saved[128]; memset(out,0x5a,sizeof(out)); memcpy(saved,out,sizeof(out));
        assert(md_seccomp_policy_route(policy,NULL,0,201,out,4,&route)==-ENOSPC);
        assert(!memcmp(out,saved,sizeof(out)));
        int count=md_seccomp_policy_route(policy,NULL,0,201,out,128,&route);
        assert(count>4);
        struct sock_fprog routed={count,out};
        for (unsigned i=0;i<3;++i) {
            struct seccomp_data call={.nr=SYS_read,.instruction_pointer=i==0 ? original : i==1 ? alias : alias+16};
            uint32_t expected,value;
            assert(!md_seccomp_policy_eval(policy,&call,&expected));
            if (i==1) expected=gate.decision;
            assert(!md_seccomp_program_eval(&routed,&call,&value) && value==expected);
        }
        assert(!md_seccomp_policy_bind_entry(policy,17,&gate,1));
        assert(md_seccomp_policy_entry_image(policy)==17);
        assert(md_seccomp_policy_internal_gates(policy,&gate,1));
        struct md_seccomp_policy *child=NULL;
        assert(!md_seccomp_policy_capture(policy,&source,&child));
        assert(!md_seccomp_policy_bind_entry(child,invert ? 18 : 17,&gate,1));
        assert(md_seccomp_policy_entry_image(child)==(invert ? 0 : 17));
        assert(md_seccomp_policy_internal_gates(child,&gate,1));
        md_seccomp_policy_release(child); md_seccomp_policy_release(policy);
    }
    puts("PASS IP-sensitive gates: both PC halves, ALLOW alias interception, original actions and image ancestry");
}
static void kernel_gates(void) {
    uid_t uid=getuid();
    for (int range=0;range<2;++range) for (int first=0;first<2;++first) {
        uintptr_t pc=(uintptr_t)policy_test_svc+4;
        struct md_seccomp_gate gate={pc-(range ? 4 : 0),pc+(range ? 4 : 0),SYS_getuid,SECCOMP_RET_ALLOW};
        struct md_seccomp_route route={.gates=&gate,.gate_count=1,.entry_first=first};
        struct sock_filter original[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getuid,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog source={4,original}; struct md_seccomp_policy *policy=NULL;
        assert(!md_seccomp_policy_capture(NULL,&source,&policy));
        struct sock_filter code[64];
        int count=md_seccomp_policy_route(policy,NULL,0,201,code,64,&route); assert(count>0);
        struct sock_fprog program={count,code};
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
            assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
            assert(policy_test_call(SYS_getuid)==(long)uid);
            assert(policy_test_call(SYS_getpid)==getpid());
            errno=0;
            assert(syscall(SYS_getuid)==-1 && errno==EACCES);
            _exit(0);
        }
        int status;
        assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
        md_seccomp_policy_release(policy);
    }
    puts("PASS kernel gate admission: exact/range helper allowed, original caller denied, native calls unchanged");
}
static void admitted_gates(void) {
    struct md_seccomp_gate gate={UINT64_C(0x123456780000),UINT64_C(0x123456781000),-1,SECCOMP_RET_ALLOW};
    struct md_seccomp_route route={.gates=&gate,.gate_count=1,.entry_first=1};
    struct sock_filter source[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getuid,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args)),
        BPF_STMT(BPF_MISC|BPF_TAX,0), BPF_STMT(BPF_LD|BPF_W|BPF_IMM,1),
        BPF_STMT(BPF_ALU|BPF_DIV|BPF_X,0), BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES)};
    struct sock_fprog original={sizeof(source)/sizeof(*source),source};
    struct md_seccomp_policy *policy=NULL;
    assert(!md_seccomp_policy_capture(NULL,&original,&policy));
    struct sock_filter output[128];
    int n=md_seccomp_policy_route(policy,NULL,0,201,output,128,&route); assert(n>0);
    struct sock_fprog routed={n,output};
    for (unsigned i=0;i<64*MD_SECCOMP_CACHE_WORDS;++i) {
        uint32_t result;
        int error=md_seccomp_program_constant(&routed,i,AUDIT_ARCH_AARCH64,&result);
        assert(i==SYS_getuid ? error==-EAGAIN : !error && result==SECCOMP_RET_ALLOW);
    }
    for (unsigned arch=0;arch<2;++arch) for (unsigned arg=0;arg<2;++arg)
    for (unsigned owned=0;owned<2;++owned) {
        struct seccomp_data call={.nr=SYS_getuid,.arch=arch ? AUDIT_ARCH_ARM : AUDIT_ARCH_AARCH64,
            .instruction_pointer=owned ? gate.first+4 : gate.end+4,.args={arg}};
        uint32_t result,expected=owned ? SECCOMP_RET_ALLOW : arg ? SECCOMP_RET_ERRNO|EACCES : 0;
        assert(!md_seccomp_program_eval(&routed,&call,&result) && result==expected);
    }
    md_seccomp_policy_release(policy);
    puts("PASS admitted entry: guest body and implicit exits skipped, native ALLOW remains cacheable");
}
static void gate_ranges(void) {
    const uint64_t bounds[][2]={
        {0x1000,0x2000},{UINT64_C(0xfffffffc),UINT64_C(0x100000004)},
        {UINT64_C(0x123400000000),UINT64_C(0x123600000000)},
        {UINT64_MAX-0x1000,UINT64_MAX}};
    const uint32_t actions[]={SECCOMP_RET_ERRNO|EACCES,SECCOMP_RET_TRAP|3,
        SECCOMP_RET_TRACE|42,SECCOMP_RET_KILL_PROCESS,SECCOMP_RET_ALLOW};
    for (unsigned b=0;b<sizeof(bounds)/sizeof(*bounds);++b)
    for (unsigned a=0;a<sizeof(actions)/sizeof(*actions);++a)
    for (int dynamic=0;dynamic<2;++dynamic) for (int numbered=0;numbered<2;++numbered) {
        struct sock_filter source[]={BPF_STMT(BPF_LD|BPF_W|BPF_IMM,actions[a]),
            BPF_STMT(BPF_RET|BPF_A,0)};
        if (!dynamic) source[1]=(struct sock_filter)BPF_STMT(BPF_RET|BPF_K,actions[a]);
        struct sock_fprog original={2,source}; struct md_seccomp_policy *policy=NULL;
        assert(!md_seccomp_policy_capture(NULL,&original,&policy));
        struct md_seccomp_gate gate={bounds[b][0],bounds[b][1],numbered ? SYS_getpid : -1,
            numbered ? SECCOMP_RET_ALLOW : SECCOMP_RET_TRACE|201};
        struct md_seccomp_route route={.gates=&gate,.gate_count=1};
        struct sock_filter output[64];
        int n=md_seccomp_policy_route(policy,NULL,0,201,output,64,&route); assert(n>0);
        struct sock_fprog program={n,output};
        const uint64_t pcs[]={0,gate.first-1,gate.first,gate.first+1,gate.end-1,gate.end,UINT64_MAX};
        for (unsigned i=0;i<sizeof(pcs)/sizeof(*pcs);++i) for (int nr=0;nr<2;++nr) {
            struct seccomp_data call={.nr=nr ? SYS_getpid : SYS_getppid,.instruction_pointer=pcs[i]};
            uint32_t result,expected=actions[a];
            if ((dynamic || actions[a]!=SECCOMP_RET_ALLOW) && (!numbered || nr)
                    && pcs[i]>=gate.first && pcs[i]<gate.end) expected=gate.decision;
            assert(!md_seccomp_program_eval(&program,&call,&result) && result==expected);
        }
        if (!dynamic && actions[a]==SECCOMP_RET_ALLOW) assert(n==2);
        memset(output,0xa5,sizeof(output));
        gate.end=gate.first-1;
        assert(md_seccomp_policy_route(policy,NULL,0,201,output,64,&route)==-EINVAL);
        for (size_t i=0;i<sizeof(output);++i) assert(((unsigned char *)output)[i]==0xa5);
        md_seccomp_policy_release(policy);
    }
    puts("PASS owned gate ranges: 64-bit boundaries, syscall scope, dynamic results and native ALLOW cache");
}
static void views(void) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_openat,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={4,code};
    struct md_seccomp_policy *root=NULL,*head=NULL;
    assert(!md_seccomp_policy_capture(NULL,&program,&root));
    size_t bytes;
    assert(!md_seccomp_policy_view_size(root,0,&bytes));
    struct md_seccomp_view *parent=malloc(bytes); assert(parent);
    assert(!md_seccomp_policy_view_write(root,0,parent,bytes,(uintptr_t)parent,0));
    uint64_t id=md_seccomp_policy_identity(root);
    assert(id && !md_seccomp_policy_identity(NULL) && !md_seccomp_policy_parent(root));
    const uint32_t actions[]={SECCOMP_RET_KILL_THREAD,SECCOMP_RET_KILL_PROCESS,
        SECCOMP_RET_ERRNO|EPERM,SECCOMP_RET_ERRNO,SECCOMP_RET_TRAP|7,
        SECCOMP_RET_TRACE|9,SECCOMP_RET_USER_NOTIF,SECCOMP_RET_LOG,SECCOMP_RET_ALLOW};
    for (unsigned field=0;field<sizeof(struct seccomp_data);field+=4) {
        for (unsigned a=0;a<sizeof(actions)/sizeof(*actions);++a) {
            code[0].k=field; code[1].k=SYS_openat; code[2].k=actions[a];
            assert(!md_seccomp_policy_capture(root,&program,&head));
            assert(md_seccomp_policy_parent(head)==root && md_seccomp_policy_identity(head)>id);
            size_t part,whole;
            assert(!md_seccomp_policy_view_size(head,id,&part));
            assert(!md_seccomp_policy_view_size(head,0,&whole) && whole==part+bytes);
            struct md_seccomp_view *view=malloc(whole),*flat=malloc(whole);
            assert(view && flat); memset(view,0xa5,whole);
            assert(md_seccomp_policy_view_write(head,id,view,part-1,(uintptr_t)view,(uintptr_t)parent)==-ENOSPC);
            for (size_t i=0;i<whole;++i) assert(((unsigned char *)view)[i]==0xa5);
            assert(md_seccomp_policy_view_size(head,UINT64_MAX,&part)==-ENOENT);
            assert(md_seccomp_policy_view_write(head,id,view,whole,(uintptr_t)view,0)==-EINVAL);
            assert(!md_seccomp_policy_view_write(head,id,view,whole,(uintptr_t)view,(uintptr_t)parent));
            assert(view->parent==parent);
            assert(!md_seccomp_policy_view_write(head,0,flat,whole,(uintptr_t)flat,0));
            for (unsigned match=0;match<2;++match) {
                struct seccomp_data call={0};
                uint32_t input=match ? SYS_openat : SYS_read,result,linked,linear;
                memcpy((char *)&call+field,&input,sizeof(input));
                assert(!md_seccomp_policy_eval(head,&call,&result));
                assert(!md_seccomp_view_eval(view,&call,&linked) && linked==result);
                assert(!md_seccomp_view_eval(flat,&call,&linear) && linear==result);
            }
            free(view); free(flat); md_seccomp_policy_release(head);
        }
    }
    /* The exported view owns bytes, not a borrowed coordinator allocation. */
    md_seccomp_policy_release(root);
    struct seccomp_data call={.nr=SYS_openat}; uint32_t result;
    assert(!md_seccomp_view_eval(parent,&call,&result) && result==(SECCOMP_RET_ERRNO|EACCES));
    assert(!md_seccomp_view_eval(NULL,&call,&result) && result==SECCOMP_RET_ALLOW);
    free(parent);
    puts("PASS immutable policy views: shared ancestry, all call fields, actions and independent lifetime");
}
static void constant_admission(void) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getuid,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,0,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES)};
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    struct md_seccomp_cache cache,child;
    md_seccomp_cache_build(&cache,&program,NULL);
    for (unsigned number=0;number<1024;++number) {
        struct seccomp_data call={.arch=AUDIT_ARCH_AARCH64,.nr=(int)number};
        assert(md_seccomp_cache_allows(&cache,&call)==(number==SYS_getuid));
        for (unsigned value=0;value<4;++value) {
            call.args[0]=value; call.instruction_pointer=UINT64_MAX-value;
            uint32_t decision;
            assert(!md_seccomp_program_eval(&program,&call,&decision));
            if (md_seccomp_cache_allows(&cache,&call)) assert(decision==SECCOMP_RET_ALLOW);
        }
        call.arch=AUDIT_ARCH_ARM;
        assert(!md_seccomp_cache_allows(&cache,&call));
    }
    code[1].k=SYS_getgid;
    md_seccomp_cache_build(&child,&program,&cache);
    for (unsigned i=0;i<MD_SECCOMP_CACHE_WORDS;++i) assert(!child.allow[i]);
    code[1].k=SYS_getuid;
    code[2]=(struct sock_filter)BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES);
    md_seccomp_cache_build(&child,&program,&cache);
    for (unsigned i=0;i<MD_SECCOMP_CACHE_WORDS;++i) assert(!child.allow[i]);
    for (unsigned offset=offsetof(struct seccomp_data,instruction_pointer);
            offset<sizeof(struct seccomp_data);offset+=4) {
        code[0]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offset);
        uint32_t result=123;
        assert(md_seccomp_program_constant(&program,SYS_getuid,AUDIT_ARCH_AARCH64,&result)==-EAGAIN);
        assert(result==123);
    }
    puts("PASS constant admission: nr/arch proof, unknown fields, bounds and ancestor intersection");
}
static void inherited_entry(void) {
    struct sock_filter allow[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={1,allow};
    struct md_seccomp_policy *parent=NULL,*child=NULL;
    struct md_seccomp_gate gates[]={
        {0x1204,0x1204,SYS_gettid,SECCOMP_RET_TRACE|201},
        {0x1000,0x2000,-1,SECCOMP_RET_ALLOW}};
    assert(!md_seccomp_policy_capture(NULL,&program,&parent));
    assert(!md_seccomp_policy_entry_compatible(parent,17));
    assert(!md_seccomp_policy_internal_gates(parent,gates,2));
    assert(!md_seccomp_policy_internal_gates(NULL,gates,2));
    assert(!md_seccomp_policy_bind_entry(parent,17,gates,2));
    assert(md_seccomp_policy_internal_gates(parent,gates,2));
    assert(!md_seccomp_policy_internal_gates(parent,NULL,0));
    assert(md_seccomp_policy_entry_compatible(parent,17) && md_seccomp_policy_entry_compatible(parent,18));
    assert(md_seccomp_policy_entry_checkpoint(parent)==SYS_gettid);
    assert(!md_seccomp_policy_capture(parent,&program,&child));
    assert(!md_seccomp_policy_bind_entry(child,18,gates,2));
    assert(md_seccomp_policy_entry_image(child)==18 && md_seccomp_policy_entry_compatible(child,19));
    assert(md_seccomp_policy_internal_gates(child,gates,2));
    struct md_seccomp_policy *grandchild=NULL;
    assert(!md_seccomp_policy_capture(child,&program,&grandchild));
    assert(!md_seccomp_policy_bind_entry(grandchild,18,gates,2));
    assert(md_seccomp_policy_entry_compatible(grandchild,18));
    assert(md_seccomp_policy_internal_gates(grandchild,gates,2));
    md_seccomp_policy_release(grandchild);
    md_seccomp_policy_release(child); child=NULL;
    /* Changing any part of the contract, including precedence, cannot restore
     * a missing ancestor allowance merely by installing another filter. */
    for (unsigned variant=0;variant<7;++variant) {
        struct md_seccomp_gate changed[2]; memcpy(changed,gates,sizeof(changed));
        size_t count=2;
        if (variant==0) changed[1].first+=4;
        if (variant==1) changed[1].end+=4;
        if (variant==2) changed[0].number=SYS_getpid;
        if (variant==3) ++changed[0].decision;
        if (variant==4) { changed[0]=gates[1]; changed[1]=gates[0]; }
        if (variant==5) count=1;
        if (variant==6) count=0;
        assert(!md_seccomp_policy_internal_gates(parent,changed,count));
        assert(md_seccomp_policy_internal_gates(parent,gates,2));
        assert(!md_seccomp_policy_capture(parent,&program,&child));
        assert(!md_seccomp_policy_bind_entry(child,18,changed,count));
        assert(!md_seccomp_policy_internal_gates(child,changed,count));
        assert(!md_seccomp_policy_internal_gates(child,gates,2));
        assert(!md_seccomp_policy_capture(child,&program,&grandchild));
        assert(!md_seccomp_policy_bind_entry(grandchild,19,gates,2));
        assert(!md_seccomp_policy_internal_gates(grandchild,gates,2));
        md_seccomp_policy_release(grandchild); grandchild=NULL;
        md_seccomp_policy_release(child); child=NULL;
    }
    struct sock_filter denied[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_gettid,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    program=(struct sock_fprog){4,denied};
    assert(!md_seccomp_policy_capture(parent,&program,&child));
    assert(!md_seccomp_policy_bind_entry(child,18,gates,2));
    assert(md_seccomp_policy_entry_checkpoint(child)==SYS_getpid);
    md_seccomp_policy_release(child); child=NULL;
    allow[0].k=SECCOMP_RET_ERRNO|EPERM;
    program=(struct sock_fprog){1,allow};
    assert(!md_seccomp_policy_capture(parent,&program,&child));
    assert(!md_seccomp_policy_bind_entry(child,18,gates,2));
    assert(md_seccomp_policy_entry_compatible(child,18) && !md_seccomp_policy_entry_compatible(child,19));
    assert(md_seccomp_policy_entry_checkpoint(child)<0);
    md_seccomp_policy_release(child); md_seccomp_policy_release(parent);
    puts("PASS inherited entry: stable gates, policy ancestry and admitted checkpoint selection");
}
int main(void) {
    constant_admission();
    inherited_entry();
    ip_guards();
    kernel_gates();
    admitted_gates();
    gate_ranges();
    views();
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
