#ifndef MD_SIGNAL_OPERATION_H
#define MD_SIGNAL_OPERATION_H
#include "signal_actions.h"
#include "signal_pending.h"
#include "signal_view.h"
#include "signal_fd.h"
#include "task_call_arguments.h"
#include <linux/seccomp.h>
#include <sys/uio.h>
#include <time.h>

struct md_signal_wait_exchange { uint64_t set; struct timespec timeout; siginfo_t info; };
struct md_signal_transfer { uintptr_t address; void *data; size_t size; int writing; };
/* Logical signal operation. Transports service copy/native/pending requests;
 * the model owns consumption, ordering and deadlines, never machine registers. */
struct md_signal_operation {
    struct seccomp_data request;
    uintptr_t signal_frame;
    uintptr_t peer;
    void *scratch;
    size_t capacity;
    struct md_signal_actions *actions;
    int *blocked;
    struct md_kernel_action requested, previous;
    uint64_t previous_mask, next_mask;
    unsigned stage;
    int entering, signal, force_default;
    int copying, invoking;
    long result,effect_result;
    int waiting, passthrough;
    int wait_native, wait_woken, wait_timed, wait_interrupt;
    int64_t wait_deadline;
    uintptr_t wait_peer;
    struct md_signal_wait_exchange wait_exchange;
    uint64_t wait_set;
    struct timespec wait_timeout;
    siginfo_t wait_info;
    struct md_signal_pending *pending_owner;
    struct md_signal_pending_value selected;
    struct md_signal_wait_view wait_view;
    long wait_result;
    int descriptor, descriptor_stage, descriptor_nonblock, descriptor_flags;
    long descriptor_result;
    struct md_signal_fd *signal_file;
    struct iovec *vectors;
    struct iovec scalar;
    unsigned vector_count, vector_index;
    size_t vector_offset, descriptor_copied;
    size_t descriptor_available, record_copied, copy_length;
    struct signalfd_siginfo record;
    void (*translate)(void *,siginfo_t *);
    void *context;
    struct md_signal_transfer transfer;
    struct md_task_call_arguments effect;
    uint64_t pending_wanted,pending_probe,pending_result;
};
enum { MD_SIGNAL_WAIT_RELEASE=2, MD_SIGNAL_WAIT_ACQUIRE=3,
    MD_SIGNAL_FD_ACQUIRE=4, MD_SIGNAL_FD_RELEASE=5,
    MD_SIGNAL_WAIT_STORAGE=6, MD_SIGNAL_WAIT_PARKED=7, MD_SIGNAL_PENDING=8 };
int md_signal_operation_begin(struct md_signal_operation *,const struct seccomp_data *,
    struct md_signal_actions *,struct md_signal_pending *,int *,
    void (*)(void *,siginfo_t *),void *,void *,uintptr_t,size_t);
int md_signal_operation_default_begin(struct md_signal_operation *,struct md_signal_actions *,int *,
    const struct md_kernel_action *,void *,uintptr_t,size_t);
int md_signal_operation_entry_begin(struct md_signal_operation *,uintptr_t,int,
    struct md_signal_actions *,int *,const struct md_kernel_action *,const struct md_signal_wait_view *,
    void *,uintptr_t,size_t);
int md_signal_operation_copy_done(struct md_signal_operation *,long);
int md_signal_operation_effect_done(struct md_signal_operation *,long);
int md_signal_operation_pending_done(struct md_signal_operation *,int,uint64_t);
int md_signal_operation_continue(struct md_signal_operation *);
int md_signal_operation_park(struct md_signal_operation *,uintptr_t);
int md_signal_operation_wake(struct md_signal_operation *);
int md_signal_operation_parked(const struct md_signal_operation *);
void md_signal_operation_quiesced(struct md_signal_operation *);
int md_signal_operation_descriptor_admit(struct md_signal_operation *,struct md_signal_fd *);
int md_signal_operation_descriptor_changed(struct md_signal_operation *,const struct md_signal_fd *);
void md_signal_operation_dispose(struct md_signal_operation *);
#endif
