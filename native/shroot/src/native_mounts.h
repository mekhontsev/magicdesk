#ifndef MD_NATIVE_MOUNTS_H
#define MD_NATIVE_MOUNTS_H
#include <stddef.h>
#include <sys/types.h>

struct md_native_mounts;
/* Worker-owned snapshot of the native /proc and /dev mounts, not guest policy.
 * Returned bytes are borrowed until the next read or release. */
int md_native_mounts_read(struct md_native_mounts **, const char **, size_t *);
/* Type from the last captured table; no IO, host paths or source names. */
const char *md_native_mounts_type(const struct md_native_mounts *, dev_t);
void md_native_mounts_release(struct md_native_mounts *);
#endif
