# Inode Store

The inode store is the runtime's hierarchical namespace model, **not a kernel
filesystem or mount namespace**. It represents shared-inode links under the
shell SELinux hard-link restriction without copying data, claiming host links,
changing identity or special-casing dpkg.

## Ownership

Each object has a current ordinary native file, empty directory or symlink backing.
Immutable image bodies can be shared through explicitly retained source directories;
writable instances promote regular files on mutation. Names and directory parent edges refer to objects through
SQLite. Adding a name does not copy or move the object.
Within one current backing, descriptors opened before and after linking share the actual kernel device/inode,
data, mappings and file locks. Data IO, mmap and descriptor duplication stay native.

`md_inode_stat/fstat` combine native data attributes with logical UID/GID/mode,
stable logical device/inode identity and the namespace's transactional link
count, including zero for an open, unlinked object. Socket inodes expose S_IFSOCK
while their transport is an abstract kernel endpoint; their backing regular file
supplies permission and identity metadata. Raw host fstat still sees the
backing object's native links and directory contents. Concealing that distinction
from arbitrary guest code
would require a complete filesystem adapter; this prototype does not do that.

Each connection has one owner and is reopened after fork/exec. SQLite is linked
only into native namespace owners and fixtures, not the freestanding bootstrap
or in-guest syscall adapter. Its heap use and locks belong to the worker. The separate
[filesystem service experiment](filesystem-service.md) supplies explicit RPC,
native FD transfer and bounded reentrant client waits. The explicit
[namespace executor](namespace-execution.md) routes selected guest syscalls here;
a timeout is not cancellation of a committed operation.

`inode_db.c` owns transactions, allocation, identities and metadata queries;
`inode_path.c` resolves paths and reconstructs directory paths;
`inode_store.c` defines namespace operations, `inode_directory.c` implements
directory cursors, `inode_socket.c` implements socket publication and address
lookup, `inode_metadata.c` owns guest permissions and metadata mutations, and
`inode_acl.c` and `inode_capability.c` own logical security attributes;
`inode_import.c` imports prepared trees. `inode_snapshot.c` owns
immutable-source snapshots; `inode_backing.c` owns source validation and copy-up.
Their private contract is in
`inode_internal.h`. Callers use the explicit dirfd-based `inode_store.h` API.
The internal database format is versioned and incompatible formats are rejected,
not migrated. Format 11 includes logical owners/modes, POSIX ACLs, file capabilities,
unambiguous file-path history, stable identities, backing history,
immutable sources, socket addresses, transactional namespace
counters and the [watch event journal](watches.md). Incompatible stores require a separately prepared store; opening
one never rewrites or deletes it. Bind retains a name until
unlink, including stale listeners; existing connections survive unlink/rebind.

## Paths And Directories

Absolute paths start at the model's root; relative paths use an explicit root
sentinel or a real backing-directory FD. Native descriptor duplication and
fork/exec inheritance preserve identity. Directory moves update the namespace
parent edge, so an already-open FD resolves `..` and its reconstructed path at
the new location. There is no process-global emulated cwd or cached path string.

Directory hard links and moves into descendants are rejected. Replacing or
removing a directory requires it to be empty. Removed directories retain their
identity and parent for live descriptors, report zero links and cannot acquire
new entries; reconstructing their pathname returns ENOENT.

Symlinks have native inode identity and can themselves have multiple names.
Resolution handles relative and absolute targets, a 40-link bound, root-clamped
`..`, trailing slashes and distinct follow/no-follow/entry-mutation semantics.
`missing/..` and `file/..` cannot be collapsed lexically. O_PATH/no-follow can
return a symlink FD without opening its target. Directory search and mutation
permissions use the calling task's immutable guest credential snapshot, including
supplementary groups, sticky-directory ownership and root's execute-bit rule.
`access()` uses real IDs for traversal and the final object. New objects inherit
the filesystem UID/GID and a setgid parent's group; chmod/chown and timestamps
share this model. The native backing remains caller-owned, without kernel set-ID.
Trusted offline operations without a credential context use native authority.

`file_paths` retains the parent/name of files that have never had multiple names.
Descriptor readlink follows parent renames and marks deleted names without
substituting a newly created object. Once an object has had hardlink aliases,
the original descriptor dentry is ambiguous even after all but one alias is removed;
readlink returns ENOTSUP instead of guessing. Data and descriptor operations still work.

POSIX access/default ACLs are canonical Linux xattr values in `inode_acls`, shared
by every alias and copied with image metadata. A transactional `acl_mask` on the
object avoids extra lookups for ordinary mode-only access. Named users/groups,
the ACL mask, chmod and default inheritance use the caller's guest credentials.
Creation carries both the requested mode and kernel umask: a default ACL is
clamped by the requested mode; otherwise the umask applies. This includes socket
nodes and child-directory default ACLs. ACL mutations copy up before native
metadata changes, preserving sealed lower bodies. The backing filesystem never
receives guest-user ACLs or executable kernel file capabilities.

Ordinary xattr bytes remain on the backing inode. Their preparation uses the
same logical permission authority, including ACL masks and sticky-directory
ownership, and returns a retained descriptor after any required copy-up.
Kernel/LSM restrictions still apply; virtual root cannot bypass Android's
attribute restrictions. Attribute listing does not require file-read access.

## Directory Cursors

`md_inode_getdents` emits aligned Linux getdents64 records with logical inode
numbers and types, `.`/`..`, and opaque monotonic cookies. Committed name cookies
are not reused after deletion. Enumeration uses an indexed database read snapshot
per batch; changes between batches have ordinary non-snapshot traversal semantics.
An undersized first-record buffer fails without advancing the cursor.
An optional synchronous delivery callback runs after the read transaction and
before cursor advancement. Rejected or cancelled delivery leaves the offset
unchanged; successful delivery precedes the native seek. The namespace owner
serializes that entire operation, including delivery, against other read/seek
requests. SQLite locks are not held during delivery. A failure after publication
is not permission to replay the read. RPC uses local delivery followed by its
existing uncertain-reply contract; notifications copy directly to the caller.

The cursor is the actual backing directory's open-file-description offset,
not a client ID or service-side FD map. Thus dup, fork, exec and SCM_RIGHTS share
position, independent opens start at zero, and service restart retains position.
`md_inode_seekdir` handles rewind, saved cookies and position queries. One service
must serialize read/seek operations on shared descriptions; direct kernel
getdents/lseek or a second service bypass that contract. Arbitrary directory
offsets are verified on the current host/device filesystem, not all filesystems.

A lost enumeration reply may already have advanced the cursor. It is an uncertain
operation, not a replay-safe read. The model does not freeze a directory across
multiple batches or promise a particular ordering after concurrent rename.

## Offline Import

`md_inode_import_tree` copies an immutable prepared tree into an empty namespace
in one transaction. The source is not modified apart from normal read atime.
Callers supply byte/entry limits. Traversal uses a heap-owned stack, one copy
buffer and open directory FDs; no recursive import stack or SQLite work enters the guest.
This offline operation is deliberately not exposed as a long-running RPC request.

Regular data, permission bits, access/modification timestamps, directories and
symlink text are preserved. Source device/inode pairs identify shared objects;
aliases refer to one new backing object, never a copied file or a substitute
symlink. Metadata snapshots detect observed source changes. The source must stay
immutable: these checks are not an atomic snapshot of a live filesystem.

Default import maps ownership to the executor and keeps the destination root's
metadata. Explicit `preserve_ownership` retains source UID/GID/mode, including
root-directory and set-ID metadata, in the database; the host remains unchanged.
Neither policy reproduces ctime, sparse extents or physical layout.
Special files, set-ID bits without preservation, cross-device traversal
and xattrs other than the kernel-assigned SELinux label are rejected explicitly.
SELinux labels are not copied or changed. Overlapping source/storage trees and
nonempty destination namespaces are rejected. No fallback clears unsupported
metadata or claims it was preserved.

Native objects and data are synced before namespace commit. Failure before commit
leaves no published partial tree, but may retain untracked objects. An uncertain
commit requires reopen/inspection before retry. The model still has no garbage
collector; byte limits bound the current attempt, not accumulated failed attempts.
Import is not execution from the new namespace or successful package installation.

## Transactions

Sealed images, writable instances and copy-up retain these transaction boundaries;
their [backing and lifetime contract](images.md#sharing-and-copy-on-write) is
independent of OCI parsing and launch-local directory attachments.

- Each connection reuses compiled inode, descriptor, name and directory queries,
  plus BEGIN/BEGIN IMMEDIATE/COMMIT/ROLLBACK programs. Every use resets the statement and clears all bindings
  before leaving the operation. No rows, paths or read snapshots are cached;
  other writers and native file-data changes remain visible in the next operation.
  Statements are finalized with their owning connection.
- Each object stores its name count and child-directory count. Insert/delete
  triggers maintain them in the same transaction as namespace changes, including
  rollback, rename and exchange. Entry updates must use delete/insert. Node reads
  obtain these logical attributes together with identity, without counting queries.
  The independent audit recomputes both counters from names and directory kinds.
- Stat reads current native attributes, then uses the logical node snapshot already
  obtained by path resolution or the one descriptor lookup. Directory enumeration
  and parent mutation checks use that same membership model. Root is always attached;
  detached directories report zero links and reject enumeration and new entries.
  No pathname reopening or cached native attribute snapshot substitutes for an FD.
- Image opening returns one retained FD, its object identity and canonical dentry
  path in one read transaction. Ordinary opens do not reconstruct unused paths.
  Reconstructing a directory path reuses prepared parent-name queries, not cached names.
- Create allocates a randomly named native object, syncs regular-file data and
  its containing directory before publishing a reference. A collision fails
  without truncation. Symlink data and directory metadata retain filesystem
  durability semantics; power-loss recovery is not established by these tests.
- Link, unlink, rename, replacement and exchange each use one database transaction.
  Names are byte strings, not UTF-8 text or concatenated SQL. A same-inode rename
  preserves both names; NOREPLACE rejects an existing target.
  Directory moves/exchanges change parent edges in the same transaction as names.
- Reads resolve names in a snapshot. They cannot see half an exchange. Contents
  are not transactional: writes through returned FDs follow kernel IO semantics.
- Store opening and each metadata operation take an exclusive kernel `flock`
  on their private object-directory description before entering SQLite.
  Independent services for one store therefore serialize metadata, including
  reads; separate stores do not share a lock. The gate is released after commit
  or rollback and by the kernel when its owner dies. It adds no per-request heap
  allocation and is not held during RPC delivery, guest execution or ordinary
  IO through returned file descriptors. Connections must still be reopened after
  fork; an inherited description would share the lock owner.
- Lock admission waits for kernel release, without polling or a settling delay.
  A caught signal can fail admission before the transaction starts. Kernel IO
  and lock waiting do not promise bounded service completion; the caller's RPC
  deadline bounds observation, not cancellation of an accepted request. Offline
  import must not contend with live clients.
- The rollback journal uses `synchronous=EXTRA`. No SQLite busy timeout or
  automatic replay is installed. An external SQLite writer bypassing the store
  gate still returns EAGAIN. An IO error during commit can have an unknown
  outcome: inspect before replaying a mutation.
- Detached objects and uncommitted allocations are retained and audited separately.
  There is no live garbage collector. Removing metadata while another process
  holds an FD would break descriptor lookup. This bounded experiment is not
  suitable for indefinite production churn.

SQLite supplies database transactions, not Linux filesystem semantics. References:
[atomic commit](https://www.sqlite.org/atomiccommit.html),
[isolation](https://www.sqlite.org/isolation.html),
[kernel lock ownership](https://man7.org/linux/man-pages/man2/flock.2.html), and
[Linux rename](https://man7.org/linux/man-pages/man2/rename.2.html).

## Checks

`test_inodes.c` runs on the Termux build host and as a Debian/glibc process under
real shell UID 2000 in `u:r:shell:s0` on NX809J / API 36 / Linux 6.12.23 / 4 KiB:

- Open-before/after-link identity, shared writes/mappings/locks, modes, unlink to
  zero, name reuse and retained descriptors after reopening the namespace.
- Replacement/exchange, same-inode rename, enumeration, non-UTF-8 names,
  truncation and explicit rejection of unsupported API requests.
- Independent connections across fork/exec and commit visibility after a
  deterministic kernel-lock conflict. Four independent store owners concurrently
  open/create, link, stat and unlink without transient SQLite errors; the final
  audit verifies names, detached objects and absence of untracked allocations.
- Reused queries observe another connection's link/unlink and name replacement,
  missing-name lookups, native truncation and open-unlinked FD identity without
  recompiling the warmed read programs.
- Open/fstat query counts, directory batches observing another writer, and
  rejected/cancelled directory delivery before shared-cursor advancement.
- Node reads share transactional membership and link counts; another owner's
  removal is visible on the next operation, while stat retains exact counts
  before and after removal. Independent audit rejects corrupt name/directory
  counters. Opening an incompatible store fails without rewriting its format.
- Directory moves/replacement/exchange with retained FDs, cycle rejection,
  detached directories, directory link counts and real search/write permissions.
- Relative/absolute/dangling/cyclic symlinks, hard-linked symlink inodes, follow
  flags, O_PATH, readlink truncation and the 40-link traversal boundary.
- 189 path/flag/mutation comparisons against ordinary POSIX syscall results.
  On the build host those are direct kernel calls; in the Debian fixture the
  reference calls pass through the existing guest path adapter. Host comparison
  and device comparison are separate evidence, not independent kernel oracles
  inside the guest.
- Eleven deterministic SIGKILL boundaries: create after object sync, and
  create/link/unlink/rename/exchange before and after namespace commit.
  Precommit checkpoints flush actual dirty database pages and verify a nonempty
  rollback journal. Reopening checks names, inode identity, database integrity,
  foreign keys and orphan accounting.
- Twelve additional hierarchy SIGKILL boundaries: mkdir/symlink after object
  sync, and mkdir/symlink/directory move/exchange/rmdir before and after commit.
  Retained directory FDs, parent paths and shared file data remain coherent after
  reopening. Audit checks directory parent/name agreement and root ancestry.

Pipes identify exact checkpoints; waitpid observes child termination. An outer
timeout cancels a stuck fixture; it is not a settling delay. Process-kill tests
do **not** establish power-loss correctness. No phone reboot or system change
is involved.

`test_import.c` covers contents, modes, timestamps, symlinks, source independence,
limits, unsupported metadata, observed source mutation, overlap rejection and
three additional deterministic import SIGKILL boundaries. It also imports the
prepared Debian fixture under shell UID 2000 and checks selected files and the
namespace audit. Both current Android identities deny source hard-link creation:
the fixture reports that alias-import branch as LIMIT rather than substituting
copies. It requires another host with native hard-link support. FIFO rejection
is covered on the Termux host; shell policy blocks creating that source fixture.
Directory RPC paging, inherited/shared cursors, concurrent readers, lost replies
and service restart are covered in `test_rpc.c`.

## Remaining Boundary

Namespace-induced ctime updates, complete set-ID/file-capability clearing on
native data writes,
exact hardlink-dentry data notifications, live import/promotion, FD
reclamation, out-of-space recovery and production throughput are not implemented
or validated. RPC descriptor transfer does not supply lifetime/reclamation policy.
Host ownership stays the real executor identity; guest ownership is logical.

The namespace executor covers selected path/FD operations, cwd, directory
cursors and program mapping. It passes a fixture package lifecycle and the gzip
hard-link extraction; direct-backend controls still fail independently. Complete
the remaining ABI and lifetime contracts before treating this as an installed
Debian environment. SQLite stays in native namespace owners, outside guest execution.
