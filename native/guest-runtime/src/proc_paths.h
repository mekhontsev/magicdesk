#ifndef MD_PROC_PATHS_H
#define MD_PROC_PATHS_H
#include <stddef.h>

enum md_proc_kind { MD_PROC_NONE, MD_PROC_FD, MD_PROC_CWD, MD_PROC_ROOT, MD_PROC_EXE,
                    MD_PROC_CMDLINE, MD_PROC_AUXV, MD_PROC_FOREIGN };
struct md_proc_path {
    enum md_proc_kind kind;
    size_t anchor_length;
    const char *tail;
    int ordinary_link;
};
int md_host_path(const char *);
/* Classifies adapter-owned absolute strings, without resolving guest symlinks. */
struct md_proc_path md_proc_path(const char *);
void md_proc_image(unsigned argc, char *const *argv, const void *auxv, size_t auxv_bytes);
struct md_fs;
long md_proc_executable_init(const struct md_fs *, int fd);
long md_proc_executable_open(const struct md_fs *, const char *path, int flags);
long md_proc_image_open(const struct md_fs *, enum md_proc_kind, int flags);
#endif
