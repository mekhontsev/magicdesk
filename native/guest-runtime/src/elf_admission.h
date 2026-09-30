#ifndef MD_GUEST_ADMISSION_H
#define MD_GUEST_ADMISSION_H
#include "guest_identity.h"
#include <sys/stat.h>

/* One explicit, trusted launch admission. No guest may
 * choose this record's logical owner or mode. */
struct md_admission {
    int source;
    struct stat source_stat;
    struct md_exec_image *image;
    char object[33];
};
int md_admission_open(struct md_admission *, const char *endpoint, const char *guest_path);
/* Takes ownership of source, including on failure. */
int md_admission_snapshot(struct md_admission *, int source);
int md_admission_match(const struct md_admission *, const char *endpoint, int fd);
void md_admission_close(struct md_admission *);
#endif
