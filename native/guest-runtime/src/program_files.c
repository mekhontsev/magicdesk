#define _GNU_SOURCE
#include "program_files.h"
#include "namespace_internal.h"
#include "proc_paths.h"
#include "file_calls.h"
#include "raw.h"
#include "linux_abi.h"
#include <fcntl.h>
#include <errno.h>
#include <unistd.h>

long md_program_open(const struct md_fs *fs, const char *path, int executable) {
    long fd;
    if (fs->endpoint[0])
        fd = md_namespace_open(fs, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
    else {
        char host[PATH_MAX];
        long r = md_fs_resolve(fs, AT_FDCWD, path, MD_PATH_FOLLOW, host);
        if (r < 0)
            return r;
        fd = RAW4(openat, AT_FDCWD, host, O_RDONLY | O_CLOEXEC, 0);
    }
    if (fd < 0)
        return fd;
    if (executable) {
        long r = RAW4(faccessat2, fd, "", X_OK, AT_EMPTY_PATH | MD_AT_EACCESS);
        if (r < 0) {
            RAW1(close, fd);
            return r;
        }
    }
    return fd;
}
static long program_identity(const struct md_fs *fs, int base, const char *path, char *out) {
    if (fs->endpoint[0])
        return md_copy(out, PATH_MAX, path);
    char host[PATH_MAX];
    long r = md_fs_resolve(fs, base, path, MD_PATH_FOLLOW, host);
    return r < 0 ? r : md_fs_guest(fs, host, out);
}
static long object_identity(const struct md_fs *fs, struct md_program *program) {
    memset(program->identity.object, 0, sizeof(program->identity.object));
    if (!fs->endpoint[0]) return 0;
    struct md_fs_request q = {.operation = MD_FS_OBJECT_ID, .directory = {program->fd, -1}};
    struct md_fs_response out;
    long r = md_fs_call(fs->endpoint, 5000, &q, &out);
    if (!r) r = out.result.error;
    if (!r) memcpy(program->identity.object, out.data, sizeof(program->identity.object));
    return r;
}
long md_program_capture(const struct md_fs *fs, int fd, const char *path, struct md_program *program) {
    program->fd = -1;
    long r = md_copy(program->identity.path, sizeof(program->identity.path), path);
    if (r < 0) return r;
    r = RAW3(fcntl, fd, F_DUPFD_CLOEXEC, 0);
    if (r >= 0) {
        program->fd = (int)r;
        r = object_identity(fs, program);
        if (r < 0) { RAW1(close, program->fd); program->fd = -1; }
    }
    return r;
}
long md_program_acquire(const struct md_fs *fs, int base, const char *path, int flags,
        struct md_program *program) {
    program->fd = -1;
    memset(program->identity.object, 0, sizeof(program->identity.object));
    char routed[PATH_MAX];
    if (fs->endpoint[0]) {
        long r = md_copy(routed, sizeof(routed), path);
        if (!r) r = md_namespace_relative_mount(base, routed);
        if (r < 0) return r;
        path = routed;
    }
    if (fs->endpoint[0] && !md_host_path(path)) {
        struct md_fs_request q = {.operation = MD_FS_OPEN_IMAGE, .directory = {base, -1},
            .path = {path, NULL}, .flags = (unsigned)flags};
        struct md_fs_response out;
        long r = md_namespace_request(fs, &q, &out);
        if (r < 0) return r;
        memcpy(&program->identity, out.data, out.result.size);
        program->fd = out.result.fd;
        return 0;
    }
    unsigned long args[6] = {(unsigned long)base, (unsigned long)path, O_RDONLY | O_CLOEXEC | flags};
    const char *exe = fs->image && fs->image->executable_path ? fs->image->executable_path : "";
    long r = md_file_call(fs, exe, SYS_openat, args);
    if (r < 0) return r;
    program->fd = (int)r;
    struct md_proc_path ref = md_proc_path(path);
    r = ref.kind == MD_PROC_EXE && !*ref.tail && *exe
        ? md_copy(program->identity.path, sizeof(program->identity.path), exe)
        : program_identity(fs, base, path, program->identity.path);
    if (!r) r = object_identity(fs, program);
    if (r < 0) { RAW1(close, program->fd); program->fd = -1; }
    return r;
}
