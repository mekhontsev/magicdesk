#ifndef MD_GUEST_USER_H
#define MD_GUEST_USER_H
#include <stdint.h>
/* Numeric launch identity; name resolution belongs to the selected image. */
static inline int md_guest_user_parse(const char *s, uint32_t *uid, uint32_t *gid) {
    uint32_t ids[2];
    for (unsigned i = 0; i < 2; i++) {
        uint64_t value = 0;
        if (*s < '0' || *s > '9') return -1;
        do { value = value * 10 + (unsigned)(*s++ - '0'); if (value >= UINT32_MAX) return -1; }
        while (*s >= '0' && *s <= '9');
        ids[i] = (uint32_t)value;
        if (!i && *s++ != ':') return -1;
    }
    if (*s) return -1;
    *uid = ids[0]; *gid = ids[1]; return 0;
}
#endif
