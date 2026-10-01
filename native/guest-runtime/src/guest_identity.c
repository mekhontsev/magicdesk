#define _GNU_SOURCE
#include "guest_identity.h"
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/prctl.h>
#include <linux/securebits.h>
#include <unistd.h>

struct md_exec_image { int fd; uint32_t uid, gid; mode_t mode; };
struct md_identity_groups { atomic_uint references; unsigned count; uint32_t ids[]; };
static int compare_gid(const void *a, const void *b) {
    uint32_t x = *(const uint32_t *)a, y = *(const uint32_t *)b;
    return (x > y) - (x < y);
}

struct md_identity md_identity_copy(const struct md_identity *v) {
    if (v->groups) atomic_fetch_add_explicit(&v->groups->references, 1, memory_order_relaxed);
    return *v;
}
void md_identity_release(struct md_identity *v) {
    if (v->groups && atomic_fetch_sub_explicit(&v->groups->references, 1, memory_order_acq_rel) == 1)
        free(v->groups);
    v->groups = NULL;
}
int md_identity_groups(struct md_identity *v, const uint32_t *ids, unsigned count) {
    if (count > MD_IDENTITY_GROUPS_MAX) return -EINVAL;
    for (unsigned i = 0; i < count; i++) if (ids[i] == UINT32_MAX) return -EINVAL;
    struct md_identity_groups *g = count ? malloc(sizeof(*g) + count * sizeof(*ids)) : NULL;
    if (count && !g) return -ENOMEM;
    if (g) {
        atomic_init(&g->references, 1); g->count = count; memcpy(g->ids, ids, count * sizeof(*ids));
        qsort(g->ids, count, sizeof(*ids), compare_gid);
    }
    md_identity_release(v); v->groups = g;
    return 0;
}
unsigned md_identity_group_count(const struct md_identity *v) { return v->groups ? v->groups->count : 0; }
const uint32_t *md_identity_group_data(const struct md_identity *v) { return v->groups ? v->groups->ids : NULL; }
int md_identity_in_group(const struct md_identity *v, uint32_t gid) {
    if (v->gid.fs == gid) return 1;
    return v->groups && bsearch(&gid, v->groups->ids, v->groups->count,
        sizeof(gid), compare_gid) != NULL;
}

struct md_identity md_identity_new(uint32_t uid, uint32_t gid) {
    /* Only capabilities with an authority in this runtime are advertised.
     * These never authorize operations on Android's native resources. */
    uint64_t supported = (1ULL << CAP_CHOWN) | (1ULL << CAP_DAC_OVERRIDE)
        | (1ULL << CAP_FOWNER) | (1ULL << CAP_FSETID) | (1ULL << CAP_SETUID)
        | (1ULL << CAP_SETGID) | (1ULL << CAP_SETPCAP) | (1ULL << CAP_SYS_CHROOT)
        | (1ULL << CAP_IPC_OWNER) | (1ULL << CAP_SETFCAP);
    return (struct md_identity){.uid = {uid, uid, uid, uid}, .gid = {gid, gid, gid, gid},
        .caps = {.bounding = supported, .permitted = uid ? 0 : supported,
            .effective = uid ? 0 : supported}};
}
int md_identity_capable(const struct md_identity *v, unsigned cap) {
    return cap < 64 && (v->caps.effective & (1ULL << cap)) != 0;
}
static void uid_changed(struct md_identity *v, const struct md_identity_ids *old) {
    if (v->caps.securebits & SECBIT_NO_SETUID_FIXUP) return;
    if ((!old->real || !old->effective || !old->saved)
            && v->uid.real && v->uid.effective && v->uid.saved) {
        if (!(v->caps.securebits & SECBIT_KEEP_CAPS)) v->caps.permitted = 0;
        v->caps.effective = v->caps.ambient = 0;
    }
    if (!old->effective && v->uid.effective) v->caps.effective = 0;
    if (old->effective && !v->uid.effective) v->caps.effective = v->caps.permitted;
    uint64_t fs = (1ULL << CAP_CHOWN) | (1ULL << CAP_DAC_OVERRIDE)
        | (1ULL << CAP_FOWNER) | (1ULL << CAP_FSETID);
    if (!old->fs && v->uid.fs) v->caps.effective &= ~fs;
    if (old->fs && !v->uid.fs) v->caps.effective |= v->caps.permitted & fs;
}
int md_identity_capset(struct md_identity *v, uint64_t permitted, uint64_t effective, uint64_t inheritable) {
    uint64_t allowed = v->caps.inheritable | v->caps.permitted;
    if (md_identity_capable(v, CAP_SETPCAP)) allowed |= v->caps.bounding;
    if ((permitted & ~v->caps.permitted) || (effective & ~permitted)
            || (inheritable & ~allowed) || (inheritable & ~(v->caps.inheritable | v->caps.bounding))) return -EPERM;
    v->caps.permitted = permitted; v->caps.effective = effective; v->caps.inheritable = inheritable;
    v->caps.ambient &= permitted & inheritable;
    return 0;
}
long md_identity_cap_prctl(struct md_identity *v, unsigned long op, const unsigned long *a) {
    switch (op) {
    case PR_GET_KEEPCAPS: return !!(v->caps.securebits & SECBIT_KEEP_CAPS);
    case PR_SET_KEEPCAPS:
        if (a[0] > 1) return -EINVAL;
        if (v->caps.securebits & SECBIT_KEEP_CAPS_LOCKED) return -EPERM;
        v->caps.securebits = (v->caps.securebits & ~SECBIT_KEEP_CAPS) | (a[0] ? SECBIT_KEEP_CAPS : 0);
        return 0;
    case PR_GET_SECUREBITS: return v->caps.securebits;
    case PR_SET_SECUREBITS: {
        unsigned locks = SECBIT_NOROOT_LOCKED | SECBIT_NO_SETUID_FIXUP_LOCKED
            | SECBIT_KEEP_CAPS_LOCKED | SECBIT_NO_CAP_AMBIENT_RAISE_LOCKED;
        if (a[0] & ~(unsigned long)(locks | (locks >> 1))) return -EINVAL;
        if (!md_identity_capable(v, CAP_SETPCAP)
                || ((v->caps.securebits & locks) & ~a[0])
                || (((v->caps.securebits ^ a[0]) << 1) & v->caps.securebits & locks)) return -EPERM;
        v->caps.securebits = a[0]; return 0;
    }
    case PR_CAPBSET_READ: case PR_CAPBSET_DROP:
        if (a[0] > CAP_LAST_CAP) return -EINVAL;
        if (op == PR_CAPBSET_READ) return !!(v->caps.bounding & (1ULL << a[0]));
        if (!md_identity_capable(v, CAP_SETPCAP)) return -EPERM;
        v->caps.bounding &= ~(1ULL << a[0]); return 0;
    case PR_CAP_AMBIENT:
        if (a[2] || a[3]) return -EINVAL;
        if (a[0] == PR_CAP_AMBIENT_CLEAR_ALL) {
            if (a[1]) return -EINVAL;
            v->caps.ambient = 0; return 0;
        }
        if (a[1] > CAP_LAST_CAP) return -EINVAL;
        uint64_t bit = 1ULL << a[1];
        if (a[0] == PR_CAP_AMBIENT_IS_SET) return !!(v->caps.ambient & bit);
        if (a[0] == PR_CAP_AMBIENT_LOWER) { v->caps.ambient &= ~bit; return 0; }
        if (a[0] != PR_CAP_AMBIENT_RAISE) return -EINVAL;
        if (!(v->caps.permitted & v->caps.inheritable & bit)
                || (v->caps.securebits & SECBIT_NO_CAP_AMBIENT_RAISE)) return -EPERM;
        v->caps.ambient |= bit; return 0;
    default: return -ENOTSUP;
    }
}
void md_identity_exec(const struct md_identity *old, struct md_identity *next) {
    int privileged = old->uid.effective != next->uid.effective || old->gid.effective != next->gid.effective;
    if (privileged) next->caps.ambient = 0;
    uint64_t permitted = next->caps.ambient;
    if (!(next->caps.securebits & SECBIT_NOROOT) && (!next->uid.real || !next->uid.effective))
        permitted |= next->caps.bounding | next->caps.inheritable;
    if (old->no_new_privs) permitted &= old->caps.permitted;
    next->caps.permitted = permitted;
    next->caps.effective = !next->uid.effective && !(next->caps.securebits & SECBIT_NOROOT)
        ? permitted : next->caps.ambient;
    next->caps.securebits &= ~SECBIT_KEEP_CAPS;
}
static int existing(const struct md_identity_ids *ids, uint32_t id) {
    return id == UINT32_MAX || id == ids->real || id == ids->effective || id == ids->saved;
}
static int setres(struct md_identity_ids *ids, int privileged, uint32_t r, uint32_t e, uint32_t s) {
    if (!privileged && (!existing(ids, r) || !existing(ids, e) || !existing(ids, s))) return -EPERM;
    if (r != UINT32_MAX) ids->real = r;
    if (e != UINT32_MAX) ids->effective = e;
    if (s != UINT32_MAX) ids->saved = s;
    ids->fs = ids->effective;
    return 0;
}
int md_identity_setresuid(struct md_identity *value, uint32_t r, uint32_t e, uint32_t s) {
    struct md_identity_ids old = value->uid;
    int result = setres(&value->uid, md_identity_capable(value, CAP_SETUID), r, e, s);
    if (!result) uid_changed(value, &old);
    return result;
}
int md_identity_setresgid(struct md_identity *value, uint32_t r, uint32_t e, uint32_t s) {
    return setres(&value->gid, md_identity_capable(value, CAP_SETGID), r, e, s);
}
static int setid(struct md_identity_ids *ids, int root, uint32_t id) {
    if (id == UINT32_MAX) return -EINVAL;
    if (root) *ids = (struct md_identity_ids){id, id, id, id};
    else if (id == ids->real || id == ids->saved) ids->effective = ids->fs = id;
    else return -EPERM;
    return 0;
}
int md_identity_setuid(struct md_identity *v, uint32_t id) {
    struct md_identity_ids old = v->uid;
    int r = setid(&v->uid, md_identity_capable(v, CAP_SETUID), id);
    if (!r) uid_changed(v, &old);
    return r;
}
int md_identity_setgid(struct md_identity *v, uint32_t id) { return setid(&v->gid, md_identity_capable(v, CAP_SETGID), id); }
static int setre(struct md_identity_ids *ids, int root, uint32_t r, uint32_t e) {
    if (!root && ((r != UINT32_MAX && r != ids->real && r != ids->effective) || !existing(ids, e)))
        return -EPERM;
    uint32_t old_real = ids->real;
    if (r != UINT32_MAX) ids->real = r;
    if (e != UINT32_MAX) ids->effective = e;
    if (r != UINT32_MAX || (e != UINT32_MAX && e != old_real)) ids->saved = ids->effective;
    ids->fs = ids->effective;
    return 0;
}
int md_identity_setreuid(struct md_identity *v, uint32_t r, uint32_t e) {
    struct md_identity_ids old = v->uid;
    int result = setre(&v->uid, md_identity_capable(v, CAP_SETUID), r, e);
    if (!result) uid_changed(v, &old);
    return result;
}
int md_identity_setregid(struct md_identity *v, uint32_t r, uint32_t e) { return setre(&v->gid, md_identity_capable(v, CAP_SETGID), r, e); }
static uint32_t setfs(struct md_identity_ids *ids, int root, uint32_t id) {
    uint32_t old = ids->fs;
    if (id != UINT32_MAX && (root || existing(ids, id) || id == old)) ids->fs = id;
    return old;
}
uint32_t md_identity_setfsuid(struct md_identity *v, uint32_t id) {
    struct md_identity_ids old = v->uid;
    uint32_t result = setfs(&v->uid, md_identity_capable(v, CAP_SETUID), id);
    uid_changed(v, &old); return result;
}
uint32_t md_identity_setfsgid(struct md_identity *v, uint32_t id) { return setfs(&v->gid, md_identity_capable(v, CAP_SETGID), id); }
int md_identity_no_new_privs(struct md_identity *value, unsigned enable) {
    if (enable != 1) return -EINVAL;
    value->no_new_privs = 1;
    return 0;
}
int md_identity_may_chroot(const struct md_identity *value) { return md_identity_capable(value, CAP_SYS_CHROOT); }

int md_exec_image_admit(int fd, uint32_t uid, uint32_t gid, mode_t mode, struct md_exec_image **out) {
    *out = NULL;
    if (uid == UINT32_MAX || gid == UINT32_MAX || !S_ISREG(mode) || (mode & ~(S_IFREG | 07777)))
        return -EINVAL;
    int retained = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (retained < 0) return -errno;
    struct stat st;
    int error = fstat(retained, &st) ? -errno : 0;
    if (!error && !S_ISREG(st.st_mode)) error = -EACCES;
    int seals = !error ? fcntl(retained, F_GET_SEALS) : -1;
    const int required = F_SEAL_WRITE | F_SEAL_SHRINK | F_SEAL_GROW;
    if (!error && (seals < 0 || (seals & required) != required)) error = -EPERM;
    /* Scripts cannot obtain set-ID admission. Full ELF/interpreter validation
     * remains the loader's responsibility; this accepts only the native ABI. */
    Elf64_Ehdr header;
    ssize_t n = !error ? pread(retained, &header, sizeof(header), 0) : -1;
    if (!error && (n != sizeof(header) || header.e_ident[EI_MAG0] != ELFMAG0
            || header.e_ident[EI_MAG1] != ELFMAG1 || header.e_ident[EI_MAG2] != ELFMAG2
            || header.e_ident[EI_MAG3] != ELFMAG3 || header.e_ident[EI_CLASS] != ELFCLASS64
            || header.e_ident[EI_DATA] != ELFDATA2LSB || header.e_machine != EM_AARCH64
            || header.e_ident[EI_VERSION] != EV_CURRENT || header.e_version != EV_CURRENT
            || header.e_ehsize != sizeof(header) || (header.e_type != ET_EXEC && header.e_type != ET_DYN)))
        error = -ENOEXEC;
    struct md_exec_image *image = !error ? malloc(sizeof(*image)) : NULL;
    if (!error && !image) error = -ENOMEM;
    if (error) { close(retained); return error; }
    *image = (struct md_exec_image){retained, uid, gid, mode};
    *out = image;
    return 0;
}
void md_exec_image_release(struct md_exec_image *image) {
    if (image) { close(image->fd); free(image); }
}
int md_exec_image_dup(const struct md_exec_image *image) {
    int fd = fcntl(image->fd, F_DUPFD_CLOEXEC, 0);
    return fd < 0 ? -errno : fd;
}
int md_identity_prepare_exec(const struct md_identity *current, const struct md_exec_image *image,
        int nosuid, struct md_exec_identity *out) {
    if (!image) return -EACCES;
    unsigned permission = current->uid.fs == image->uid ? S_IXUSR
        : md_identity_in_group(current, image->gid) ? S_IXGRP : S_IXOTH;
    if (md_identity_capable(current, CAP_DAC_OVERRIDE)) permission = S_IXUSR | S_IXGRP | S_IXOTH;
    if (!(image->mode & permission)) return -EACCES;
    struct md_identity next = *current;
    if (!current->no_new_privs && !nosuid) {
        if (image->mode & S_ISUID) next.uid.effective = image->uid;
        if ((image->mode & (S_ISGID | S_IXGRP)) == (S_ISGID | S_IXGRP)) next.gid.effective = image->gid;
    }
    next.uid.saved = next.uid.fs = next.uid.effective;
    next.gid.saved = next.gid.fs = next.gid.effective;
    md_identity_exec(current, &next);
    *out = (struct md_exec_identity){.value = next,
        .secure = next.uid.real != next.uid.effective || next.gid.real != next.gid.effective};
    return 0;
}
