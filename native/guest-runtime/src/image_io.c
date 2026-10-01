#define _GNU_SOURCE
#include "image_io.h"
#include <mbedtls/sha256.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

int md_image_write(int fd, const void *buffer, size_t size) {
    const unsigned char *p = buffer;
    while (size) {
        ssize_t n = write(fd, p, size);
        if (n < 0) { if (errno == EINTR) continue; return -errno; }
        if (!n) return -EIO;
        p += n; size -= (size_t)n;
    }
    return 0;
}
int md_image_read(int fd, size_t limit, char **out) {
    *out = NULL;
    struct stat st;
    if (fstat(fd, &st)) return -errno;
    if (!S_ISREG(st.st_mode) || st.st_size < 0 || (uint64_t)st.st_size > limit) return -EFBIG;
    size_t size = (size_t)st.st_size;
    char *data = malloc(size + 1);
    if (!data) return -ENOMEM;
    for (size_t offset = 0; offset < size;) {
        ssize_t n = pread(fd, data + offset, size - offset, (off_t)offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { free(data); return n < 0 ? -errno : -ESTALE; }
        offset += (size_t)n;
    }
    if (memchr(data, 0, size)) { free(data); return -EBADMSG; }
    data[size] = 0; *out = data; return 0;
}
int md_image_digest_valid(const char *digest) {
    return digest && strlen(digest) == 71 && !memcmp(digest, "sha256:", 7)
        && strspn(digest + 7, "0123456789abcdef") == 64;
}
int md_image_hash(int fd, uint64_t size, char out[65]) {
    mbedtls_sha256_context hash;
    mbedtls_sha256_init(&hash);
    int r = mbedtls_sha256_starts(&hash, 0) ? -EIO : 0;
    unsigned char buffer[65536], digest[32];
    uint64_t offset = 0;
    while (!r && offset < size) {
        size_t take = size - offset > sizeof(buffer) ? sizeof(buffer) : (size_t)(size - offset);
        ssize_t n = pread(fd, buffer, take, (off_t)offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { r = n < 0 ? -errno : -ESTALE; break; }
        if (mbedtls_sha256_update(&hash, buffer, (size_t)n)) r = -EIO;
        offset += (uint64_t)n;
    }
    if (!r && mbedtls_sha256_finish(&hash, digest)) r = -EIO;
    mbedtls_sha256_free(&hash);
    if (!r) {
        for (unsigned i = 0; i < 32; ++i) {
            out[i * 2] = "0123456789abcdef"[digest[i] >> 4];
            out[i * 2 + 1] = "0123456789abcdef"[digest[i] & 15];
        }
        out[64] = 0;
    }
    return r;
}
int md_image_blob(int blobs, int scratch, const char *digest, uint64_t size, int *out) {
    *out = -1;
    if (!md_image_digest_valid(digest) || size > 8ULL * 1024 * 1024 * 1024) return -EINVAL;
    int fd = openat(blobs, digest + 7, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -errno;
    struct stat st;
    int r = fstat(fd, &st) ? -errno : 0;
    if (!r && (!S_ISREG(st.st_mode) || st.st_size < 0 || (uint64_t)st.st_size != size)) r = -EBADMSG;
    int snapshot = -1;
    if (!r && (snapshot = openat(scratch, ".", O_TMPFILE | O_RDWR | O_CLOEXEC, 0600)) < 0) r = -errno;
    unsigned char buffer[65536];
    for (uint64_t offset = 0; !r && offset < size;) {
        size_t take = size - offset > sizeof(buffer) ? sizeof(buffer) : (size_t)(size - offset);
        ssize_t n = pread(fd, buffer, take, (off_t)offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { r = n < 0 ? -errno : -ESTALE; break; }
        r = md_image_write(snapshot, buffer, (size_t)n);
        offset += (uint64_t)n;
    }
    close(fd);
    char hash[65];
    if (!r) r = md_image_hash(snapshot, size, hash);
    if (!r && strcmp(hash, digest + 7)) r = -EBADMSG;
    if (r && snapshot >= 0) close(snapshot);
    if (!r) *out = snapshot;
    return r;
}
