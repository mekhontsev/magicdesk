#ifndef MD_GUEST_IDENTITY_H
#define MD_GUEST_IDENTITY_H
#include <stdint.h>
#include <sys/types.h>
#include <linux/capability.h>
#include "user_namespace.h"
#include "file_capability.h"

/* Guest credentials confer no Android/kernel authority. Copies retain the
 * immutable supplementary-group set; release each owning copy. */
#define MD_IDENTITY_GROUPS_MAX 65536
/* Linux 5.15's credential ABI, independent of host headers and backend support. */
#define MD_CAP_LAST CAP_CHECKPOINT_RESTORE
#define MD_CAP_VALID ((UINT64_C(1) << (MD_CAP_LAST + 1)) - 1)
struct md_identity_groups;
struct md_identity_ids { uint32_t real, effective, saved, fs; };
struct md_capabilities { uint64_t permitted, effective, inheritable, bounding, ambient; unsigned securebits; };
struct md_identity {
    struct md_identity_ids uid, gid;
    struct md_capabilities caps;
    int no_new_privs;
    struct md_identity_groups *groups;
    struct md_user_namespace *user_namespace;
};
struct md_exec_image;
struct md_exec_identity { struct md_identity value; int secure; };

struct md_identity md_identity_new(uint32_t uid, uint32_t gid);
struct md_identity md_identity_copy(const struct md_identity *);
/* Borrowed access(2) credentials, not an owning copy. Real-ID checks also
 * select permitted capabilities for namespace root and none for other users. */
struct md_identity md_identity_access(const struct md_identity *,int effective);
void md_identity_release(struct md_identity *);
int md_identity_groups(struct md_identity *, const uint32_t *, unsigned);
unsigned md_identity_group_count(const struct md_identity *);
const uint32_t *md_identity_group_data(const struct md_identity *);
int md_identity_in_group(const struct md_identity *, uint32_t);
/* Credential portion of process inspection. Namespace/root policy and native
 * proc access remain the caller's responsibility; this grants no host rights. */
int md_identity_traceme(const struct md_identity *parent, const struct md_identity *child);
int md_identity_may_inspect(const struct md_identity *observer, const struct md_identity *target,
    int filesystem_credentials);
/* set-ID operations accept IDs as seen in the caller's user namespace. */
int md_identity_setuid(struct md_identity *, uint32_t);
int md_identity_setgid(struct md_identity *, uint32_t);
int md_identity_setreuid(struct md_identity *, uint32_t, uint32_t);
int md_identity_setregid(struct md_identity *, uint32_t, uint32_t);
uint32_t md_identity_setfsuid(struct md_identity *, uint32_t);
uint32_t md_identity_setfsgid(struct md_identity *, uint32_t);
int md_identity_setresuid(struct md_identity *, uint32_t, uint32_t, uint32_t);
int md_identity_setresgid(struct md_identity *, uint32_t, uint32_t, uint32_t);
int md_identity_no_new_privs(struct md_identity *, unsigned);
int md_identity_may_chroot(const struct md_identity *);
int md_identity_capable(const struct md_identity *, unsigned);
int md_identity_capable_in(const struct md_identity *, const struct md_user_namespace *, unsigned);
/* Filesystem IDs are canonical and mounts are not idmapped. FOWNER needs the
 * owner UID mapped; other inode capabilities also require the owning GID. */
int md_identity_file_capable(const struct md_identity *, uint32_t uid, uint32_t gid, unsigned);
/* Stored IDs are canonical. Only syscall/ABI boundaries translate them. */
uint32_t md_identity_visible_id(const struct md_identity *, int group, uint32_t);
uint32_t md_identity_root(const struct md_identity *);
int md_identity_enter_user_namespace(struct md_identity *);
int md_identity_join_user_namespace(struct md_identity *, struct md_user_namespace *);
int md_identity_setgroups(struct md_identity *, uint32_t *, unsigned);
int md_identity_capset(struct md_identity *, uint64_t, uint64_t, uint64_t);
long md_identity_cap_prctl(struct md_identity *, unsigned long, const unsigned long *);
void md_identity_exec(const struct md_identity *, struct md_identity *);

/* Only the filesystem execution owner or explicit launcher supplies the logical owner and mode.
 * The retained image must already be sealed against write, shrink and growth;
 * an ELF pathname, guest-supplied stat result or guest-owned FD is not admission.
 * The filesystem owner captures metadata under the task's provider/mount context;
 * exec preparation binds it to the selected image, never to a second pathname. */
int md_exec_image_admit(int sealed_fd, uint32_t uid, uint32_t gid, mode_t mode,
    const struct md_file_capabilities *, struct md_exec_image **out);
/* Filesystem-only binfmt C admission; the handler, not a shebang, authorizes
 * taking credentials from this sealed input instead of the interpreter ELF. */
int md_exec_image_admit_interpreted(int sealed_fd,uint32_t uid,uint32_t gid,mode_t mode,
    const struct md_file_capabilities *,struct md_exec_image **out);
void md_exec_image_release(struct md_exec_image *);
int md_exec_image_dup(const struct md_exec_image *);

/* Prepare is side-effect free. The supervisor may publish the candidate only
 * after successful guest image admission, before entering the guest loader.
 * A failed exec leaves the old identity intact. secure supplies AT_SECURE;
 * value.uid/gid supply all four ID auxv entries. nosuid suppresses file privileges.
 * The candidate borrows current's groups until commit; it is not an owning copy. */
int md_identity_prepare_exec(const struct md_identity *, const struct md_exec_image *,
    int nosuid, struct md_exec_identity *);
#endif
