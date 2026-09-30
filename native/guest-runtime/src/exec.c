#define _GNU_SOURCE
#include "bootstrap.h"
#include "elf.h"
#include "namespace.h"
#include "file_calls.h"
#include "linux_abi.h"
#include "raw.h"
#include "socket_routes.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

int md_command_prepare(struct md_command *c, const char *program, char *const argv[],
        int input_fd, int inaccessible) {
    c->fd = -1;
    long r = md_read_string(c->path, sizeof(c->path), program);
    if (r < 0) return (int)r;
    c->argc = 0;
    for (; c->argc < MD_ARG_MAX; ++c->argc) {
        if (!argv) break;
        r = md_read_memory(&c->argv[c->argc], argv + c->argc, sizeof(char *));
        if (r < 0) return (int)r;
        if (!c->argv[c->argc]) break;
    }
    if (!c->argc) c->argv[c->argc++] = "";
    c->argv[c->argc] = NULL;
    if (c->argc == MD_ARG_MAX) return -E2BIG;
    for (unsigned depth = 0;; ++depth) {
        long fd;
        if (!depth && input_fd >= 0) {
            struct stat st;
            r = RAW2(fstat, input_fd, &st);
            if (r < 0) return (int)r;
            if (S_ISLNK(st.st_mode)) return -ELOOP;
            char reference[64] = "/proc/thread-self/fd/";
            md_decimal(reference + md_length(reference), (unsigned)input_fd);
            fd = RAW4(openat, AT_FDCWD, reference, O_RDONLY | O_CLOEXEC, 0);
            if (fd >= 0) {
                r = RAW4(faccessat2, fd, "", X_OK, AT_EMPTY_PATH | MD_AT_EACCESS);
                if (r < 0) { RAW1(close, fd); return (int)r; }
            }
        } else fd = md_program_open(&md_files, c->path, 1);
        if (fd < 0) return (int)fd;
        struct stat st;
        r = RAW2(fstat, fd, &st);
        if (!r && (!S_ISREG(st.st_mode) || (st.st_mode & (S_ISUID | S_ISGID)))) r = -EACCES;
        char header[256] = {0};
        if (!r) r = RAW3(read, fd, header, sizeof(header));
        if (r < 0) { RAW1(close, fd); return (int)r; }
        if (r < 2 || header[0] != '#' || header[1] != '!') {
            r = md_elf_interpreter((int)fd, c->interpreter);
            if (!r && c->interpreter[0]) {
                long loader = md_program_open(&md_files, c->interpreter, 1);
                if (loader < 0) r = loader;
                else { r = md_elf_validate((int)loader, 1); RAW1(close, loader); }
            }
            if (r < 0) RAW1(close, fd);
            else c->fd = (int)fd;
            return (int)r;
        }
        RAW1(close, fd);
        if (!depth && inaccessible) return -ENOENT;
        if (depth == 4) return -ELOOP;
        md_copy(c->scripts[depth], PATH_MAX, c->path);
        memcpy(c->lines[depth], header, sizeof(header));
        char *line = c->lines[depth] + 2;
        char *limit = c->lines[depth] + sizeof(header), *end = line;
        while (end < limit && *end != '\n') ++end;
        if (end == limit) {
            // Linux permits a missing newline and a truncated option, never a
            // truncated interpreter name. A short read supplies a NUL terminator.
            char *name = line;
            while (name < limit && (*name == ' ' || *name == '\t')) ++name;
            while (name < limit && *name && *name != ' ' && *name != '\t') ++name;
            if (name == limit) return -ENOEXEC;
            end = limit - 1;
        }
        *end = 0;
        while (end > line && (end[-1] == ' ' || end[-1] == '\t')) *--end = 0;
        while (*line == ' ' || *line == '\t') ++line;
        char *option = line;
        while (*option && *option != ' ' && *option != '\t') ++option;
        if (*option) *option++ = 0;
        while (*option == ' ' || *option == '\t') ++option;
        if (!*line) return -ENOEXEC;
        unsigned shift = *option ? 2 : 1;
        memmove(c->argv + 1 + shift, c->argv + 1, c->argc * sizeof(char *));
        c->argv[0] = line;
        if (*option) c->argv[1] = option;
        c->argv[shift] = c->scripts[depth];
        c->argc += shift;
        md_copy(c->path, sizeof(c->path), line);
    }
}
long md_guest_exec(const char *program, char *const argv[], char *const env[]) {
    return md_guest_execat(AT_FDCWD, program, argv, env, 0);
}
long md_guest_execat(int base, const char *program, char *const argv[], char *const env[], int flags) {
    if (flags & ~(AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW)) return -EINVAL;
    char path[PATH_MAX], identity[PATH_MAX];
    long r = md_read_string(path, sizeof(path), program);
    if (r < 0) return r;
    if (!*path && !(flags & AT_EMPTY_PATH)) return -ENOENT;
    int fd, inaccessible = 0;
    if (!*path || (*path != '/' && base != AT_FDCWD)) {
        long descriptor_flags = RAW2(fcntl, base, F_GETFD);
        if (descriptor_flags < 0) return descriptor_flags;
        inaccessible = !!(descriptor_flags & FD_CLOEXEC);
        md_copy(identity, sizeof(identity), "/dev/fd/");
        md_decimal(identity + md_length(identity), (unsigned)base);
        if (*path) {
            r = md_append(identity, sizeof(identity), "/");
            if (!r) r = md_append(identity, sizeof(identity), path);
            if (r < 0) return r;
        }
    }
    if (!*path) fd = base;
    else {
        unsigned long args[6] = {(unsigned long)base, (unsigned long)path,
            O_RDONLY | O_CLOEXEC | ((flags & AT_SYMLINK_NOFOLLOW) ? O_NOFOLLOW : 0)};
        r = md_file_call(&md_files, md_executable, SYS_openat, args);
        if (r < 0) return r;
        fd = (int)r;
    }
    if (*path && (*path == '/' || base == AT_FDCWD)) {
        // Resolve identity after opening: NOFOLLOW must also reject dangling links.
        r = md_program_identity(&md_files, path, identity);
        if (r < 0) { RAW1(close, fd); return r; }
    }
    struct md_command c;
    r = md_command_prepare(&c, identity, argv, fd, inaccessible);
    if (*path) RAW1(close, fd);
    if (r < 0) return r;
    /* One inherited descriptor pins the validated ELF across bootstrap exec.
     * The new bootstrap closes it before guest entry; failed exec closes it here. */
    r = RAW3(fcntl, c.fd, F_SETFD, 0);
    if (r < 0) { RAW1(close, c.fd); return r; }
    char number[24]; md_decimal(number, (unsigned)c.fd);
    char *next[MD_ARG_MAX + 24 + MD_SOCKET_ROUTES_MAX * 3];
    next[0] = md_bootstrap;
    next[1] = "--resume";
    unsigned n=2;
    next[n++] = "--program-fd";
    next[n++] = number;
    // AT_EXECFN is the caller's spelling, not the canonical executable or the
    // final shebang interpreter. Linux uses /dev/fd/N for descriptor exec.
    next[n++] = "--execfn";
    next[n++] = *path && (*path == '/' || base == AT_FDCWD) ? path : identity;
    for (unsigned i = 0; i < md_connections.count; i++) {
        struct md_socket_route *route = &md_connections.entries[i];
        next[n++] = route->abstract ? "--socket-abstract" : "--socket-path";
        next[n++] = route->source;
        next[n++] = route->destination;
    }
    if(md_files.endpoint[0]) { next[n++]="--namespace"; next[n++]=md_files.endpoint; }
    else next[n++]=md_files.root;
    next[n++]=c.path;
    for (unsigned i = 0; i <= c.argc; ++i) next[n+i] = c.argv[i];
    r = RAW3(execve, md_bootstrap, next, env);
    RAW1(close, c.fd);
    return r;
}
