#define _GNU_SOURCE
#include "raw.h"
#include <errno.h>
#include <sys/uio.h>

size_t md_page_size;
size_t md_length(const char *s) { size_t n = 0; while (s[n]) ++n; return n; }
#ifndef MD_USE_LIBC
void *memcpy(void *out, const void *in, size_t n) {
    unsigned char *d = out; const unsigned char *s = in;
    for (size_t i = 0; i < n; ++i) d[i] = s[i];
    return out;
}
void *memset(void *out, int value, size_t n) {
    unsigned char *d = out;
    for (size_t i = 0; i < n; ++i) d[i] = (unsigned char)value;
    return out;
}
void *memmove(void *out, const void *in, size_t n) {
    unsigned char *d = out; const unsigned char *s = in;
    if ((uintptr_t)d <= (uintptr_t)s) return memcpy(out, in, n);
    while (n) { --n; d[n] = s[n]; }
    return out;
}
#endif
int md_equal(const char *a, const char *b) {
    size_t i = 0; while (a[i] && a[i] == b[i]) ++i;
    return a[i] == b[i];
}
int md_prefix(const char *a, const char *b) {
    size_t i = 0; while (b[i] && a[i] == b[i]) ++i;
    return !b[i];
}
int md_copy(char *out, size_t size, const char *in) {
    size_t n = md_length(in);
    if (n >= size) return -ENAMETOOLONG;
    memcpy(out, in, n + 1);
    return 0;
}
int md_append(char *out, size_t size, const char *in) {
    size_t n = md_length(out);
    return md_copy(out + n, size - n, in);
}
void md_decimal(char *out, unsigned long value) {
    char digits[32]; size_t n = 0;
    do { digits[n++] = (char)('0' + value % 10); value /= 10; } while (value);
    size_t i = 0; while (n) out[i++] = digits[--n];
    out[i] = 0;
}
void md_error(const char *reason, long error) {
    char code[32]; md_decimal(code, (unsigned long)(error < 0 ? -error : error));
    RAW3(write, 2, "md-bootstrap: ", 14);
    RAW3(write, 2, reason, md_length(reason));
    RAW3(write, 2, " errno=", 7);
    RAW3(write, 2, code, md_length(code));
    RAW3(write, 2, "\n", 1);
}
void md_die(const char *reason, long error) {
    md_error(reason, error);
    RAW1(exit_group, error == -ENOENT ? 127 : 126);
    __builtin_unreachable();
}
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
long md_read_string(char *out, size_t capacity, const char *in) {
    if (!in) return -EFAULT;
    size_t offset = 0;
    while (offset < capacity) {
        uintptr_t address = (uintptr_t)in + offset;
        if (address < (uintptr_t)in) return -EFAULT;
        size_t n = md_page_size - address % md_page_size;
        if (n > capacity - offset) n = capacity - offset;
        long r = md_read_memory(out + offset, (const void *)address, n);
        if (r < 0) return r;
        for (size_t i = 0; i < n; ++i) if (!out[offset + i]) return 0;
        offset += n;
    }
    return -ENAMETOOLONG;
}
