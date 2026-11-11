#ifndef MD_FD_SELECTOR_H
#define MD_FD_SELECTOR_H
#include <stdint.h>

/* Descriptor requirements plus the table revision used for provider discovery.
 * The descriptor owner validates this token under its mutation turn. */
struct md_fd_observation { long selectors; uint64_t generation; };

/* Finite selector classes bound irreversible kernel filter growth. A selector
 * is never file identity; the namespace owner checks the retained kernel FD. */
enum { MD_FD_SELECTOR_MASK = 511 };
enum md_fd_selector_kind { MD_FD_SELECTOR_WATCH, MD_FD_SELECTOR_PROC,
    MD_FD_SELECTOR_EPOLL_TARGET, MD_FD_SELECTOR_EPOLL, MD_FD_SELECTOR_POLL,
    MD_FD_SELECTOR_KINDS };
enum { MD_FD_SELECTOR_WORDS=(MD_FD_SELECTOR_MASK+1)/64 };
struct md_fd_selector;
struct md_fd_selector_view {
    unsigned active,all;
    uint64_t bits[MD_FD_SELECTOR_KINDS][MD_FD_SELECTOR_WORDS];
};
uint64_t md_fd_selector_revision(const struct md_fd_selector *);
void md_fd_selector_export(const struct md_fd_selector *,struct md_fd_selector_view *);
/* Installed seccomp selectors belong to a thread group, not its address space
 * or descriptor table. Fork copies them, threads share them, exec retains them. */
struct md_fd_selector *md_fd_selector_new(void);
struct md_fd_selector *md_fd_selector_fork(struct md_fd_selector *, int shared);
void md_fd_selector_release(struct md_fd_selector *);
/* -1 selects all descriptor numbers, for admission before application filters
 * make later selector installation impossible. PROC also covers WATCH calls. */
int md_fd_selector_has(struct md_fd_selector *, unsigned kind, int fd);
int md_fd_selector_has_mask(struct md_fd_selector *, unsigned kinds, int fd);
int md_fd_selector_mark(struct md_fd_selector *, unsigned kind, int fd);
/* Bounded difference of two selector inventories. Does not install anything;
 * a suspended file-table owner may retain requirements separately until it can
 * execute again. 1 supplies one missing class/number, 0 means covered. */
int md_fd_selector_missing(const struct md_fd_selector *required,struct md_fd_selector *installed,
        enum md_fd_selector_kind *kind,int *fd);
/* Bound our irreversible filters to half of Linux's 32768-instruction path
 * limit, leaving room for the base adapter and application filters. Promotion
 * uses the existing all-FD dispatch of this kind, not a policy bypass. */
enum { MD_FD_SELECTOR_BUDGET = 16384 };
int md_fd_selector_target(const struct md_fd_selector *, int fd, unsigned instructions);
int md_fd_selector_installed(struct md_fd_selector *, unsigned kind, int fd, unsigned instructions);
#endif
