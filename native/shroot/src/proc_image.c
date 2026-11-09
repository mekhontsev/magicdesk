#define _GNU_SOURCE
#include "proc_paths.h"
#include "fs.h"
#include "fs_rpc.h"
#include "fs_buffer.h"
#include "namespace.h"
#include "interception.h"
#include "proc_control.h"
#include "native_identity.h"
#include "raw.h"
#include "arguments.h"
#include "process_arguments.h"
#include "namespace_internal.h"
#include <errno.h>
#include <fcntl.h>

long md_proc_executable_capture(const struct md_fs *fs,int process,int flags) {
    if (!(flags&O_PATH) && ((flags&O_ACCMODE)!=O_RDONLY || (flags&O_TRUNC))) return -ETXTBSY;
    if ((flags&(O_CREAT|O_EXCL))==(O_CREAT|O_EXCL)) return -EEXIST;
    long fd=md_proc_control_open(&(struct md_proc_path){.process=process,.kind=MD_PROC_EXE},flags,0);
    if (fd!=-EXDEV) return fd;
    char selected[64]="/proc/";
    md_decimal(selected+md_length(selected),(unsigned)process);
    md_append(selected,sizeof(selected),"/exe");
    struct md_fs_request q={.operation=MD_FS_REOPEN,.directory={-1,-1},
        .capture={{selected,O_PATH}},.flags=(unsigned)flags&~(O_CREAT|O_EXCL|O_NOFOLLOW)};
    struct md_fs_response out;
    long error=md_namespace_request(fs,&q,&out);
    return error<0 ? error : md_namespace_open_result(&out.result,q.flags);
}
long md_proc_executable_open(const struct md_fs *fs, const char *path, int flags) {
    if (fs->image && fs->endpoint[0])
        return md_proc_executable_capture(fs,md_native_tid(),flags);
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
void md_proc_image_init(struct md_process_image *image, const void *arguments,
        size_t argument_bytes, size_t environment_bytes, const void *auxv, size_t bytes) {
    if (argument_bytes>MD_EXEC_BYTES || environment_bytes>MD_EXEC_BYTES || bytes>sizeof(image->auxv))
        md_die("process image bounds", -E2BIG);
    image->arguments=arguments; image->argument_bytes=argument_bytes; image->environment_bytes=environment_bytes;
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
struct image_snapshot {
    const struct md_process_image *image;
    enum md_proc_kind kind;
    size_t offset;
};
static long read_arguments(void *context,uintptr_t address,void *out,size_t size) {
    (void)context;
    long error=md_read_memory(out,(const void *)address,size);
    return error<0 ? error : (long)size;
}
static long snapshot_bytes(void *context, void *out, size_t capacity) {
    struct image_snapshot *s=context;
    const struct md_process_image *image=s->image;
    if (s->kind==MD_PROC_CMDLINE) {
        long n=md_process_arguments_read(image,md_page_size,read_arguments,NULL,out,capacity,s->offset);
        if (n>0) s->offset+=(size_t)n;
        return n;
    }
    const void *data=image->auxv;
    size_t size=image->auxv_bytes;
    size_t n=size-s->offset;
    if (n>capacity) n=capacity;
    if (!n) return 0;
    long error=md_read_memory(out,(const char *)data+s->offset,n);
    if (error<0) return error;
    s->offset+=n;
    return (long)n;
}
long md_proc_image_open(const struct md_fs *fs, enum md_proc_kind kind, int flags) {
    const struct md_process_image *image = fs->image;
    if (!image || !image->auxv_bytes) return -ENOTSUP;
    if (flags & O_DIRECTORY) return -ENOTDIR;
    if ((flags & O_ACCMODE) != O_RDONLY || (flags & O_TRUNC)) return -EACCES;
    if ((flags & (O_CREAT | O_EXCL)) == (O_CREAT | O_EXCL)) return -EEXIST;
    struct image_snapshot snapshot={.image=image,.kind=kind};
    if (fs->endpoint[0]) {
#ifndef MD_USE_LIBC
        return md_fs_buffer_snapshot((unsigned)(flags&~(O_CREAT|O_EXCL|O_NOFOLLOW)),snapshot_bytes,&snapshot);
#else
        return -ENOTSUP;
#endif
    }
    long fd = RAW4(openat, AT_FDCWD, fs->root, O_TMPFILE | O_RDWR | O_CLOEXEC, 0600);
    if (fd < 0) return fd;
    long r = 0;
    char bytes[4096];
    while (!r) {
        long size=snapshot_bytes(&snapshot,bytes,sizeof(bytes));
        if (size<=0) { r=size; break; }
        r=append_bytes((int)fd,bytes,(size_t)size);
    }
    if (!r) {
        char path[64] = "/proc/thread-self/fd/";
        md_decimal(path + md_length(path), (unsigned)fd);
        r = RAW4(openat, AT_FDCWD, path, flags & ~(O_CREAT | O_EXCL | O_NOFOLLOW), 0);
    }
    RAW1(close, fd);
    return r;
}
