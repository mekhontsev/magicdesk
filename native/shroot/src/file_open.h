#ifndef MD_FILE_OPEN_H
#define MD_FILE_OPEN_H
#include <fcntl.h>
#include <stdint.h>
#include <stdatomic.h>
#include <sys/stat.h>

/* A deferred pin is an authorized O_PATH descriptor, not a data endpoint.
 * FIFO rendezvous and terminal acquisition finish outside the filesystem
 * worker; MD_OPEN_CALLER preserves the opening task's session semantics. */
enum md_open_kind { MD_OPEN_READY, MD_OPEN_FIFO, MD_OPEN_PIPE, MD_OPEN_CALLER, MD_OPEN_DEVICE, MD_OPEN_PLAN,
    MD_OPEN_NEEDS_WRITE };
/* Read windows must never retain a transaction across a write or copy-up. */
static inline int md_open_readonly_plan(unsigned flags) {
    return !(flags & ~(O_CLOEXEC | O_NOFOLLOW | O_LARGEFILE));
}
/* Ordinary endpoints; exclusive creation additionally needs unpublished birth
 * custody. Keep offer, provider and execution-window admission in agreement. */
static inline int md_open_native_plan(unsigned flags,int birth) {
    if (birth) flags&=~O_EXCL;
    /* The provider additionally checks kind: NOFOLLOW metadata opens can
     * reopen directories through fd/. without changing their status flags. */
    if (flags&O_PATH) return 1;
    return (flags&(O_CREAT|O_EXCL))!=(O_CREAT|O_EXCL)
        && (flags&O_ACCMODE)!=O_ACCMODE && !(flags & ~(O_ACCMODE | O_CLOEXEC
        | O_NOFOLLOW | O_LARGEFILE | O_APPEND | O_TRUNC | O_NONBLOCK | O_NOCTTY
        | O_NOATIME | O_SYNC | O_DSYNC | O_DIRECT | O_CREAT | O_DIRECTORY));
}
static inline int md_open_native_kind(unsigned flags,unsigned mode) {
    return S_ISDIR(mode) || (S_ISREG(mode) && (flags&(O_PATH|O_NOFOLLOW))!=(O_PATH|O_NOFOLLOW));
}
struct md_open_completion {
    enum md_open_kind kind;
    /* Owned only for MD_OPEN_PIPE; a regular-file rendezvous mapping. */
    int control;
    /* Worker-local device selection; resolved before protocol publication. */
    uint64_t device;
    unsigned mode;
    /* MD_OPEN_PLAN only: identity verified on the returned O_PATH pin. */
    uint64_t backing_device, backing_inode;
    /* Worker-local membership proof, retained only with the opened identity.
     * Attributes and catalogue promotion remain live observations. */
    unsigned native_leaf;
    /* Worker-local, stable catalogue dentry selected by this open. */
    uint64_t dentry;
};
int md_open_device(unsigned mode, uint64_t device, int flags, struct md_open_completion *);
/* Enumerate the portable character endpoints; zero marks the end. The returned
 * absolute name and device number also seed launch-private guest metadata. */
int md_open_device_node(unsigned index,const char **path,uint64_t *device);
int md_complete_open(int, int, struct md_open_completion);
struct md_open_observation { uint32_t initial_reader, writers; };
int md_complete_open_observed(int, int, struct md_open_completion, struct md_open_observation *);
int md_complete_open_interruptible(int, int, struct md_open_completion,
    struct md_open_observation *, const atomic_int *);
/* Reopen one retained native object; FIFO completion remains deferred. */
int md_reopen_file(int fd, int flags, struct md_open_completion *);
/* Already authorized regular/directory pin; the caller retains its ownership.
 * Flags and kind were validated by the provider, not inferred from a pathname. */
int md_open_pinned(int fd, int flags);
/* Positive pid selects another process; zero names the calling process. */
void md_descriptor_path(char path[64], unsigned pid, unsigned fd);
/* Only after native-plan kind admission; returns the matching open flags. */
unsigned md_open_plan_path(char path[64], unsigned pid, unsigned fd, unsigned flags);
/* fd/. additionally needs native directory search permission. Decline before
 * publication when the exact-descriptor route is required instead. */
int md_open_plan_access(int fd, unsigned flags);
static inline int md_open_endpoint_flags(int flags) {
    return flags & ~(O_CREAT | O_EXCL | O_NOFOLLOW);
}
#endif
