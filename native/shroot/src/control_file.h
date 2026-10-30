#ifndef MD_CONTROL_FILE_H
#define MD_CONTROL_FILE_H
#include <stddef.h>
#include <stdint.h>
#include <sys/stat.h>
#include "resource_order.h"
struct md_identity;
struct md_task_resources;
struct md_namespace_reference;
struct md_fs_anchor;
struct md_resource_wakeup;
struct md_control_pending;
struct md_fs_context;
struct md_fs_continuation;
struct md_network_namespace;
/* Borrowed admission context. Per-open providers retain only the resources
 * they need; dup/fork keep the resulting OFD, not the current caller's state. */
struct md_control_opener {
    const struct md_identity *identity;
    struct md_network_namespace *network;
};
struct md_control_fs_operation {
    long (*run)(void *,struct md_fs_context *,struct md_fs_continuation **);
    void (*destroy)(void *);
    void *context;
};
struct md_control_actor {
    int pid;
    const struct md_identity *identity;
    const struct md_task_resources *resources;
    /* Borrowed event-loop owner; providers retain it only for future wakeups. */
    struct md_resource_wakeup *wakeup;
    /* Current OFD status flags, captured when its ordered operation starts. */
    unsigned status_flags;
    /* Optional filesystem admission. Submission captures the actor once and
     * transfers operation ownership only on EINPROGRESS. */
    void *filesystem;
    long (*submit)(void *,const struct md_control_actor *,struct md_control_fs_operation,
        struct md_control_pending **);
};
struct md_control_file {
    const struct md_control_file_ops *ops;
    void *context;
};
/* Typed ioctl marshalling. Providers never dereference guest addresses. Some
 * Linux requests (notably EVIOCSFF) return data despite an _IOW encoding. */
enum { MD_CONTROL_IN=1, MD_CONTROL_OUT=2, MD_CONTROL_STRING=4, MD_CONTROL_INDIRECT=8, MD_CONTROL_IOCTL_MAX=16384 };
enum { MD_CONTROL_IO_MAX=65536 };
/* Bounded pointed-to byte arrays, following the fixed argument in transport.
 * Offsets address native-width pointer/size_t fields in that argument. The
 * adapter captures addresses before dispatch; providers only see byte arrays. */
enum { MD_CONTROL_IOCTL_FIELDS=4 };
struct md_control_ioctl_field { unsigned pointer, length, capacity, direction; };
struct md_control_ioctl {
    unsigned size, direction;
    unsigned fields;
    struct md_control_ioctl_field field[MD_CONTROL_IOCTL_FIELDS];
};
/* Provider-owned asynchronous request. resume never blocks: EINPROGRESS means
 * an external completion will signal the descriptor wakeup. dispose cancels
 * an unfinished request and releases it exactly once. deadline is monotonic. */
struct md_control_pending {
    long (*resume)(struct md_control_pending *,void *data,size_t);
    void (*dispose)(struct md_control_pending *);
    int64_t deadline;
    /* Directory cookie, or an explicit cursor after an append write. */
    int64_t position;
    int position_valid;
    /* Internal preparation has no application-visible mutation and must not
     * expose its wait as an interruptible application operation. Signals run
     * normally; preparation is retried even without SA_RESTART. */
    int retry_preparation;
    /* Optional terminal ownership for effects already admitted by a provider.
     * abandon consumes this request, calling retired exactly once with the
     * final result before disposal. No caller buffer or task is retained. */
    void (*abandon)(struct md_control_pending *);
    void (*retired)(struct md_control_pending *,long);
    struct md_resource_order retirement;
    void *retirement_context;
    int64_t retirement_offset;
    int retirement_advance;
};
enum { MD_CONTROL_READ=1, MD_CONTROL_WRITE, MD_CONTROL_GETDENTS };
enum { MD_CONTROL_SYNC=1, MD_CONTROL_DATASYNC, MD_CONTROL_TRUNCATE };
/* A virtual inode supplies typed semantics, not a second IO/cursor manager.
 * The descriptor host serializes each open description and owns copy/commit. */
struct md_control_file_ops {
    void *(*retain)(void *);
    void (*release)(void *);
    int (*stat)(void *,struct stat *);
    long (*read)(void *,const struct md_control_actor *,void *,size_t,int64_t);
    long (*write)(void *,const struct md_control_actor *,const void *,size_t,int64_t);
    /* Packet-independent IO. Inputs are captured before returning EINPROGRESS;
     * only the exact OFD turn spans the wait, never a descriptor-table turn. */
    long (*io)(void *,const struct md_control_actor *,unsigned operation,void *,size_t,int64_t,
        struct md_control_pending **);
    /* Regular byte files may bound transport pages without exposing that
     * bound as EOF. Zero retains single-operation/packet semantics. The host
     * retains one OFD turn until all pages are copied, EOF or an error. */
    size_t (*read_size)(void *);
    /* Optional seek semantics; the descriptor host owns the cursor and OFD
     * turn. ENOSYS delegates to its ordinary byte-offset handling. */
    long (*seek)(void *,const struct md_control_actor *,int64_t,int,struct md_control_pending **);
    /* Scalar descriptor commands share asynchronous lifetime and OFD ordering
     * with IO, but never consume or advance the file cursor. */
    long (*command)(void *,const struct md_control_actor *,unsigned,int64_t,struct md_control_pending **);
    /* One confirmed descriptor close, distinct from final OFD release. The
     * caller no longer owns its FD-table turn; pending work owns its lifetime. */
    long (*flush)(void *,const struct md_control_actor *,struct md_control_pending **);
    unsigned long filesystem;
    int (*access)(void *,const struct md_identity *,unsigned);
    int nonseekable;
    /* Optional real backing for a controlled view. IO/cursors remain owned by
     * the descriptor host; the returned CLOEXEC descriptor is transferred. */
    int (*descriptor)(void *,int flags);
    int (*seals)(void *);
    int mappable;
    /* Shared mappings retain VM_MAYWRITE restrictions even though preparation
     * may borrow a writable backing descriptor. Private COW is unaffected. */
    int mapping_readonly;
    /* Optional private mapping backing, distinct from the inert carrier.
     * Returns an owned FD, or EINPROGRESS with an owned request whose resume
     * returns that FD. Neither the file table nor address space is locked
     * while a userspace provider prepares it. */
    long (*mapping)(void *,const struct md_control_actor *,unsigned protection,unsigned flags,
        struct md_control_pending **);
    /* Typed single-file mounts retain their authority, not the carrier inode.
     * at() returns an owned context at the supplied placement; NULL detaches
     * any previous placement before the mount itself retains the provider. */
    const struct md_namespace_reference *(*namespace_reference)(void *);
    void *(*at)(void *,struct md_fs_anchor *);
    /* Borrowed placement, distinct from the native descriptor carrier. */
    struct md_fs_anchor *(*origin)(void *);
    /* IO is controlled, but metadata, reopen and bind retain filesystem
     * authority through origin. Never substitute the descriptor carrier. */
    int filesystem_paths;
    /* Kernel-pipe transfers may use the same IO/copy acknowledgement owner. */
    int splice;
    /* Optional independent per-open state; dup/fork retain the same context. */
    void *(*open)(void *,const struct md_control_opener *,int flags);
    int (*ioctl_spec)(void *,unsigned command,struct md_control_ioctl *);
    long (*ioctl)(void *,const struct md_control_actor *,unsigned command,
        unsigned long value,void *data,size_t size);
    long (*ioctl_async)(void *,const struct md_control_actor *,unsigned command,
        unsigned long value,void *data,size_t size,struct md_control_pending **);
    /* Event sources signal the descriptor owner's wakeup after publication.
     * readable also returns true for terminal errors (disconnect/revoke). */
    int (*readable)(void *);
    void (*read_commit)(void *,size_t copied);
    /* Optional virtual readiness. The wake FD is only a transport hint; it is
     * not the file's application-visible event mask. poll consumes provider
     * state, independently of reads. poll_descriptor returns zero and a
     * borrowed FD, or -1 in the output for a source without notifications.
     * Negative return values are errors, never application event masks. */
    int (*poll_descriptor)(void *,int *descriptor);
    long (*poll)(void *);
    /* True when the original application descriptor now supplies equivalent
     * native readiness. Existing virtual epoll subscriptions remain valid. */
    int (*poll_native)(void *);
    /* Optional per-wait gate while native readiness is suppressed. Unlike an
     * epoll subscription, poll/select can refresh this source after a wake. */
    int (*poll_wait_descriptor)(void *,int *descriptor);
};
static inline struct md_control_file md_control_file_retain(struct md_control_file f) {
    if (f.ops) f.context=f.ops->retain(f.context);
    return f;
}
static inline void md_control_file_release(struct md_control_file f) {
    if (f.ops) f.ops->release(f.context);
}
#endif
