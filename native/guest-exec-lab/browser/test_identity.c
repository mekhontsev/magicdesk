#define _GNU_SOURCE
#include "guest_identity.h"
#include <assert.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static int image_fd(void) {
    int fd = memfd_create("md-identity-test", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    assert(fd >= 0);
    /* Model tests need only a header, not an executable program. */
    Elf64_Ehdr header = {.e_type = ET_DYN, .e_machine = EM_AARCH64,
        .e_version = EV_CURRENT, .e_ehsize = sizeof(header)};
    memcpy(header.e_ident, ELFMAG, SELFMAG);
    header.e_ident[EI_CLASS] = ELFCLASS64;
    header.e_ident[EI_DATA] = ELFDATA2LSB;
    header.e_ident[EI_VERSION] = EV_CURRENT;
    assert(write(fd, &header, sizeof(header)) == sizeof(header));
    return fd;
}
static void immutable_admission(void) {
    int fd = image_fd();
    struct md_exec_image *image = NULL;
    assert(md_exec_image_admit(fd, 0, 0, S_IFREG | 04755, &image) == -EPERM && !image);
    assert(!fcntl(fd, F_ADD_SEALS, F_SEAL_FUTURE_WRITE | F_SEAL_SHRINK | F_SEAL_GROW));
    assert(md_exec_image_admit(fd, 0, 0, S_IFREG | 04755, &image) == -EPERM && !image);
    assert(!fcntl(fd, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_SEAL));
    assert(!md_exec_image_admit(fd, 0, 0, S_IFREG | 04755, &image));
    int retained = md_exec_image_dup(image); assert(retained >= 0 && !close(fd));
    struct stat st;
    assert(!fstat(retained, &st) && st.st_uid == 2000 && st.st_gid == 2000);
    assert(pwrite(retained, "X", 1, 0) == -1 && errno == EPERM);
    assert(ftruncate(retained, 0) == -1 && errno == EPERM);
    assert(ftruncate(retained, 8192) == -1 && errno == EPERM);
    assert(mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, retained, 0) == MAP_FAILED && errno == EPERM);
    assert(fcntl(retained, F_GETFD) & FD_CLOEXEC);
    char magic[4]; assert(pread(retained, magic, sizeof(magic), 0) == 4 && !memcmp(magic, ELFMAG, 4));
    assert(!close(retained)); md_exec_image_release(image);
    puts("PASS executable admission: mutable/FUTURE_WRITE rejected, retained image kernel-sealed");
}
static void rejected_images(void) {
    struct md_exec_image *image = NULL;
    assert(md_exec_image_admit(-1, 0, 0, S_IFREG | 04755, &image) == -EBADF && !image);
    int fd = image_fd();
    assert(pwrite(fd, "#!", 2, 0) == 2);
    assert(!fcntl(fd, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK));
    assert(md_exec_image_admit(fd, 0, 0, S_IFREG | 04755, &image) == -ENOEXEC && !image);
    close(fd); fd = image_fd();
    assert(!fcntl(fd, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK));
    assert(md_exec_image_admit(fd, UINT32_MAX, 0, S_IFREG | 04755, &image) == -EINVAL && !image);
    assert(md_exec_image_admit(fd, 0, UINT32_MAX, S_IFREG | 04755, &image) == -EINVAL && !image);
    assert(md_exec_image_admit(fd, 0, 0, S_IFDIR | 04755, &image) == -EINVAL && !image);
    assert(md_exec_image_admit(fd, 0, 0, S_IFREG | 010000000, &image) == -EINVAL && !image);
    close(fd);
    puts("PASS image admission rejects invalid descriptor, script header, owner and mode");
}
static struct md_exec_image *admit(uint32_t uid, uint32_t gid, mode_t mode) {
    int fd = image_fd();
    assert(!fcntl(fd, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL));
    struct md_exec_image *image;
    assert(!md_exec_image_admit(fd, uid, gid, S_IFREG | mode, &image)); close(fd);
    return image;
}
static void transitions(void) {
    struct md_exec_image *suid = admit(0, 0, 04755), *plain = admit(0, 0, 0755);
    struct md_exec_image *sgid = admit(4000, 4001, 02755), *locked = admit(0, 0, 04700);
    struct md_exec_image *sgid_no_x = admit(4000, 4001, 02745), *no_x = admit(0, 0, 04644);
    struct md_identity original = md_identity_new(2000, 2000), current = original;
    struct md_exec_identity candidate;
    assert(!md_identity_may_chroot(&current));
    assert(md_identity_setresuid(&current, 0, 0, 0) == -EPERM);
    assert(md_identity_setresgid(&current, 0, 0, 0) == -EPERM);
    assert(!memcmp(&current, &original, sizeof(current)));
    assert(md_identity_prepare_exec(&current, NULL, 0, &candidate) == -EACCES);
    assert(md_identity_prepare_exec(&current, locked, 0, &candidate) == -EACCES);
    assert(!md_identity_prepare_exec(&current, suid, 0, &candidate));
    assert(candidate.secure && candidate.value.uid.real == 2000
        && candidate.value.uid.effective == 0 && candidate.value.uid.saved == 0 && candidate.value.uid.fs == 0);
    /* Discarding a prepared exec is the failed-exec path. No privilege changes. */
    assert(!memcmp(&current, &original, sizeof(current)));
    current = candidate.value;
    assert(md_identity_may_chroot(&current));
    assert(md_identity_prepare_exec(&current, no_x, 0, &candidate) == -EACCES);
    struct md_identity helper_child = current;
    assert(!md_identity_setresgid(&current, 2000, 2000, 2000));
    assert(!md_identity_setresuid(&current, 2000, 2000, 2000));
    assert(!md_identity_may_chroot(&current) && md_identity_may_chroot(&helper_child));
    assert(md_identity_setresuid(&current, UINT32_MAX, 0, UINT32_MAX) == -EPERM);
    assert(!md_identity_prepare_exec(&current, plain, 0, &candidate) && !candidate.secure);
    assert(!md_identity_prepare_exec(&current, suid, 1, &candidate) && !candidate.secure);
    assert(candidate.value.uid.effective == 2000);
    assert(!md_identity_no_new_privs(&current, 1));
    assert(md_identity_no_new_privs(&current, 0) == -EINVAL && current.no_new_privs);
    assert(!md_identity_prepare_exec(&current, suid, 0, &candidate) && !candidate.secure);
    assert(candidate.value.uid.effective == 2000 && candidate.value.no_new_privs);
    assert(!md_identity_prepare_exec(&original, sgid, 0, &candidate) && candidate.secure);
    assert(candidate.value.uid.effective == 2000 && candidate.value.gid.effective == 4001);
    assert(!md_identity_prepare_exec(&original, sgid_no_x, 0, &candidate) && !candidate.secure);
    assert(candidate.value.gid.effective == 2000);
    current = helper_child;
    assert(!md_identity_setresuid(&current, UINT32_MAX, 2000, UINT32_MAX));
    assert(!md_identity_may_chroot(&current) && current.uid.saved == 0);
    assert(!md_identity_setresuid(&current, UINT32_MAX, 0, UINT32_MAX));
    assert(md_identity_may_chroot(&current));
    assert(!md_identity_setresuid(&current, UINT32_MAX, 2000, UINT32_MAX));
    assert(!md_identity_prepare_exec(&current, plain, 0, &candidate));
    current = candidate.value;
    assert(current.uid.saved == 2000 && md_identity_setresuid(&current, UINT32_MAX, 0, UINT32_MAX) == -EPERM);
    md_exec_image_release(suid); md_exec_image_release(plain);
    md_exec_image_release(sgid); md_exec_image_release(locked);
    md_exec_image_release(sgid_no_x); md_exec_image_release(no_x);
    puts("PASS guest identity: set-ID admission, drop/reacquire, fork copy, failed exec, nosuid and no_new_privs");
}
static void native_comparison(void) {
    const uint32_t ids[] = {UINT32_MAX, 2000, 0, 4000};
    for (int group = 0; group < 2; group++) for (unsigned i = 0; i < 4; i++)
        for (unsigned j = 0; j < 4; j++) for (unsigned k = 0; k < 4; k++) {
            pid_t pid = fork(); assert(pid >= 0);
            if (!pid) {
                struct md_identity value = md_identity_new(2000, 2000);
                int expected = group ? md_identity_setresgid(&value, ids[i], ids[j], ids[k])
                    : md_identity_setresuid(&value, ids[i], ids[j], ids[k]);
                int actual = syscall(group ? SYS_setresgid : SYS_setresuid, ids[i], ids[j], ids[k]);
                assert(actual < 0 ? expected == -errno : expected == 0);
                uid_t r, e, s;
                assert(!syscall(group ? SYS_getresgid : SYS_getresuid, &r, &e, &s));
                const struct md_identity_ids *model = group ? &value.gid : &value.uid;
                assert(r == model->real && e == model->effective && s == model->saved);
                _exit(0);
            }
            int status;
            /* EVENT_WAIT: reap each native credential comparison; outer deadline bounds hangs. */
            assert(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && !WEXITSTATUS(status));
        }
    puts("PASS 128 unprivileged setresuid/setresgid cases match native UID-2000 kernel results");
}
int main(void) {
    assert(getuid() == 2000 && geteuid() == 2000 && getgid() == 2000 && getegid() == 2000);
    immutable_admission(); rejected_images(); transitions(); native_comparison();
    assert(getuid() == 2000 && geteuid() == 2000);
    puts("PASS external identity contract; browser integration and sandbox support NOT established");
    return 0;
}
