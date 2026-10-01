#ifndef MD_BOOTSTRAP_H
#define MD_BOOTSTRAP_H
#include "fs.h"
#include "elf.h"
#include "program_files.h"
#define MD_ARG_MAX 1024
extern struct md_fs md_files;
extern char md_bootstrap[PATH_MAX];
extern char md_executable[PATH_MAX];
long md_guest_exec(const char *, char *const [], char *const []);
long md_guest_execat(int, const char *, char *const [], char *const [], int);
// Storage remains live until the loader takes ownership of argv strings.
struct md_command {
    char path[PATH_MAX];
    char object[33];
    char interpreter[MD_INTERPRETER_MAX];
    char scripts[4][PATH_MAX];
    char lines[4][256];
    char *argv[MD_ARG_MAX + 16];
    unsigned argc;
    int fd, interpreter_fd;
};
/* Borrows input (NULL selects the path); success owns both prepared images. */
int md_command_prepare(const struct md_fs *, struct md_command *, const char *, char *const [],
    const struct md_program *input, int inaccessible);
void md_command_close(struct md_command *);
#endif
