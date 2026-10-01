#ifndef MD_INODE_STORE_H
#define MD_INODE_STORE_H
#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>
#include <sys/stat.h>
#include "profile.h"
#include "image_identity.h"

/* Experimental inode namespace, not a syscall adapter. Each connection
 * has one owner; never use it from SIGSYS, a signal handler, or after fork.
 * Errors are negative errno. Store operations serialize before their transaction.
 * EAGAIN still reports an external SQLite writer bypassing that gate; no replay.
 * EIO during commit has an unknown outcome; reopen and inspect before replay.
 * Files are native descriptors. No object is reclaimed while clients may exist. */
struct md_inode_store;
struct md_inode_statistics {
    struct md_cost prepare, step, transaction, lock;
    uint64_t query_reuses;
    uint64_t link_count_queries, membership_queries;
};
/* Caller-owned diagnostic counters; NULL disables clock sampling. */
void md_inode_measure(struct md_inode_store *, struct md_inode_statistics *);
/* No implicit process cwd: pass the root sentinel or a directory FD from this
 * store. Absolute paths ignore dirfd. Data/directory FDs are real kernel FDs. */
#define MD_INODE_ROOT (-1)
int md_inode_store_open(const char *directory, int create, struct md_inode_store **out);
void md_inode_store_close(struct md_inode_store *);
int md_inode_store_seal(struct md_inode_store *);
/* Prepare store-owned runtime resources before accepting guest operations.
 * Existing directories retain their owners and permissions. */
int md_inode_runtime_prepare(struct md_inode_store *);
int md_inode_snapshot(struct md_inode_store *source, struct md_inode_store *empty_destination);
/* Retained FD identity selects its logical object, even after copy-up. Flags
 * select a new descriptor; the caller's original open description is untouched. */
int md_inode_reopen(struct md_inode_store *, int fd, int flags, int mutable);
int md_inode_create(struct md_inode_store *, int dirfd, const char *path, mode_t mode);
int md_inode_mkdir(struct md_inode_store *, int dirfd, const char *path, mode_t mode);
int md_inode_symlink(struct md_inode_store *, const char *target, int dirfd, const char *path);
ssize_t md_inode_readlink(struct md_inode_store *, int dirfd, const char *path, char *, size_t);
int md_inode_open(struct md_inode_store *, int dirfd, const char *path, int flags, mode_t mode);
int md_inode_open_resolved(struct md_inode_store *, int, const char *, int, mode_t, uint64_t);
/* Read-only image open and identity from one namespace snapshot. */
int md_inode_open_image(struct md_inode_store *, int, const char *, int, struct md_image_identity *);
int md_inode_link(struct md_inode_store *, int sourcefd, const char *source,
        int targetfd, const char *target, int flags);
int md_inode_unlink(struct md_inode_store *, int dirfd, const char *path, int flags);
int md_inode_rename(struct md_inode_store *, int sourcefd, const char *source,
        int targetfd, const char *target, unsigned flags);
int md_inode_stat(struct md_inode_store *, int dirfd, const char *path, int flags, struct stat *);
int md_inode_fstat(struct md_inode_store *, int fd, struct stat *);
struct md_fs_request;
int md_inode_metadata(struct md_inode_store *, const struct md_fs_request *);
int md_inode_xattr_open(struct md_inode_store *, int fd, const char *name, int access);
int md_inode_capability(struct md_inode_store *, const struct md_fs_request *, void *, size_t);
/* Trusted offline import only; records logical ownership, never host chown. */
int md_inode_import_metadata(struct md_inode_store *, int fd, uint32_t uid, uint32_t gid, mode_t);
/* A directory or unambiguous file dentry. Files with any hardlink history
 * return ENOTSUP, never an arbitrary alias. Unlinked files retain a deleted
 * suffix while their parent path is available. No per-open path cache. */
int md_inode_path(struct md_inode_store *, int fd, char *, size_t);
int md_inode_realpath(struct md_inode_store *, int dirfd, const char *, char *, size_t);
/* Unlinked native storage, outside the logical namespace and its metadata. */
int md_inode_temporary(struct md_inode_store *);
/* Stable store-local identity. Retained objects outlive names and open FDs. */
int md_inode_object_id(struct md_inode_store *, int fd, char out[33]);
int md_inode_open_object(struct md_inode_store *, const char *id, int flags);
/* Pathname socket identity belongs to the namespace; data, credentials, buffer
 * passing and listener lifetime remain kernel Unix-socket operations. */
int md_inode_socket_bind(struct md_inode_store *, int dirfd, const char *path, mode_t, int socket);
int md_inode_socket_address(struct md_inode_store *, int dirfd, const char *path, char *, size_t);
int md_inode_socket_name(struct md_inode_store *, const char *endpoint, char *, size_t);

/* Linux getdents64 records. Directory offsets belong to the native open file
 * description, shared by dup/fork/SCM_RIGHTS. Serialize directory read/seek
 * through one service; direct kernel getdents/lseek are outside this model. */
struct md_inode_dirent { uint64_t inode; int64_t next; uint16_t size; uint8_t type; char name[]; };
ssize_t md_inode_getdents(struct md_inode_store *, int fd, void *, size_t);
/* Optional synchronous delivery before cursor commit. A rejected delivery leaves
 * the offset unchanged. The callback must not reenter the namespace owner. */
ssize_t md_inode_getdents_deliver(struct md_inode_store *, int fd, void *, size_t,
        int (*deliver)(void *, const void *, size_t), void *);
int64_t md_inode_seekdir(struct md_inode_store *, int fd, int64_t offset, int whence);

struct md_inode_import_limits { uint64_t bytes, entries; int preserve_ownership; };
struct md_inode_import_result { uint64_t bytes, entries, aliases; };
/* Offline import of an immutable source into an empty namespace. Source stays
 * unchanged. A failed/uncertain commit must be inspected, never replayed blindly. */
int md_inode_import_tree(struct md_inode_store *, int source_fd,
        const struct md_inode_import_limits *, struct md_inode_import_result *);

/* Callback runs inside a read snapshot; it must not reenter this connection. */
int md_inode_list(struct md_inode_store *, int dirfd, const char *path,
        int (*visit)(const char *, void *), void *);

#ifdef MD_INODE_TESTING
enum md_inode_checkpoint { MD_OBJECT_SYNCED, MD_NAMESPACE_STAGED, MD_NAMESPACE_COMMITTED, MD_STORE_CONTENDED };
void md_inode_observe(struct md_inode_store *, void (*)(enum md_inode_checkpoint, void *), void *);
struct md_inode_audit { unsigned objects, names, detached, untracked; };
int md_inode_audit(struct md_inode_store *, struct md_inode_audit *);
#endif
#endif
