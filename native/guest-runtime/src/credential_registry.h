#ifndef MD_CREDENTIAL_REGISTRY_H
#define MD_CREDENTIAL_REGISTRY_H
#include "guest_identity.h"

struct md_credentials;
struct md_credentials *md_credentials_create(void);
void md_credentials_destroy(struct md_credentials *);
int md_credentials_publish(struct md_credentials *, pid_t tid, pid_t tgid, const struct md_identity *);
void md_credentials_forget(struct md_credentials *, pid_t tid);
/* peer==0 is a kernel-notification/internal caller. RPC callers must own the
 * TID's thread group; the supervisor may request on behalf of its tracee. */
int md_credentials_read(struct md_credentials *, pid_t tid, pid_t peer, struct md_identity *);
#endif
