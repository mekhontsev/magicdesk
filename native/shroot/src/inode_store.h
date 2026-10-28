#ifndef MD_INODE_STORE_H
#define MD_INODE_STORE_H
#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>
#include <sys/stat.h>
#include "profile.h"
#include "image_identity.h"
#include "file_open.h"
#include "directory_record.h"

/* Experimental inode namespace, not a syscall adapter. Each connection
 * belongs to one worker; never use it from SIGSYS, a signal handler, or after fork.
 * Errors are negative errno. Store operations serialize before their transaction.
 * EAGAIN still reports an external SQLite writer bypassing that gate; no replay.
 * EIO during commit has an unknown outcome; reopen and inspect before replay.
 * Files are native descriptors. No object is reclaimed while clients may exist. */
struct md_inode_store;
/* Worker-only read scope. Every nested operation still resolves permissions
 * and observes current native attributes. Never retain across a blocking wait. */
int md_inode_read_scope_begin(void *store);
int md_inode_read_scope_end(void *store);
/* Bounded worker-owned store gate, not a transaction or a nested read scope.
 * Every operation completes its own writes/events before returning. Release
 * before parking, delegation or entering another store; never nest windows.
 * A successful begin retains the connection until successful end. */
int md_inode_operation_window_begin(void *store);
int md_inode_operation_window_end(void *store);
/* Revoke retention before entering a different store/provider. The executor
 * still ends the window normally; subsequent scopes acquire/release the gate. */
int md_inode_operation_window_pause(struct md_inode_store *);
/* Stable format family and independently evolving metadata schema revision. */
int md_inode_store_format(void);
int md_inode_store_revision(void);
struct md_inode_statistics {
    struct md_cost prepare, step, transaction, lock;
    struct md_cost version, descriptor, path;
    struct md_cost sync_object, sync_directory, commit, checkpoint;
    uint64_t query_reuses;
    uint64_t link_count_queries, membership_queries;
    uint64_t metadata_cache_hits, metadata_cache_misses;
    uint64_t walk_components, walk_materializations;
    uint64_t write_admissions;
    uint64_t directory_check_hits, directory_check_misses;
    uint64_t directory_pin_hits;
    uint64_t path_prefix_hits;
};
/* Caller-owned diagnostic counters; NULL disables clock sampling. */
void md_inode_measure(struct md_inode_store *, struct md_inode_statistics *);
/* No implicit process cwd: pass the root sentinel or a directory FD from this
 * store. Absolute paths ignore dirfd. Data/directory FDs are real kernel FDs. */
#define MD_INODE_ROOT (-1)
int md_inode_store_open(const char *directory, int create, struct md_inode_store **out);
/* No schema/journal updates or index preparation. Unknown/newer schemas fail
 * before namespace queries; callers must not treat that as an empty store. */
int md_inode_store_open_inspect(const char *directory, struct md_inode_store **out);
/* Offline maintenance fails with EBUSY while a connection or path origin retains
 * the store. Final connection close releases SQLite and transaction resources;
 * retained origins keep the existing backing/source maintenance leases. */
int md_inode_store_open_exclusive(const char *directory, struct md_inode_store **out);
struct md_inode_lifetime;
/* New worker-local connection to retained exact backing custody. Does not
 * reopen the lease or resolve source paths that may have been renamed. */
int md_inode_store_reconnect(struct md_inode_lifetime *, struct md_inode_store **out);
/* Initialize an unpublished, empty, exclusively prepared retained directory.
 * The caller supplies lifetime custody; no pathname is reopened or retained. */
int md_inode_store_initialize(struct md_inode_lifetime *, struct md_inode_store **out);
/* Private temporary inode filesystem in the caller-selected backing directory.
 * Same metadata/data semantics as an ordinary store; this does not imply RAM
 * backing. Final retained custody retires it, including imported owners. */
struct md_user_namespace;
int md_inode_store_transient(int parent, struct md_user_namespace *, struct md_inode_store **out);
/* Worker-local connection custody, distinct from shareable backing lifetime.
 * Retaining does not permit concurrent access or retain request policy. */
struct md_inode_store *md_inode_store_retain(struct md_inode_store *);
void md_inode_store_close(struct md_inode_store *);
enum md_inode_sync_mode { MD_INODE_SYNC_FILE, MD_INODE_SYNC_DATA, MD_INODE_SYNC_FILESYSTEM };
/* Native data first, then all committed namespace metadata under the same gate.
 * FILE/DATA require a real descriptor. FILESYSTEM also accepts the root sentinel. */
int md_inode_sync(struct md_inode_store *, int fd, enum md_inode_sync_mode);
int md_inode_store_seal(struct md_inode_store *);
/* Mutable, lifetime-scoped superblock policy, independent of per-mount flags
 * and immutable image sealing. Persistent stores deny new write authority but
 * do not revoke writable native OFDs/mappings. Negative errno on unavailable IO. */
int md_inode_store_readonly(struct md_inode_store *);
int md_inode_store_reconfigure(struct md_inode_store *, int readonly);
/* Prepare store-owned runtime resources before accepting guest operations.
 * Existing directories retain their owners and permissions. */
int md_inode_runtime_prepare(struct md_inode_store *);
int md_inode_snapshot(struct md_inode_store *source, struct md_inode_store *empty_destination);
/* Retained FD identity selects its logical object, even after copy-up. Flags
 * select a new descriptor; the caller's original open description is untouched. */
int md_inode_reopen(struct md_inode_store *, int fd, int flags, int mutable);
int md_inode_plan_reopen(struct md_inode_store *, int, int, int, struct md_open_completion *, int native_plan);
int md_inode_create(struct md_inode_store *, int dirfd, const char *path, mode_t mode);
int md_inode_mkdir(struct md_inode_store *, int dirfd, const char *path, mode_t mode);
int md_inode_mkfifo(struct md_inode_store *, int dirfd, const char *path, mode_t mode);
int md_inode_mknod(struct md_inode_store *, int dirfd, const char *path, mode_t mode, dev_t);
int md_inode_symlink(struct md_inode_store *, const char *target, int dirfd, const char *path);
ssize_t md_inode_readlink(struct md_inode_store *, int dirfd, const char *path, char *, size_t);
int md_inode_open(struct md_inode_store *, int dirfd, const char *path, int flags, mode_t mode);
int md_inode_open_resolved(struct md_inode_store *, int, const char *, int, mode_t, uint64_t);
int md_inode_prepare_open(struct md_inode_store *, int, const char *, int, mode_t, uint64_t,
        struct md_open_completion *);
struct md_inode_birth;
/* Optional birth custody lets a native plan prepare a missing leaf in the same
 * resolved scope as an existing open. NULL retains effect-free decline. */
int md_inode_plan_open(struct md_inode_store *, int, const char *, int, mode_t, uint64_t,
        struct md_open_completion *, int native_plan,struct md_inode_birth **);
/* Unpublished regular-file birth. Retains its worker-local connection. The
 * returned O_PATH pin belongs to the caller; no guest name exists until commit.
 * Commit consumes the owner on success. Failure retains it for discard after
 * the caller closes any native FD it acquired. No SQLite snapshot spans the
 * caller's effect, and no retry of a completed publication is permitted. */
int md_inode_birth_commit(struct md_inode_birth **);
struct md_inode_store *md_inode_birth_store(const struct md_inode_birth *);
int md_inode_birth_discard(struct md_inode_birth **);
/* Terminal worker shutdown only: release custody without publishing or unlinking.
 * The existing maintenance owner reclaims the unpublished root object. */
void md_inode_birth_forget(struct md_inode_birth **);
/* Read-only image open and identity from one namespace snapshot. */
int md_inode_open_image(struct md_inode_store *, int, const char *, int, struct md_image_identity *);
int md_inode_link(struct md_inode_store *, int sourcefd, const char *source,
        int targetfd, const char *target, int flags);
int md_inode_unlink(struct md_inode_store *, int dirfd, const char *path, int flags);
int md_inode_rename(struct md_inode_store *, int sourcefd, const char *source,
        int targetfd, const char *target, unsigned flags);
int md_inode_stat(struct md_inode_store *, int dirfd, const char *path, int flags, struct stat *);
int md_inode_fstat(struct md_inode_store *, int fd, struct stat *);
/* native_leaf is a worker-produced, retained descriptor's membership proof,
 * not an application flag. Promotion and mutable metadata are checked anew. */
int md_inode_fstat_origin(struct md_inode_store *, int fd, struct stat *, int native_leaf);
struct md_fs_request;
int md_inode_metadata(struct md_inode_store *, const struct md_fs_request *);
int md_inode_capability(struct md_inode_store *, const struct md_fs_request *, void *, size_t);
/* Trusted offline import only; records logical ownership, never host chown. */
int md_inode_import_metadata(struct md_inode_store *, int fd, uint32_t uid, uint32_t gid, mode_t);
/* A directory or unambiguous file dentry. Files with any hardlink history
 * return ENOTSUP, never an arbitrary alias. Unlinked files retain a deleted
 * suffix while their parent path is available. No per-open path cache. */
int md_inode_path(struct md_inode_store *, int fd, char *, size_t);
/* Captured namespace entry, independent of other hardlinks to the same inode.
 * native_birth selects the first promoted name for a pre-promotion native FD. */
int md_inode_dentry_path(struct md_inode_store *,int,uint64_t,int native_birth,char *,size_t);
/* Backing identity ancestry, independent of names and the selected root.
 * Returns 1/0; an object from a different store is not contained. */
int md_inode_contains(struct md_inode_store *, int root, int object);
int md_inode_realpath(struct md_inode_store *, int dirfd, const char *, char *, size_t);
/* Unlinked native storage, outside the logical namespace and its metadata. */
int md_inode_temporary(struct md_inode_store *);
/* Stable store-local identity. Retained objects outlive names and open FDs. */
int md_inode_object_id(struct md_inode_store *, int fd, char out[33]);
int md_inode_open_object(struct md_inode_store *, const char *id, int flags);
int md_inode_landlock_fd(struct md_inode_store *,int fd,int flags);
/* Stable physical name of an already opened backing object. The store and its
 * source leases must outlive use; guest unlink/rename/copy-up cannot retarget it.
 * EXDEV means the descriptor has no leased backing name in this store.
 * promote permits durable native-leaf publication when FD transfer is restricted. */
int md_inode_descriptor_source(struct md_inode_store *, int, char *, size_t, int promote);
/* Pathname socket identity belongs to the namespace; data, credentials, buffer
 * passing and listener lifetime remain kernel Unix-socket operations. */
int md_inode_socket_bind(struct md_inode_store *, int dirfd, const char *path, const char *name, mode_t, int socket);
int md_inode_socket_address(struct md_inode_store *, int dirfd, const char *path, char *, size_t);
int md_inode_socket_name(struct md_inode_store *, const char *endpoint, char *, size_t);

/* Linux getdents64 records. Directory offsets belong to the native open file
 * description, shared by dup/fork/SCM_RIGHTS. Serialize directory read/seek
 * through one service; direct kernel getdents/lseek are outside this model. */
ssize_t md_inode_getdents(struct md_inode_store *, int fd, void *, size_t);
/* Optional synchronous delivery before cursor commit. A rejected delivery leaves
 * the offset unchanged. The callback must not reenter the namespace owner. */
ssize_t md_inode_getdents_deliver(struct md_inode_store *, int fd, void *, size_t,
        int (*deliver)(void *, const void *, size_t), void *);
/* Caller orders this OFD through later copy/commit; next receives its cookie. */
ssize_t md_inode_getdents_prepare(struct md_inode_store *, int fd, void *, size_t, int64_t *next);
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
enum md_inode_checkpoint { MD_OBJECT_SYNCED, MD_NAMESPACE_STAGED, MD_NAMESPACE_COMMITTED, MD_STORE_CONTENDED,
    MD_PUBLICATION_INTENT, MD_PUBLICATION_MOVED, MD_PUBLICATION_CATALOGUED, MD_PUBLICATION_STAGED };
void md_inode_observe(struct md_inode_store *, void (*)(enum md_inode_checkpoint, void *), void *);
struct md_inode_audit { unsigned objects, names, detached, untracked; };
int md_inode_audit(struct md_inode_store *, struct md_inode_audit *);
#endif
#endif
