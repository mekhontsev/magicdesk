#define _GNU_SOURCE
#include "guest_identity.h"
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>

struct md_exec_image { int fd; uint32_t uid, gid; mode_t mode; };

struct md_identity md_identity_new(uint32_t uid, uint32_t gid) {
    return (struct md_identity){.uid = {uid, uid, uid, uid}, .gid = {gid, gid, gid, gid}};
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
    return setres(&value->uid, value->uid.effective == 0, r, e, s);
}
int md_identity_setresgid(struct md_identity *value, uint32_t r, uint32_t e, uint32_t s) {
    return setres(&value->gid, value->uid.effective == 0, r, e, s);
}
int md_identity_no_new_privs(struct md_identity *value, unsigned enable) {
    if (enable != 1) return -EINVAL;
    value->no_new_privs = 1;
    return 0;
}
int md_identity_may_chroot(const struct md_identity *value) { return value->uid.effective == 0; }

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
        : current->gid.fs == image->gid ? S_IXGRP : S_IXOTH;
    if (current->uid.fs == 0) permission = S_IXUSR | S_IXGRP | S_IXOTH;
    if (!(image->mode & permission)) return -EACCES;
    struct md_identity next = *current;
    if (!current->no_new_privs && !nosuid) {
        if (image->mode & S_ISUID) next.uid.effective = image->uid;
        if ((image->mode & (S_ISGID | S_IXGRP)) == (S_ISGID | S_IXGRP)) next.gid.effective = image->gid;
    }
    next.uid.saved = next.uid.fs = next.uid.effective;
    next.gid.saved = next.gid.fs = next.gid.effective;
    *out = (struct md_exec_identity){.value = next,
        .secure = next.uid.real != next.uid.effective || next.gid.real != next.gid.effective};
    return 0;
}
