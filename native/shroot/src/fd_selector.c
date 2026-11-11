#include "fd_selector.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
enum { BYTES = (MD_FD_SELECTOR_MASK + 1) / 8 };
struct md_fd_selector { unsigned references, all, instructions; uint64_t revision;
    unsigned char *bits[MD_FD_SELECTOR_KINDS]; };
uint64_t md_fd_selector_revision(const struct md_fd_selector *a) { return a ? a->revision : 0; }
void md_fd_selector_export(const struct md_fd_selector *a,struct md_fd_selector_view *out) {
    memset(out,0,sizeof(*out));
    if (!a) return;
    out->active=out->all=a->all;
    for (unsigned k=0;k<MD_FD_SELECTOR_KINDS;++k) if (a->bits[k]) {
        memcpy(out->bits[k],a->bits[k],BYTES);
        for (unsigned i=0;i<MD_FD_SELECTOR_WORDS;++i)
            if (out->bits[k][i]) out->active|=1u<<k;
    }
}
struct md_fd_selector *md_fd_selector_new(void) {
    struct md_fd_selector *a = calloc(1, sizeof(*a));
    if (a) a->references = 1;
    return a;
}
struct md_fd_selector *md_fd_selector_fork(struct md_fd_selector *a, int shared) {
    if (shared) { a->references++; return a; }
    struct md_fd_selector *copy = md_fd_selector_new();
    if (copy) { copy->all=a->all; copy->instructions=a->instructions; copy->revision=a->revision; }
    for (unsigned i=0;copy && i<MD_FD_SELECTOR_KINDS;++i) if (a->bits[i]) {
        copy->bits[i] = malloc(BYTES);
        if (!copy->bits[i]) { md_fd_selector_release(copy); return NULL; }
        memcpy(copy->bits[i], a->bits[i], BYTES);
    }
    return copy;
}
void md_fd_selector_release(struct md_fd_selector *a) {
    if (!a || --a->references) return;
    for (unsigned i=0;i<MD_FD_SELECTOR_KINDS;++i) free(a->bits[i]);
    free(a);
}
int md_fd_selector_has(struct md_fd_selector *a, unsigned kind, int fd) {
    if (!a || kind>=MD_FD_SELECTOR_KINDS || fd< -1) return -EINVAL;
    if (kind==MD_FD_SELECTOR_POLL) fd=-1;
    if (a->all&(1U<<kind)) return 1;
    if (fd==-1) return 0;
    fd &= MD_FD_SELECTOR_MASK;
    /* Allocate before installing an irreversible kernel filter. */
    if (!a->bits[kind] && !(a->bits[kind] = calloc(1, BYTES))) return -ENOMEM;
    return !!(a->bits[kind][fd / 8] & (1U << (fd % 8)));
}
int md_fd_selector_has_mask(struct md_fd_selector *a,unsigned kinds,int fd) {
    if (!a || fd< -1 || kinds&~((1U<<MD_FD_SELECTOR_KINDS)-1)) return -EINVAL;
    for (unsigned kind=0;kind<MD_FD_SELECTOR_KINDS;++kind) if (kinds&(1U<<kind)) {
        int result=md_fd_selector_has(a,kind,fd);
        if (result<=0) return result;
    }
    return 1;
}
int md_fd_selector_mark(struct md_fd_selector *a, unsigned kind, int fd) {
    if (!a || kind>=MD_FD_SELECTOR_KINDS || fd< -1) return -EINVAL;
    if (kind==MD_FD_SELECTOR_POLL) fd=-1;
    if (fd==-1) {
        ++a->revision;
        a->all|=1U<<kind;
        if (kind==MD_FD_SELECTOR_PROC) a->all|=1U<<MD_FD_SELECTOR_WATCH;
        return 0;
    }
    fd &= MD_FD_SELECTOR_MASK;
    if (!a->bits[kind]) return -EINVAL;
    a->bits[kind][fd / 8] |= (1U << (fd % 8)); ++a->revision; return 0;
}
int md_fd_selector_missing(const struct md_fd_selector *required,struct md_fd_selector *installed,
        enum md_fd_selector_kind *kind,int *fd) {
    if (!required || !installed || !kind || !fd) return -EINVAL;
    for (unsigned k=0;k<MD_FD_SELECTOR_KINDS;++k) {
        if (installed->all&(1U<<k)) continue;
        if (required->all&(1U<<k)) { *kind=k; *fd=-1; return 1; }
        if (!required->bits[k]) continue;
        for (unsigned i=0;i<BYTES;++i) {
            unsigned missing=required->bits[k][i]&~(installed->bits[k] ? installed->bits[k][i] : 0);
            if (!missing) continue;
            *kind=k; *fd=(int)(8*i+__builtin_ctz(missing));
            int ready=md_fd_selector_has(installed,k,*fd);
            return ready<0 ? ready : 1;
        }
    }
    return 0;
}
int md_fd_selector_target(const struct md_fd_selector *a,int fd,unsigned instructions) {
    if (!a || fd< -1 || !instructions || instructions>4096) return -EINVAL;
    return a->instructions+instructions+4>MD_FD_SELECTOR_BUDGET ? -1 : fd;
}
int md_fd_selector_installed(struct md_fd_selector *a,unsigned kind,int fd,unsigned instructions) {
    if (!instructions || instructions>4096) return -EINVAL;
    int error=md_fd_selector_mark(a,kind,fd);
    /* Linux charges four extra instructions for each ancestor filter. Count
     * only completed installations, never queries or a failed TSYNC attempt. */
    if (!error) a->instructions+=instructions+4;
    return error;
}
