#ifndef MD_SYSV_SHM_H
#define MD_SYSV_SHM_H
#include "guest_identity.h"
#include <linux/shm.h>
#include <stddef.h>

/* A store-scoped IPC authority and supervisor-owned address spaces. Kernel
 * mappings carry the bytes; metadata never contains guest pointers to follow. */
enum md_shm_operation {
    MD_SHM_GET, MD_SHM_OPEN, MD_SHM_STAT, MD_SHM_SET, MD_SHM_REMOVE,
    MD_SHM_ATTACHED, MD_SHM_ABORT, MD_SHM_DETACH_SIZE, MD_SHM_DETACHED, MD_SHM_DETACH_ADDRESS
};
struct md_shm;
struct md_shm_space;
struct md_shm_mapping;
struct md_pid_identity;
int md_shm_open(const char *, uint64_t namespace_id, struct md_user_namespace *owner, struct md_shm **);
/* Lifetime can cross threads through namespace pins; database operations cannot.
 * The final owner closes only after every mapping/operation owner is released. */
struct md_shm *md_shm_retain(struct md_shm *);
void md_shm_close(struct md_shm *);
/* Export one attachment after namespace/guest permission checks. Success
 * transfers two FDs: backing bytes and an independent attachment lease. Close
 * both on failure; retain the lease for the complete mapping/pixmap lifetime. */
int md_shm_export(struct md_shm *,const struct md_identity *,pid_t,const struct md_pid_identity *,int segment,
    int readonly,int descriptors[2],uint64_t *size);
struct md_shm_space *md_shm_space_new(void);
struct md_shm_space *md_shm_space_fork(struct md_shm_space *, int shared);
int md_shm_space_release(struct md_shm_space *);
/* Includes pending attachments; fork must order behind their publication. */
int md_shm_space_used(const struct md_shm_space *);
int md_shm_abort(struct md_shm_mapping **);
size_t md_shm_pending_size(const struct md_shm_mapping *);
int md_shm_intersects(const struct md_shm_space *, uintptr_t, size_t);
int md_shm_unmapped(struct md_shm_space *, uintptr_t, size_t, pid_t,const struct md_pid_identity *);
/* An OPEN result is an owned FD; STAT publishes the ARM64 Linux ABI structure.
 * Other results are scalar syscall values. OPEN is pinned until commit/abort. */
long md_shm_call(struct md_shm *, struct md_shm_space *, struct md_shm_mapping **,
    const struct md_identity *,pid_t,const struct md_pid_identity *,unsigned,const unsigned long[4],struct shmid64_ds *);
#endif
