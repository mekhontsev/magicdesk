#define _GNU_SOURCE
#include "proc_paths.h"
#include "fs.h"
#include "fs_rpc.h"
#include "namespace.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>

long md_proc_executable_open(const struct md_fs *fs, const char *path, int flags) {
    if (!fs->image || !fs->image->executable_object[0])
        return md_namespace_open(fs, AT_FDCWD, path, flags, 0);
    if (!(flags & O_PATH) && ((flags & O_ACCMODE) != O_RDONLY || (flags & O_TRUNC))) return -ETXTBSY;
    if ((flags & (O_CREAT | O_EXCL)) == (O_CREAT | O_EXCL)) return -EEXIST;
    struct md_fs_request q = {.operation = MD_FS_OPEN_OBJECT, .directory = {-1, -1},
        .path = {fs->image->executable_object, NULL}, .flags = (unsigned)(flags & ~(O_CREAT | O_EXCL | O_NOFOLLOW))};
    struct md_fs_response result;
    long r = md_fs_call(fs->endpoint, 5000, &q, &result);
    if (r < 0) return r;
    if (result.result.error) return result.result.error;
    if (!(flags & O_CLOEXEC)) {
        r = RAW3(fcntl, result.result.fd, F_SETFD, 0);
        if (r < 0) { RAW1(close, result.result.fd); return r; }
    }
    return result.result.fd;
}
void md_proc_image_init(struct md_process_image *image, unsigned argc,
        char *const *argv, const void *auxv, size_t bytes) {
    if (argc > sizeof(image->arg_lengths) / sizeof(image->arg_lengths[0]) || bytes > sizeof(image->auxv))
        md_die("process image bounds", -E2BIG);
    image->argc = argc;
    for (unsigned i = 0; i < argc; ++i) {
        image->argv[i] = (uintptr_t)argv[i];
        image->arg_lengths[i] = md_length(argv[i]) + 1;
    }
    memcpy(image->auxv, auxv, bytes); image->auxv_bytes = bytes;
}
static long append_bytes(int fd, const void *data, size_t size) {
    while (size) {
        long n = RAW3(write, fd, data, size);
        if (n == -EINTR) continue;
        if (n <= 0) return n ? n : -EIO;
        data = (const char *)data + n; size -= (size_t)n;
    }
    return 0;
}
long md_proc_image_open(const struct md_fs *fs, enum md_proc_kind kind, int flags) {
    const struct md_process_image *image = fs->image;
    if (!image || !image->auxv_bytes) return -ENOTSUP;
    if (flags & O_DIRECTORY) return -ENOTDIR;
    if ((flags & O_ACCMODE) != O_RDONLY || (flags & O_TRUNC)) return -EACCES;
    if ((flags & (O_CREAT | O_EXCL)) == (O_CREAT | O_EXCL)) return -EEXIST;
    long fd;
    if (fs->endpoint[0]) {
        struct md_fs_request q = {.operation = MD_FS_TEMPORARY, .directory = {-1, -1}};
        struct md_fs_response out;
        long r = md_fs_call(fs->endpoint, 5000, &q, &out);
        if (r < 0) return r;
        if (out.result.error) return out.result.error;
        fd = out.result.fd;
    } else fd = RAW4(openat, AT_FDCWD, fs->root, O_TMPFILE | O_RDWR | O_CLOEXEC, 0600);
    if (fd < 0) return fd;
    long r = 0;
    if (kind == MD_PROC_AUXV) r = append_bytes((int)fd, image->auxv, image->auxv_bytes);
    else {
        /* Snapshot on open, with native read/seek afterwards. No permanent FD,
         * per-read interception or background process polling is required. */
        for (unsigned i = 0; !r && i < image->argc; ++i) {
            r = append_bytes((int)fd, (const void *)image->argv[i], image->arg_lengths[i]);
        }
    }
    if (!r) {
        char path[64] = "/proc/thread-self/fd/";
        md_decimal(path + md_length(path), (unsigned)fd);
        r = RAW4(openat, AT_FDCWD, path, flags & ~(O_CREAT | O_EXCL | O_NOFOLLOW), 0);
    }
    RAW1(close, fd);
    return r;
}
