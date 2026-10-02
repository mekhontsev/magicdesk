#ifndef MD_PROC_PATHS_H
#define MD_PROC_PATHS_H
#include <stddef.h>
#include <stdint.h>

enum md_proc_kind { MD_PROC_NONE, MD_PROC_FD, MD_PROC_CWD, MD_PROC_ROOT, MD_PROC_EXE,
                    MD_PROC_CMDLINE, MD_PROC_AUXV, MD_PROC_FOREIGN, MD_PROC_MOUNTS, MD_PROC_MOUNTINFO };
struct md_proc_path {
    enum md_proc_kind kind;
    size_t anchor_length;
    const char *tail;
    int ordinary_link;
    int process;
    enum md_proc_kind foreign_kind;
};
int md_host_path(const char *);
/* Classifies adapter-owned absolute strings, without resolving guest symlinks. */
struct md_proc_path md_proc_path(const char *);
/* An image belongs to one address space, not to the syscall transport. */
struct md_process_image {
    unsigned argc;
    uintptr_t argv[1040];
    size_t arg_lengths[1040];
    unsigned char auxv[4096];
    size_t auxv_bytes;
    char executable_object[33];
    const char *executable_path;
};
void md_proc_image_init(struct md_process_image *, unsigned argc, char *const *argv,
        const void *auxv, size_t auxv_bytes);
struct md_fs;
long md_proc_executable_open(const struct md_fs *, const char *path, int flags);
long md_proc_image_open(const struct md_fs *, enum md_proc_kind, int flags);
#endif
