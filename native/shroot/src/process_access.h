#ifndef MD_PROCESS_ACCESS_H
#define MD_PROCESS_ACCESS_H
#include "guest_identity.h"

/* Linux dumpability belongs to the mm, not to a thread's credentials.
 * The supervisor writes; retained filesystem snapshots read atomically. */
struct md_process_access;
struct md_process_access *md_process_access_new(struct md_user_namespace *);
struct md_process_access *md_process_access_retain(struct md_process_access *);
struct md_process_access *md_process_access_fork(struct md_process_access *, int shared);
void md_process_access_release(struct md_process_access *);
int md_process_dumpable(const struct md_process_access *);
int md_process_set_dumpable(struct md_process_access *, unsigned long);
int md_process_may_inspect(const struct md_identity *, const struct md_identity *, int filesystem,
    const struct md_process_access *);
void md_process_file_owner(const struct md_identity *, const struct md_process_access *,
    unsigned mode, uint32_t native_uid, uint32_t *uid, uint32_t *gid);
void md_process_access_credentials(struct md_process_access *, const struct md_identity *before,
    const struct md_identity *after);
#endif
