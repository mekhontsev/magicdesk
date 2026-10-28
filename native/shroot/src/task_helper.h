#ifndef MD_TASK_HELPER_H
#define MD_TASK_HELPER_H
#include "interception.h"
#include "interception_memory.h"
#include "task_send.h"
#include "task_receive.h"
#include <sys/user.h>
#include <sys/types.h>
#include <asm/ptrace.h>
#include <linux/seccomp.h>
#include <signal.h>

struct md_task_helpers;
struct md_task_wait;
struct md_task_mapping_request {
    int source, protection, flags;
    uintptr_t address;
    size_t size;
    uint64_t offset;
};
enum md_task_helper_kind { MD_TASK_HELPER_MAP, MD_TASK_HELPER_CAPTURE,
    MD_TASK_HELPER_RECEIVE, MD_TASK_HELPER_FD_TABLE, MD_TASK_HELPER_EXPORT, MD_TASK_HELPER_SEND_CAPTURED };
struct md_task_helper_request {
    enum md_task_helper_kind kind;
    struct md_task_mapping_request mapping;
    /* CAPTURE only: copied before acceptance, resolved in captured FS context.
     * O_PATH captures objects; an exact own-process fdinfo path may instead be
     * opened read-only and exported intact, preserving its live table lookup.
     * FD_TABLE takes no path/flags: it opens /proc/<exact owner tid>/fd read-only
     * in the private table and exports only that directory, never a caller FD. */
    const char *path;
    int flags;
    struct md_task_send send;
    /* SEND_CAPTURED only: immutable owned [socket, 0..253 rights] retained on
     * admission. send supplies message, contiguous rights token_slot and flags;
     * no guest FD is captured. All installs belong to the private helper table.
     * send_import occupies the retained mapping. The contiguous SCM_RIGHTS
     * block may be in caller-owned control memory; recvmsg writes it directly.
     * Scratch must be disjoint from headers, vectors, control and payload. */
    struct md_received_fds *send_inputs;
    uintptr_t send_import;
    /* Optional worker-owned pathname anchor. Imported separately from rights;
     * send_address and send.message must then live in the retained mapping. */
    uintptr_t send_address;
    int send_anchor;
    struct md_task_receive receive;
    /* EXPORT only: copies 1..254 ordered numbers before acceptance. The private
     * table captures them together at clone; repeated entries retain their OFD.
     * Result.received owns that exact vector after helper reaping. No origin
     * is inferred: caller snapshots metadata under the same source-table turn,
     * ending it at captured(), never before clone or after blocking IO. */
    const int *descriptors;
    unsigned descriptor_count;
    /* Exact CLONE_FILES separation, before any potentially blocking IO. */
    void (*captured)(void *);
};
struct md_received_fds;
struct md_task_helper_result { long value; int descriptor; siginfo_t signal; struct md_received_fds *received; };
/* Completion transfers descriptor ownership, including cancellation. */
typedef void (*md_task_helper_complete)(void *, struct md_task_helper_result);
struct md_task_helper_callbacks {
    long (*trace)(int, pid_t, void *, void *);
    /* Claim an early newborn stop, if already recorded by the outer loop. */
    int (*newborn)(pid_t);
    /* Shared fair collection includes private tasks until their terminal wait. */
    struct md_task_wait *wait;
};
struct md_task_helpers *md_task_helpers_new(const struct md_task_helper_callbacks *);
/* Every helper must be reaped before destruction. */
void md_task_helpers_destroy(struct md_task_helpers *);
unsigned md_task_helpers_count(const struct md_task_helpers *);
void md_task_helpers_describe(struct md_task_helpers *,pid_t owner,int output);
int md_task_helpers_group_busy(const struct md_task_helpers *,pid_t group);
/* Snapshot for a supervisor-owned writer barrier: task, stopped, resume request.
 * A zero request waits for an externally owned stop, including initial birth;
 * the barrier must preserve it for this helper's event handler. */
int md_task_helpers_visit(struct md_task_helpers *, struct md_interception_memory *, int (*)(void *,pid_t,int,int), void *);
int md_task_helper_owns(struct md_task_helpers *,pid_t owner,pid_t task);
/* True only while this group's exact private helper is terminating, before
 * its terminal wait event. It cannot execute another application operation. */
int md_task_helper_retiring(struct md_task_helpers *,pid_t group,pid_t task);
/* Borrows source on entry; accepted operations retain their own description,
 * memory owner, scratch stack and buffer lease. No caller FD is installed.
 * Completion occurs once after helper reaping or a confirmed pre-birth error.
 * The caller orders any FS/table inputs through context capture and retains
 * its continuation until completion; detach cannot resume a dead task. */
int md_task_helper_begin(struct md_task_helpers *, pid_t owner, pid_t group,
        const struct user_pt_regs *, const struct md_interception_abi *,
        struct md_interception_memory *, const struct md_interception_buffer *,
        const struct md_task_helper_request *, md_task_helper_complete, void *completion);
/* Owner terminal/exec notification must precede record reuse. */
int md_task_helper_detach(struct md_task_helpers *, pid_t owner);
/* 1 consumed, 0 belongs to the ordinary guest path, negative errno fatal.
 * Late interrupt acknowledgements continue the lent execution in place;
 * caller exit/exec, signals and application group-stops retain outer ownership. */
int md_task_helper_event(struct md_task_helpers *, pid_t, int status);
struct md_interception_memory *md_task_helper_memory(struct md_task_helpers *, pid_t);
int md_task_helper_notification(struct md_task_helpers *, int listener, const struct seccomp_notif *);
#endif
