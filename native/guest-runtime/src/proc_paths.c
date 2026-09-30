#define _GNU_SOURCE
#include "proc_paths.h"
#include "raw.h"
#include <limits.h>

static const char *component(const char *p) {
    for (;;) {
        while (*p == '/') ++p;
        if (p[0] != '.' || (p[1] && p[1] != '/')) return p;
        ++p;
    }
}
static int word(const char **p, const char *name) {
    const char *s = component(*p);
    size_t n = md_length(name);
    if (!md_prefix(s, name) || (s[n] && s[n] != '/')) return 0;
    *p = s + n;
    return 1;
}
int md_host_path(const char *p) {
    if (*p != '/') return 0;
    if (word(&p, "dev")) return !word(&p, "shm");
    return word(&p, "proc");
}
static long number(const char **p) {
    const char *s = component(*p);
    if (*s < '0' || *s > '9') return -1;
    unsigned long n = 0;
    while (*s >= '0' && *s <= '9') {
        n = n * 10 + (unsigned)(*s++ - '0');
        if (n > INT_MAX) return -1;
    }
    if (*s && *s != '/') return -1;
    *p = s;
    return (long)n;
}
struct md_proc_path md_proc_path(const char *path) {
    struct md_proc_path out = {0};
    if (*path != '/') return out;
    const char *p = path;
    int foreign = 0;
    if (word(&p, "dev")) {
        if (word(&p, "fd")) {
            if (number(&p) < 0) return out;
        } else if (word(&p, "stdin") || word(&p, "stdout") || word(&p, "stderr"))
            out.ordinary_link = 1;
        else return out;
        out.kind = MD_PROC_FD;
    } else if (word(&p, "proc")) {
        if (!word(&p, "self") && !word(&p, "thread-self")) {
            long pid = number(&p);
            if (pid < 0) return out;
            foreign = pid != RAW0(getpid) && pid != RAW0(gettid);
        }
        if (word(&p, "task")) {
            long tid = number(&p);
            if (tid < 0) return out;
            foreign |= tid != RAW0(gettid);
        }
        if (word(&p, "fd")) {
            if (number(&p) < 0) return out;
            out.kind = MD_PROC_FD;
        } else if (word(&p, "cwd")) out.kind = MD_PROC_CWD;
        else if (word(&p, "root")) out.kind = MD_PROC_ROOT;
        else if (word(&p, "exe")) out.kind = MD_PROC_EXE;
        else if (word(&p, "cmdline")) out.kind = MD_PROC_CMDLINE;
        else if (word(&p, "auxv")) out.kind = MD_PROC_AUXV;
        else return out;
        if (foreign) out.kind = MD_PROC_FOREIGN;
    } else return out;
    out.anchor_length = (size_t)(p - path);
    out.tail = p;
    return out;
}
