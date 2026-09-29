#ifndef MD_BOOTSTRAP_H
#define MD_BOOTSTRAP_H
#include "fs.h"
#define MD_ARG_MAX 1024
extern struct md_fs md_files;
extern char md_bootstrap[PATH_MAX];
extern char md_executable[PATH_MAX];
int md_install_trap(int inherited);
long md_guest_exec(const char *, char *const [], char *const []);
// Storage remains live until the loader takes ownership of argv strings.
struct md_command {
    char path[PATH_MAX];
    char scripts[4][PATH_MAX];
    char lines[4][256];
    char *argv[MD_ARG_MAX + 16];
    unsigned argc;
};
int md_command_prepare(struct md_command *, const char *, char *const []);
#endif
