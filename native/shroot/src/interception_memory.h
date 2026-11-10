#ifndef MD_INTERCEPTION_MEMORY_H
#define MD_INTERCEPTION_MEMORY_H
#include <stdint.h>
#include <sys/types.h>
#include "interception_buffers.h"
#include "resource_order.h"
#include "private_memory.h"

/* Supervisor-owned mappings in one tracee address space. CLONE_VM shares this
 * owner; fork copies stack metadata and retains the shared buffer allocator.
 * Exec creates an empty owner. Worker pins may outlive every mapping owner. */
struct md_interception_memory;
struct md_memory_admission;
/* The caller owns this channel until detach; publication requires a stopped
 * task. Sharing/exposure revokes its native memory admission immediately. */
void md_memory_admission_publish(struct md_interception_memory *,struct md_memory_admission *,int eligible);
void md_memory_admission_detach(struct md_interception_memory *,struct md_memory_admission *);
struct md_svc_registry;
struct md_svc_registry *md_memory_svc(const struct md_interception_memory *);
int md_memory_svc_set(struct md_interception_memory *,struct md_svc_registry *);
struct md_interception_memory *md_memory_new(void);
/* Empty post-exec address space, sharing only retained file backing ownership
 * with its launch family. No mappings, private-memory authority or buffers survive. */
struct md_interception_memory *md_memory_new_image(struct md_interception_memory *);
struct md_interception_memory *md_memory_fork(struct md_interception_memory *, int shared);
int md_memory_sole_owner(const struct md_interception_memory *);
void md_memory_release(struct md_interception_memory *);
/* An in-flight proc open can already publish a native FD to CLONE_FILES peers.
 * Reserve before opening, commit success or unknown completion, cancel only a
 * confirmed failure. Successful authority can escape its opener's FD table. */
int md_memory_exposure_begin(struct md_interception_memory *,struct md_interception_memory **lease);
void md_memory_exposure_end(struct md_interception_memory **lease,int published);
int md_memory_exposed(const struct md_interception_memory *);
uintptr_t md_memory_stack_take(struct md_interception_memory *);
int md_memory_stack_add(struct md_interception_memory *, uintptr_t);
void md_memory_stack_return(struct md_interception_memory *, uintptr_t);
/* Reusable private pages for task-affine blocking operations. No shared IO
 * buffer stays pinned while the kernel waits. Callers serialize pool changes
 * with the mapping turn and return pages only after native access has ended. */
struct md_memory_page;
struct md_memory_page *md_memory_page_acquire(struct md_interception_memory *);
uintptr_t md_memory_page_address(const struct md_memory_page *);
void md_memory_page_publish(struct md_memory_page *,uintptr_t);
void md_memory_page_release(struct md_memory_page *);
int md_memory_buffers_prepare(struct md_interception_memory *, unsigned slots, size_t capacity,
    size_t clear_prefix, unsigned reserved);
struct md_interception_buffers *md_memory_buffers(struct md_interception_memory *);
/* Immutable installation inputs use a distinct sealed peer-read-only mapping.
 * Fork retains the pool and mapping; exec starts empty. Active users must hold
 * the existing mapping turn until the kernel has finished consuming bytes. */
int md_memory_readonly_prepare(struct md_interception_memory *,unsigned slots,size_t capacity);
struct md_interception_buffers *md_memory_readonly_buffers(struct md_interception_memory *);
int md_memory_readonly_mapped(struct md_interception_memory *,uintptr_t,size_t);
uintptr_t md_memory_readonly_base(const struct md_interception_memory *);
/* Immutable entry snapshots have independent sealed buffers and identities.
 * Publish only after filling the peer view. Fork retains mappings; exec drops
 * them. Destructive replacement invalidates the whole dependent view graph. */
int md_memory_snapshot_publish(struct md_interception_memory *,uint64_t identity,
    struct md_interception_buffers *,uintptr_t base);
uintptr_t md_memory_snapshot_address(const struct md_interception_memory *,uint64_t identity);
int md_memory_snapshot_intersects(const struct md_interception_memory *,uintptr_t,size_t);
int md_memory_snapshots_valid(const struct md_interception_memory *);
void md_memory_snapshots_invalidate(struct md_interception_memory *);
int md_memory_readonly_intersects(const struct md_interception_memory *,uintptr_t,size_t);
/* Successful or outcome-unknown destructive replacement retires admission. */
void md_memory_readonly_invalidate(struct md_interception_memory *,uintptr_t,size_t);
int md_memory_buffers_mapped(struct md_interception_memory *, uintptr_t base, size_t size);
uintptr_t md_memory_buffer_address(struct md_interception_memory *, const struct md_interception_buffer *);
/* Bootstrap channels are supplemented by small mapped blocks on demand.
 * Fork retains their shared allocators; outstanding worker pins prevent reuse.
 * Publication requires the mapping turn. Ordinary calls keep a direct pointer. */
int md_memory_channel_take(struct md_interception_memory *,struct md_interception_buffer *);
int md_memory_channels_publish(struct md_interception_memory *,struct md_interception_buffers *,uintptr_t base);
/* Mapping commit and destructive memory calls share this address-space turn.
 * The caller retains the memory owner until completing/cancelling the turn. */
int md_memory_order_begin(struct md_interception_memory *, struct md_resource_order *, struct md_resource_wakeup *);
/* Optional task-affine proc access. CLONE_VM shares it; fork/exec do not, since
 * each proc file pins an exact mm even when its original task disappears. */
int md_memory_private_access_set(struct md_interception_memory *, const struct md_private_memory_access *);
/* The caller holds the exact task stopped throughout capture. Failure is
 * optional capability loss, never a reason to change dumpability or identity. */
int md_memory_private_access_capture(struct md_interception_memory *, pid_t);
const struct md_private_memory_access *md_memory_private_access(const struct md_interception_memory *);
/* File provenance and VMA protection limits belong to the address space, not its
 * threads' descriptor tables. Prepare reserves storage before execution;
 * finish allocates nothing. All calls below require the address-space turn. */
struct md_memory_backing;
struct md_memory_file_range;
enum md_mapping_operation { MD_MAPPING_MAP, MD_MAPPING_UNMAP, MD_MAPPING_REMAP };
enum { MD_MAPPING_NOEXEC=1, MD_MAPPING_NOWRITE=2 };
struct md_mapping_change {
    enum md_mapping_operation operation;
    uintptr_t address;
    size_t length, new_length;
    unsigned flags;
    int restrictions, prepared;
    struct md_memory_backing *backing;
    uint64_t offset;
    struct md_memory_file_range *moving;
    size_t moving_count;
};
int md_memory_has_policy(const struct md_interception_memory *);
/* Conservative native-completion admission. Shared owners retain the mapping
 * turn through syscall exit: another thread could replace an unrelated range. */
int md_memory_mapping_observe(const struct md_interception_memory *,uintptr_t,size_t);
int md_memory_protect_check(const struct md_interception_memory *, uintptr_t, size_t, unsigned protection);
int md_memory_mapping_prepare(struct md_interception_memory *, struct md_mapping_change *,
    enum md_mapping_operation, uintptr_t, size_t, size_t new_length, unsigned flags, int restrictions);
/* Borrows a regular-file description and retains a shared CLOEXEC backing.
 * Independent address spaces share only file identity, not private bytes/offsets.
 * A failed syscall never publishes it. Native devices remain kernel-owned. */
int md_memory_mapping_backing(struct md_interception_memory *, struct md_mapping_change *, int fd, uint64_t offset);
/* Obtain a read-only backing from an authenticated caller-owned pin (O_PATH
 * is sufficient), reusing existing custody first. Returns 1 on capture, 0 for
 * nonregular files, or a negative errno. Denied reopening needs caller capture;
 * native mmap still enforces the application's descriptor access mode. */
int md_memory_mapping_pin(struct md_interception_memory *,struct md_mapping_change *,int pin,uint64_t offset);
/* Bootstrap-only import of existing private mappings of this exact open file.
 * The caller has not entered application code or published any patch yet. */
/* Bootstrap program and optional interpreter; one stopped-task map observation. */
int md_memory_image_backings(struct md_interception_memory *, pid_t, const int *fds, unsigned count);
/* Borrowed under the address-space turn. No pathname lookup or FD-number reuse.
 * Returns 1 for continuous provenance, 0 for an untracked/mixed range. */
int md_memory_file_view(const struct md_interception_memory *, uintptr_t, size_t,
    int *fd, uint64_t *offset);
void md_memory_mapping_finish(struct md_interception_memory *, struct md_mapping_change *, long result);
void md_memory_mapping_abandon(struct md_interception_memory *, struct md_mapping_change *);
#endif
