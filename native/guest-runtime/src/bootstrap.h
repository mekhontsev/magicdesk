#ifndef MD_BOOTSTRAP_H
#define MD_BOOTSTRAP_H
#include "fs.h"
#include "elf.h"
#define MD_ARG_MAX 1024
extern struct md_fs md_files;
extern char md_bootstrap[PATH_MAX];
extern char md_executable[PATH_MAX];
long md_guest_exec(const char *, char *const [], char *const []);
long md_guest_execat(int, const char *, char *const [], char *const [], int);
// Storage remains live until the loader takes ownership of argv strings.
struct md_command {
    char path[PATH_MAX];
    char interpreter[MD_INTERPRETER_MAX];
    char scripts[4][PATH_MAX];
    char lines[4][256];
    char *argv[MD_ARG_MAX + 16];
    unsigned argc;
    int fd;
};
/* Borrows input_fd (-1 selects the path); success transfers an opened ELF fd. */
int md_command_prepare(const struct md_fs *, struct md_command *, const char *, char *const [],
    int input_fd, int inaccessible);
#endif
