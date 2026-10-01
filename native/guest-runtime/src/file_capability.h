#ifndef MD_FILE_CAPABILITY_H
#define MD_FILE_CAPABILITY_H
#include <stddef.h>
#include <stdint.h>
#define MD_FILE_CAPABILITY_NAME "security.capability"
#define MD_FILE_CAPABILITY_MAX 24
/* Linux VFS revisions are little-endian, independently of the importer host. */
static inline int md_file_capability_valid(const void *value, size_t size) {
    if (!value || size < 4) return 0;
    const unsigned char *p = value;
    uint32_t magic = (uint32_t)p[0] | (uint32_t)p[1]<<8 | (uint32_t)p[2]<<16 | (uint32_t)p[3]<<24;
    unsigned revision = magic >> 24;
    return !(magic & 0x00fffffeU) && ((revision == 1 && size == 12)
        || (revision == 2 && size == 20) || (revision == 3 && size == 24));
}
#endif
