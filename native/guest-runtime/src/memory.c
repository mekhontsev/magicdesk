#define _GNU_SOURCE
#include "raw.h"
#include <errno.h>
#include <sys/uio.h>

long md_read_memory(void *out, const void *in, size_t n) {
    struct iovec local = {out, n}, remote = {(void *)in, n};
    long r = RAW6(process_vm_readv, RAW0(getpid), &local, 1, &remote, 1, 0);
    return r == (long)n ? 0 : r < 0 ? r : -EFAULT;
}
long md_write_memory(void *out, const void *in, size_t n) {
    struct iovec local = {(void *)in, n}, remote = {out, n};
    long r = RAW6(process_vm_writev, RAW0(getpid), &local, 1, &remote, 1, 0);
    return r == (long)n ? 0 : r < 0 ? r : -EFAULT;
}
