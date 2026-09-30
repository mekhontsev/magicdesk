#ifndef MD_GUEST_IDENTITY_H
#define MD_GUEST_IDENTITY_H
#include <stdint.h>
#include <sys/types.h>

/* Supervisor-side credentials for explicitly admitted executable images. IDs describe
 * this guest domain only and confer no Android/kernel credentials. File
 * capabilities, supplementary groups and securebits are outside this model. */
struct md_identity_ids { uint32_t real, effective, saved, fs; };
struct md_identity {
    struct md_identity_ids uid, gid;
    int no_new_privs;
};
struct md_exec_image;
struct md_exec_identity { struct md_identity value; int secure; };

struct md_identity md_identity_new(uint32_t uid, uint32_t gid);
int md_identity_setresuid(struct md_identity *, uint32_t, uint32_t, uint32_t);
int md_identity_setresgid(struct md_identity *, uint32_t, uint32_t, uint32_t);
int md_identity_no_new_privs(struct md_identity *, unsigned);
int md_identity_may_chroot(const struct md_identity *);

/* Only a trusted importer/launcher supplies the logical owner and mode.
 * The retained image must already be sealed against write, shrink and growth;
 * an ELF pathname, guest stat result or guest-owned FD is not admission.
 * Copying/validating package contents and authenticating that importer are
 * integration requirements, not performed by this narrow contract. */
int md_exec_image_admit(int sealed_fd, uint32_t uid, uint32_t gid, mode_t mode,
    struct md_exec_image **out);
void md_exec_image_release(struct md_exec_image *);
int md_exec_image_dup(const struct md_exec_image *);

/* Prepare is side-effect free. The supervisor may publish the candidate only
 * after successful guest image admission, before entering the guest loader.
 * A failed exec leaves the old identity intact. secure supplies AT_SECURE;
 * value.uid/gid supply all four ID auxv entries. nosuid suppresses set-ID. */
int md_identity_prepare_exec(const struct md_identity *, const struct md_exec_image *,
    int nosuid, struct md_exec_identity *);
#endif
