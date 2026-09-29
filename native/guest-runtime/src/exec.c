#define _GNU_SOURCE
#include "bootstrap.h"
#include "elf.h"
#include "namespace.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/mman.h>

int md_command_prepare(struct md_command *c, const char *program, char *const argv[]) {
    long r = md_read_string(c->path, sizeof(c->path), program);
    if (r < 0) return (int)r;
    c->argc = 0;
    for (; c->argc < MD_ARG_MAX; ++c->argc) {
        r = md_read_memory(&c->argv[c->argc], argv + c->argc, sizeof(char *));
        if (r < 0) return (int)r;
        if (!c->argv[c->argc]) break;
    }
    if (!c->argc) return -EINVAL;
    if (c->argc == MD_ARG_MAX) return -E2BIG;
    for (unsigned depth = 0;; ++depth) {
        long fd = md_program_open(&md_files, c->path, 1);
        if (fd < 0) return (int)fd;
        struct stat st;
        r = RAW2(fstat, fd, &st);
        if (!r && (!S_ISREG(st.st_mode) || (st.st_mode & (S_ISUID | S_ISGID)))) r = -EACCES;
        char header[256] = {0};
        if (!r) r = RAW3(read, fd, header, sizeof(header) - 1);
        if (r < 0) { RAW1(close, fd); return (int)r; }
        if (r < 2 || header[0] != '#' || header[1] != '!') {
            r = md_elf_check((int)fd, 0);
            RAW1(close, fd);
            return (int)r;
        }
        RAW1(close, fd);
        if (depth == 4) return -ELOOP;
        md_copy(c->scripts[depth], PATH_MAX, c->path);
        md_copy(c->lines[depth], sizeof(header), header);
        char *line = c->lines[depth] + 2, *end = line;
        while (*end && *end != '\n') ++end;
        if (!*end) return -ENOEXEC;
        *end = 0;
        while (end > line && (end[-1] == ' ' || end[-1] == '\t')) *--end = 0;
        while (*line == ' ' || *line == '\t') ++line;
        char *option = line;
        while (*option && *option != ' ' && *option != '\t') ++option;
        if (*option) *option++ = 0;
        while (*option == ' ' || *option == '\t') ++option;
        if (*line != '/') return -ENOEXEC;
        unsigned shift = *option ? 2 : 1;
        memmove(c->argv + 1 + shift, c->argv + 1, c->argc * sizeof(char *));
        c->argv[0] = line;
        if (*option) c->argv[1] = option;
        c->argv[shift] = c->scripts[depth];
        c->argc += shift;
        md_copy(c->path, sizeof(c->path), line);
    }
}
struct exec_args { const char *program; char *const *argv; char *const *env; };
static long execute(void *argument) {
    struct exec_args *args = argument;
    struct md_command c;
    int r = md_command_prepare(&c, args->program, args->argv);
    if (r < 0) return r;
    char *next[MD_ARG_MAX + 24];
    next[0] = md_bootstrap;
    next[1] = "--resume";
    unsigned n=2;
    if(md_files.endpoint[0]) { next[n++]="--namespace"; next[n++]=md_files.endpoint; }
    else next[n++]=md_files.root;
    next[n++]=c.path;
    for (unsigned i = 0; i <= c.argc; ++i) next[n+i] = c.argv[i];
    return RAW3(execve, md_bootstrap, next, args->env);
}
long md_guest_exec(const char *program, char *const argv[], char *const env[]) {
    // Only exec preparation allocates scratch space: posix_spawn's child stack is small.
    // Kernel exec discards it on success; every failure releases the exact mapping.
    const size_t size = 256 * 1024;
    long memory = RAW6(mmap, 0, size + 2 * md_page_size, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (memory < 0) return memory;
    long r = RAW3(mprotect, memory + md_page_size, size, PROT_READ | PROT_WRITE);
    if (!r) {
        struct exec_args args = {program, argv, env};
        r = md_on_stack(execute, &args, (uintptr_t)memory + md_page_size + size);
    }
    RAW2(munmap, memory, size + 2 * md_page_size);
    return r;
}
