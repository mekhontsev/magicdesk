#ifndef MD_POSIX_ACL_H
#define MD_POSIX_ACL_H
#include <stddef.h>
#include <stdint.h>
#include "guest_identity.h"

#define MD_ACL_ACCESS_NAME "system.posix_acl_access"
#define MD_ACL_DEFAULT_NAME "system.posix_acl_default"
#define MD_ACL_MAX 65532U
enum { MD_ACL_ACCESS=1, MD_ACL_DEFAULT=2 };
enum { MD_ACL_USER_OBJ=1, MD_ACL_USER=2, MD_ACL_GROUP_OBJ=4,
    MD_ACL_GROUP=8, MD_ACL_MASK=16, MD_ACL_OTHER=32 };
struct md_acl_entry { uint16_t tag, permissions; uint32_t id; };
struct md_acl { uint32_t version; struct md_acl_entry entries[]; };
int md_acl_valid(const void *, size_t);
unsigned md_acl_mode(const struct md_acl *, size_t, unsigned);
void md_acl_chmod(struct md_acl *, size_t, unsigned, int inherit);
int md_acl_permission(const struct md_acl *, size_t, const struct md_identity *,
    uint32_t owner, uint32_t group, unsigned requested);
#endif
