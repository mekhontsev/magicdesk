#ifndef MD_FD_ORIGINS_H
#define MD_FD_ORIGINS_H
#include "fs_anchor.h"
#include "resource_order.h"

/* Application provenance retains no data open description or offset.
 * Typed infrastructure entries own their transport, not application files.
 * Task-owner operations run on the supervisor; pins do not count as owners. */
struct md_fd_origins;
struct md_fd_channel;
struct md_fd_origin_entry;
struct md_control_actor;
struct md_control_pending;
struct md_fd_closed { struct md_fd_origin_entry *first; };
/* Allocation-free retirement of confirmed kernel closures. Progress consumes
 * one retained authority outside the table turn, returning its provider result.
 * Empty batches return zero without invoking a provider. */
long md_fd_closed_next(struct md_fd_closed *,const struct md_control_actor *,struct md_control_pending **);
struct md_fd_observation;
struct md_fd_loan;
struct md_fd_origin_update {
    struct md_fd_origins *table;
    struct md_fd_origin_entry *entry;
};

/* Zero-initialized, intrusive, caller-owned request. Each record belongs to one
 * event loop; the shared table serializes admission across event loops.
 * Records remain alive until cancellation removes them or completion retires
 * them. A RUNNING cancellation still needs the exact kernel terminal event. */
struct md_fd_order {
    struct md_fd_origins *table;
    struct md_resource_order order;
};
/* Short FD capture/publication only, never blocking receive/FIFO readiness.
 * Begin returns 1 when ready, 0 when queued, or a negative errno. No allocation,
 * waits or callbacks. Each accepted record pins its shared file-table owner. */
/* NULL wakeup is for a synchronous owner. Readiness is a hint; the owning loop
 * rechecks through state(). The caller owns each record until its retirement. */
int md_fd_order_begin(struct md_fd_origins *, struct md_fd_order *, struct md_resource_wakeup *);
enum md_resource_order_state md_fd_order_state(const struct md_fd_order *);
int md_fd_order_start(struct md_fd_order *);
/* Cancel returns 1 if kernel completion is still required, otherwise 0.
 * A newly ready successor wakes its owner without exposing its request pointer.
 * Complete follows the kernel result AND its metadata publication, except an
 * exclusive metadata-neutral operation admitted for unobserved native execution. */
int md_fd_order_cancel(struct md_fd_order *);
int md_fd_order_complete(struct md_fd_order *);

struct md_fd_origins *md_fd_origins_new(void);
struct md_fd_origins *md_fd_origins_fork(struct md_fd_origins *, int shared);
struct md_fd_origins *md_fd_origins_unshare_prepare(struct md_fd_origins *);
void md_fd_origins_release(struct md_fd_origins *);
struct md_fd_origins *md_fd_origins_pin(struct md_fd_origins *);
void md_fd_origins_unpin(struct md_fd_origins *);
/* A retained origin, or NULL for an untracked descriptor, not proof of EBADF. */
struct md_fs_anchor *md_fd_origins_get(struct md_fd_origins *, int fd, unsigned *flags);
struct md_fd_object *md_fd_origins_object(struct md_fd_origins *, int fd, unsigned *flags);
/* Optimistic discovery outside a table turn. Before using provider discovery
 * made from this snapshot, acquire the turn and validate its generation.
 * Metadata-free replacement and uncertain terminal mutations also invalidate
 * it. This is not an FD identity or a count of operations. */
int md_fd_origins_observe(struct md_fd_origins *,int fd,struct md_fd_observation *);
int md_fd_origins_validate(const struct md_fd_order *,uint64_t generation);
/* Caller-owned vector of retained origins, captured under one metadata lock.
 * -1 and unannotated FDs produce NULL, not a guessed mount. Repeated source
 * numbers retain independent references. The caller holds the source table
 * turn through actual kernel FD capture; these anchors retain no data OFDs. */
int md_fd_origins_capture(struct md_fd_origins *, const int *fds, unsigned count,
        struct md_fs_anchor **origins);
int md_fd_origins_capture_objects(struct md_fd_origins *, const int *fds, unsigned count,
        struct md_fd_object **);
/* Cold snapshot of one authority's tracked aliases. Requires a running table
 * turn through use of the result. No allocation, retained kernel descriptors,
 * callbacks or assumed global owner. Count-only calls use capacity zero/NULL;
 * ENOSPC reports the required count without writing a partial vector. */
int md_fd_origins_aliases(const struct md_fd_order *,const struct md_fd_object *,
        int *numbers,unsigned capacity,unsigned *count);
/* Prepare before a kernel mutation; finish is allocation-free. A negative
 * result discards it. NULL origin removes any prior destination annotation.
 * The caller orders kernel mutations; this lock alone cannot order syscalls. */
int md_fd_origins_prepare(struct md_fd_origins *, struct md_fs_anchor *, unsigned flags,
        struct md_fd_origin_update *);
int md_fd_origins_prepare_object(struct md_fd_origins *, struct md_fd_object *, unsigned flags,
        struct md_fd_origin_update *);
/* The same publication/mutation path owns both origins and infrastructure.
 * These operations require the table turn, just like ordinary descriptor dup. */
int md_fd_origins_prepare_copy(struct md_fd_origins *, int source, unsigned flags,
        struct md_fd_origin_update *);
int md_fd_origins_prepare_channel(struct md_fd_origins *, struct md_fd_channel *, unsigned flags,
        struct md_fd_origin_update *);
/* Operation-owned CLOEXEC slots. A loan owns no kernel FD or table reference;
 * the operation retains its home table through end(). Fork-copied slots are
 * immediately collectable in the child; explicit dup creates an application
 * alias without loan ownership. Kernel receipts publish loans like any origin. */
struct md_fd_loan *md_fd_loan_new(struct md_fd_origins *);
int md_fd_loan_active_in(const struct md_fd_loan *,const struct md_fd_origins *);
void md_fd_loan_end(struct md_fd_loan *);
void md_fd_loan_release(struct md_fd_loan *);
/* A metadata-free prepared update becomes a native CLOEXEC loan. */
int md_fd_origins_prepare_loan(struct md_fd_origin_update *,struct md_fd_loan *);
/* Provisional application slot, with its real CLOEXEC flag. Its RUNNING turn
 * must remain held until publication or verified native retirement. */
int md_fd_origins_prepare_custody(struct md_fd_origin_update *,struct md_fd_loan *,const struct md_fd_order *);
/* Prepared (not yet published) custody becomes an ordinary application FD. */
void md_fd_origins_release_custody(struct md_fd_origin_update *);
/* Under a RUNNING table turn: 1 selects an expired loan, 0 means none.
 * Keep that turn through native close and close_range metadata retirement.
 * Ambiguous terminal mutations return ESTALE, never an assumed identity. */
int md_fd_origins_loan_next(const struct md_fd_order *,int *number);
/* A retained operation owner for a slot in this range. The RUNNING turn must
 * cover any decision made from this result. No kernel FD is captured here.
 * Only loan entries are visited; ordinary origin inventory is not scanned.
 * A replaced slot no longer belongs to its previous operation. */
int md_fd_origins_loan_range(const struct md_fd_order *,unsigned first,unsigned last,
        int *number,struct md_fd_loan **);
int md_fd_origins_loan_matches(const struct md_fd_order *,int number,const struct md_fd_loan *);
int md_fd_origins_has_loans(struct md_fd_origins *);
/* Supervisor task ownership, queried under a running table turn. A sole
 * sleeping owner cannot create another CLONE_FILES holder or replace a slot. */
int md_fd_origins_exclusive(const struct md_fd_order *);
/* Admit an unobserved native close only in an exclusive table with no slot
 * metadata. Invalidates optimistic discovery; does not claim kernel success
 * or remove an origin. Filters and the kernel retain the actual result. */
int md_fd_origins_close_untracked(const struct md_fd_order *,int number);
/* Borrow value-only native authority under a RUNNING turn. Unknown,
 * provisional and uncertain slots, including typed objects with path origins,
 * are not native authority. Does not retain an object or capture a data OFD.
 * The regular predicate additionally excludes directory cursors and devices. */
/* The anchor is borrowed until this running file-table turn completes. No
 * slot mutation can invalidate it while the caller retains that turn. */
struct md_fd_native { unsigned kind, flags; struct md_fs_anchor *origin; };
int md_fd_origins_native(const struct md_fd_order *,int number,struct md_fd_native *);
int md_fd_origins_native_regular(const struct md_fd_order *,int number);
/* Regular data files and metadata-only anonymous pipes, not virtual FIFOs. */
int md_fd_origins_native_io(const struct md_fd_order *,int number);
/* No published object can supply virtual readiness. Requires a RUNNING turn;
 * counts potential providers, not their mutable current readiness. */
int md_fd_origins_native_poll(const struct md_fd_order *);
struct pollfd;
/* Same conservative proof for a captured vector: unrelated providers do not
 * require adaptation. The running table turn protects slot classification. */
int md_fd_origins_native_poll_vector(const struct md_fd_order *,const struct pollfd *,size_t);
/* No entry yields NULL/-1; ambiguous terminal mutations fail with ESTALE,
 * rather than creating another potentially orphaned infrastructure slot. */
int md_fd_origins_channel(struct md_fd_origins *, int *number, struct md_fd_channel **);
/* Only used after ESTALE, under the same table turn. A retained candidate is
 * not usable until an actual kernel observation resolves its identity/flags. */
int md_fd_origins_channel_candidate(struct md_fd_origins *, int *number, struct md_fd_channel **);
/* same==0 retires metadata only, never the guest slot. No allocations. */
int md_fd_origins_channel_resolve(struct md_fd_origins *, int number, struct md_fd_channel *, int same, unsigned flags);
void md_fd_origins_finish(struct md_fd_origin_update *, int result_fd);
void md_fd_origins_finish_closed(struct md_fd_origin_update *,int result_fd,struct md_fd_closed *);
/* Terminal mutation with no trustworthy result may have installed an alias.
 * Notify inventory-dependent owners before discarding unpublished metadata. */
void md_fd_origins_abandon(struct md_fd_origin_update *);
/* Prepare a complete delivery before the kernel receive. Failure cancels all
 * prepared entries. A retained origin vector can prepare several independent
 * deliveries (MSG_PEEK); it is never consumed by preparation or publication. */
int md_fd_origins_prepare_batch(struct md_fd_origins *, struct md_fd_object *const *origins,
        unsigned flags, unsigned count, struct md_fd_origin_update *updates);
/* Publish an installed prefix in one metadata transaction, discard the rest.
 * The destination table turn must cover kernel installation and publication.
 * All numbers/owners are validated first; failure changes nothing. Zero received
 * discards the prepared delivery, not the source snapshot. No allocation;
 * retired anchors are released outside the metadata lock. */
int md_fd_origins_finish_batch(struct md_fd_origin_update *, unsigned count,
        const int *received_fds, unsigned received);
int md_fd_origins_set_flags(struct md_fd_origins *, int fd, unsigned flags);
int md_fd_origins_close_range(struct md_fd_origins *, unsigned first, unsigned last, int cloexec);
int md_fd_origins_close_collect(struct md_fd_origins *,unsigned first,unsigned last,int cloexec,struct md_fd_closed *);
int md_fd_origins_abandon_range(struct md_fd_origins *, unsigned first, unsigned last);
void md_fd_origins_exec(struct md_fd_origins *);
void md_fd_origins_exec_collect(struct md_fd_origins *,struct md_fd_closed *);
/* Only the last real task owner closes a shared table. Aborted copies and pins
 * are released normally and must never manufacture descriptor-close events. */
void md_fd_origins_exit_collect(struct md_fd_origins *,struct md_fd_closed *);
#endif
