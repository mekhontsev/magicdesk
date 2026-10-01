#ifndef MD_FILE_CALLS_H
#define MD_FILE_CALLS_H
#include "fs.h"

// One catalog feeds both dispatch and the seccomp filter.
#define MD_FILE_CALLS(X) \
    X(openat) X(openat2) X(newfstatat) X(statx) X(faccessat) X(faccessat2) X(fstat) X(getdents64) X(lseek) \
    X(mkdirat) X(unlinkat) X(symlinkat) X(renameat) X(renameat2) X(linkat) \
    X(chdir) X(fchdir) X(getcwd) X(readlinkat) X(fchmodat) X(fchmodat2) X(fchownat) X(utimensat) \
    X(truncate) X(statfs) X(mknodat) X(inotify_add_watch) \
    X(setxattr) X(lsetxattr) X(getxattr) X(lgetxattr) X(listxattr) X(llistxattr) \
    X(removexattr) X(lremovexattr) X(fsetxattr) X(fremovexattr) X(fchmod) X(fchown)

long md_file_call(const struct md_fs *, const char *, long, const unsigned long *);
#endif
