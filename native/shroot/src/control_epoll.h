#ifndef MD_CONTROL_EPOLL_H
#define MD_CONTROL_EPOLL_H
#include "control_file.h"
#include "fd_object.h"
#include <sys/epoll.h>

/* Typed epoll OFD authority. Native registrations remain in their
 * original kernel epoll; neither their data nor their identity is rewritten.
 * This object owns no duplicate of that kernel epoll. Tables and queued
 * transfers retain the same authority across dup/fork/receive. The supervisor
 * serializes subscription operations; refcounts alone are thread-safe.
 * File-table capture/admission and guest result copying belong to that caller. */
struct md_control_epoll;
/* Metadata only. Wake descriptors are allocated on the first virtual ADD. */
struct md_control_epoll *md_control_epoll_new(void);
void md_control_epoll_release(struct md_control_epoll *);
struct md_fd_object *md_control_epoll_object(struct md_control_epoll *);
struct md_control_epoll *md_control_epoll_from_object(struct md_fd_object *);
/* Under the caller's file-table turn: no admitted or preparing virtual wait.
 * Unknown/non-epoll descriptions retain native kernel validation. */
int md_control_epoll_native_wait(struct md_fd_object *);
/* Readiness hint, not an application epoll: collect revalidates providers.
 * -1 before successful virtual admission. Once published, this borrowed FD
 * stays valid until owner release, including after the last interest's DEL.
 * A wake can be stale, and need not yield an application event. */
int md_control_epoll_descriptor(const struct md_control_epoll *);
/* Alternate bounded native/virtual collection for small result buffers. */
int md_control_epoll_turn(struct md_control_epoll *);
/* Cold activation reservation, before installing selectors in all holders.
 * 1: caller owns the reservation; 0: already admitted; negative: cannot admit.
 * While preparing, newly delivered aliases require the same selector, native
 * export is excluded and ctl cannot publish an interest. finish consumes only
 * an owned reservation. A failed admission leaves installed filters harmless;
 * no readiness transport or virtual subscription has been published. */
int md_control_epoll_admit_begin(struct md_control_epoll *);
int md_control_epoll_admit_finish(struct md_control_epoll *,int commit);
/* Linux interest identity is (open description, registering FD), not data.u64.
 * The caller retains identity during ctl; provider is owned by that identity.
 * ADD weakly observes identity, so the subscription cannot keep its target
 * alive. Collector calls borrow a strong reference before touching provider.
 * MOD/DEL use the exact identity.
 * Exclusive ADD uses kernel source wakeups; MOD of an exclusive interest is
 * invalid. Layered providers receive a captured native target for ADD only;
 * pass -1 otherwise. The capture is borrowed and never retained by the queue.
 * Suspend-inhibiting wakeup policy remains unsupported.
 * First ADD excludes pending native exports (EBUSY). After an actual native
 * export, virtual admission returns EOPNOTSUPP: external aliases cannot acquire
 * this authority. Failed sends release their reservations without that latch.
 * Once virtual, the authority requires local typed transfer, even after DEL. */
int md_control_epoll_ctl(struct md_control_epoll *,int operation,int fd,
    struct md_fd_object *,struct md_control_file,const struct epoll_event *,int native);
/* Nonblocking, allocation-free. At most one bounded ready-list turn per call.
 * Caller provides a separate result slice from native epoll_wait output. */
int md_control_epoll_collect(struct md_control_epoll *,struct epoll_event *,int capacity);
#endif
