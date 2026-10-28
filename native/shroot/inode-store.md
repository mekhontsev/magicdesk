# Inode Store

The inode store is the runtime's hierarchical namespace model, **not a kernel
filesystem or mount namespace**. It represents shared-inode links under the
shell SELinux hard-link restriction without copying data, claiming host links,
changing identity or special-casing dpkg.

- [Ownership](#ownership)
- [Storage Evolution](#storage-evolution)
- [Native Publication](#native-publication)
- [Paths And Directories](#paths-and-directories)
- [Directory Cursors](#directory-cursors)
- [Offline Import](#offline-import)
- [Transactions](#transactions)
- [Checks](#checks)
- [Limits](#limits)

## Ownership

Each object has a current ordinary native file, directory or symlink backing.
Immutable image bodies can be shared through explicitly retained source directories;
writable instances copy up shared regular files on mutation. SQLite owns directory
parent edges, shared objects and aliases. Eligible private regular files instead
keep their ordinary names inside the backing of their catalogued parent. They
enter the catalogue when an operation needs shared identity; later adding an
alias neither copies nor moves the object.
Within one current backing, descriptors opened before and after linking share the actual kernel device/inode,
data, mappings and file locks. Data IO, mmap and descriptor duplication stay native.

`md_inode_stat/fstat` combine native data attributes with logical UID/GID/mode,
stable logical device/inode identity and the namespace's transactional link
count, including zero for an open, unlinked object. Socket inodes expose S_IFSOCK
while their transport is an abstract kernel endpoint; their backing regular file
supplies permission and identity metadata. Raw host fstat still sees the
backing object's native links and directory contents. Concealing that distinction
from arbitrary guest code
would require complete mediation of every filesystem entry point; Shroot does
not provide that security boundary.

Each connection belongs to one worker and is reopened after fork/exec. SQLite is linked
only into native namespace owners and fixtures, not the freestanding bootstrap
or in-guest syscall adapter. Its heap use and locks belong to the worker. The separate
[filesystem service](filesystem-service.md) supplies explicit RPC,
native FD transfer and bounded reentrant client waits. The explicit
[namespace executor](namespace-execution.md) routes selected guest syscalls here;
a timeout is not cancellation of a committed operation.

Connection references belong to the cache, synchronous policy scopes, unpublished
native births and bounded operation windows. Deferred completion therefore cannot
use a connection already evicted by another task's mount traversal. The last
reference releases SQLite and native descriptors only after transaction/gate
retirement. These worker-local references do not retain caller policy or permit
concurrent database access; shareable backing custody remains a separate owner.
The mounted-store LRU targets 16 connections, skipping active references. Nested
scopes and in-flight births can temporarily exceed that target; pruning/cache
misses trim idle excess. Cache capacity is not a guest `EMFILE` condition.

`inode_db.c` owns transactions, allocation, identities and metadata queries;
`inode_schema.c` owns creation, format/revision admission, metadata upgrades,
connection journal policy and rebuildable lookup indexes;
`inode_path.c` resolves paths and reconstructs directory paths;
`inode_path_cache.c` retains bounded directory-prefix routes and revalidates
their ancestors against current caller and mount policy. Routes share the
metadata cache's exact ancestor-object and parent/name dependencies; unrelated
row writes do not flush every route. Rollback and foreign commits invalidate
the snapshot as before;
`inode_cache.c` owns bounded metadata caching and row-dependency invalidation;
`inode_store.c` defines namespace operations, `inode_directory.c` implements
directory cursors, `inode_socket.c` implements socket publication and address
lookup, `inode_metadata.c` owns guest permissions and metadata mutations, and
`inode_acl.c` and `inode_capability.c` own logical security attributes;
`inode_import.c` imports prepared trees. `inode_snapshot.c` owns
immutable-source snapshots; `inode_backing.c` owns source validation and copy-up.
`inode_reserve.c` owns durable allocation inventory; `inode_durability.c` owns
native data barriers and the metadata checkpoint boundary.
`inode_publication.c` owns recoverable native-leaf promotion and the shared
pending-publication hint used by independently running owners.
`inode_native.c` owns native regular-leaf creation and sparse logical metadata,
including retained O_PATH and open-unlinked descriptions.
`inode_leaf.c` connects those leaves to path/FD resolution, admission and
materialization at shared-source boundaries. Native leaf nodes are not cached.
`inode_overlay_native.c` owns writable-upper eligibility and atomic publication
of shared native leaves into their upper/merged catalogue projections.
`inode_view.c` owns retained namespace projections within one store transaction
domain; `inode_maintenance.c` collects untracked backing objects only under
exclusive maintenance or before a store is published.
Their private contract is in
`inode_internal.h`. Callers use the explicit dirfd-based `inode_store.h` API.
Format 17 uses WAL and includes durable allocation inventory,
scoped namespace views, logical owners/modes, POSIX ACLs, file capabilities,
unambiguous file-path history, stable identities, backing history,
immutable sources, socket addresses, FIFO ownership, device type/rdev, transactional namespace
counters and the [watch event journal](watches.md). Formats before 17 require a separately prepared store;
opening one never rewrites or deletes it. Bind retains a name until
unlink, including stale listeners; existing connections survive unlink/rebind.

## Storage Evolution

Format 17 is the compatibility baseline. SQLite `application_id=0x53485254`
identifies Shroot, `user_version=17` identifies the storage family, and the
`properties` row `schema-revision` identifies the metadata revision (initially 1,
currently 4). Revision 2 adds native-publication intents and large directory-cookie
mappings. Revision 3 replaces fixed overlay-attribute slots with named logical
xattrs, preserving existing overlay values and tombstones. Guest `trusted.*`
attributes use the same inode identity and guest capability checks; they do not
require Android trusted-xattr permissions. Revision 4 adds stable directory-entry
identities for exact descriptor paths, backfilled from the existing namespace.
These revisions do not move installed
data or change image import identity.
Admission validates those markers before current-schema namespace queries or
journal changes. Unknown families, missing markers and newer revisions fail;
there is no reader/writer implementation for formats 14-16 or automatic deletion.

Ordinary admission does not run `integrity_check`, `quick_check`,
`foreign_key_check`, a namespace-wide audit or file-content hashing. Full audit
is an explicit diagnostic/test operation, not a launch prerequisite. SQLite may
check affected tables internally during a schema change; that belongs to the
one-time upgrade, not to normal opens or filesystem operations.

Append metadata-only SQL steps to `mdi_schema_current` when the schema evolves;
each step and its next revision commit together with full synchronization. Update
the current creation SQL in the same change and verify that fresh and upgraded
stores have equivalent schemas. Fresh stores publish the current revision in
their creation transaction; no old-schema runtime is needed to initialize a root.
Upgrade SQL cannot control transactions, journal pragmas or attached databases.
Never include native backing IO in a step. Changes requiring backing
transformation need a separately reviewed recovery protocol, not an unsafe SQL
callback. Keep fixtures for every supported revision from this baseline onward.
The revision admits the complete persistent interpretation, not just SQL DDL:
changing a native metadata encoding or source-reference meaning also requires
explicit admission. Do not add independent feature flags or version counters
for runtime optimizations. Frozen SQL and native-metadata literals live in
`guest-exec-lab/fixtures/storage/format17.h`; they are not generated from the
current writer. New revisions append fixtures instead of changing old ones.

Ordinary cold admission prepares supported revisions before exposing the store.
An update converts the root lifetime lease to exclusive, nonblocking admission;
another process, retained origin or dependent store holding the lease produces
EBUSY. Failed flock conversion may drop the shared lease, so failure closes the
unpublished connection rather than returning a partially admitted owner. Retained
worker reconnects and `md_inode_store_open_inspect` cannot update. Inspection also
skips index preparation. There is no per-syscall schema branch or update check.

WAL configuration is a storage policy, not a schema revision. Conversion from a
rollback journal uses the same exclusive admission but occurs outside the schema
transaction; either journal mode with the prior revision is a valid retry state.
Committed steps are not replayed after interruption; failed steps roll back their
revision with their metadata. Checkpoint and directory-sync errors propagate.
This recovery contract is tested with process death and injected errors, not a
claim of physical power-loss testing. Concurrent preparation reports busy instead
of racing another owner. Current WAL/revision stores retain shared admission.

Guest names, stable object IDs, ownership/ACLs, file-path ambiguity history,
backing/source references, allocation inventory and namespace counters are
authoritative metadata. In particular, inventory and backing history are not
discardable caches: collection uses them to decide which native bodies survive.
Lookup indexes are rebuildable and do not require a schema bump. Connection-local
query/metadata caches and checkpoint thresholds are runtime details. Image import
identity is independent as documented in [images](images.md#commands).

Native leaves are persistent data too. Their names and contents live inside the
catalogued parent backing. Ordinary eligible root-owned leaves use native mode;
exceptional ownership, mode, ACL or capability metadata uses physical mode
`01700` and `user.shroot.metadata`. Its `SLF2` header is six little-endian uint32
fields: magic `0x32464c53`, UID, GID, mode, ACL byte count, capability byte count,
followed by the ACL and capability payloads. It is not a native C ABI dump.
Private overlay xattrs are also authoritative until materialized into the
catalogue. Copying only SQLite or stripping these xattrs loses guest state;
use the portable backup owner for transfer between devices.

Recovery records such as `native_publication` must survive interruption.
`publication-v1.state` is a shared pending-work hint reconstructed from the
journal, not an alternative source of truth. Locks, live owners and descriptor
caches are runtime state; do not delete their files under active participants.
These distinctions do not introduce additional checks on ordinary syscalls.

An upgrade must retain root/source directory identities, guest contents and
existing instance paths. Forward upgrades are the supported direction; an older
runtime rejects a newer revision rather than guessing compatibility. Storage
compatibility does not require preserving obsolete internal APIs or wire formats.

### Future Offline Snapshots

The existing immutable-source/COW mechanism is the storage foundation, not a
user snapshot feature. Object IDs and logical hardlinks are independent of native
bodies. Cloning a sealed store retains direct source references, including when
the source was itself a clone; a read never walks a history chain. Each view still
has at most 255 external source directories. This is not unlimited history.

The named-environment catalog separates stable environment identity from its
working-store key. A future offline restore can publish a prepared working store
and atomically replace that reference without renaming shared source directories
or changing application identities. It must first establish exclusive ownership,
keep both old and new dependencies until publication is resolved, and refresh
cached launch plans. Existing launches retain their captured store; changing a
catalog record cannot retarget them. No switching command, snapshot catalog,
process checkpoint or cross-store transaction engine is implemented now.

Each retained view selects its own root and physical-to-logical inode mapping.
Different views can share a native body without aliasing their logical metadata;
copy-up retains old descriptor identity within the originating view. Roots in
the same store share one SQLite transaction and one short-lived object-directory
gate. There is no second database journal for projection publication.
Projection roots carry independent kernel leases through local references and
SCM custody. Collection removes an unleased projection's metadata; native bodies
remain while other stores may retain source leases. Exclusive maintenance
collects bodies only after metadata retirement has committed and checkpointed. Normal open/stat
does not run collection or allocate a view selector. Writable overlay uses a
connection-local mutation journal to publish its merged and upper names within
the same transaction. Upper/work reservations remain held by detached views
until their last descriptor or origin releases custody.
The initial root backing owns that lease for the entire view lifetime; selecting
an upper directory body does not move it. Validation resolves the retained lease
through backing history, while collection uses its original scope basename.
Each view has its own bounded lower-source catalogue. Retiring a view releases
that catalogue instead of consuming source slots in the persistent instance.
Upper/work reservations share the metadata transaction and retained projection
lease. A dependent view retains its referenced parent metadata; collection
retires children before parents. Reconnection and object-ID access preserve the
selected scope, including after sender closure and queued SCM transfer.
Collection snapshots upper/work and copy-up dependencies once per writer
transaction. An indexed leaf queue releases only the deleted child's direct
parents; a busy leaf keeps its dependencies and is not repeatedly retried.
The temporary graph is discarded before commit. Ordinary file operations do not
build it. Scaling tests cover long mixed dependency chains and retained children.

Overlay whiteout/opaque attributes are transactional metadata, including explicit
tombstones that mask native attributes. Ordinary xattr operations and logical
ACL/capability attributes execute through the same typed inode operation; backing
FDs do not escape for a second, detached xattr mutation. Overlay views hide private
control attributes. Directory attributes come from the selected layer.
Character-device `0:0` whiteouts and regular-file whiteout xattrs use the same
merge policy. They hide lower names and are not exposed by merged enumeration.

An overlay retains the mounting identity, supplementary groups and owning user
namespace independently of the mounting task. Access checks apply both caller
DAC/ACL and the saved identity's underlying-layer permissions. Copy-up requires
read access to the lower body; upper-name changes check directory and sticky-bit
permissions before publication. Metadata, ACL and capability changes use the same
typed authorization helpers as ordinary stores. A sealed credential snapshot
travels with cold custody transfers and reconnects; ordinary file IO does not
serialize credentials or allocate an identity. Native SELinux checks remain in
force. This is not kernel-equivalent confinement of unadapted syscalls.

FIFO and pathname-socket channels have identities separate from a view's logical
inode. Copy-up and remount retain the channel, including a lower from another
store. FIFO pin custody and rendezvous remain with the creating store; projected
opens publish their local inode association without holding two store transactions.
Attached FIFO custody borrows its store's lifetime. Only detached custody retains
an independent lease, so an idle connection cannot keep its own overlay mounted.
Snapshots create fresh FIFO channels. Regular files do not use channel lookup.
Successful upper-name publication emits the same committed directory events as
ordinary inode operations; rollback does not notify an uncommitted name.

Character/block devices use regular metadata carriers, never host `mknod`.
Guest CAP_MKNOD authorizes creation; ordinary permissions, Landlock and mount
policy apply. Snapshot, overlay copy-up, hardlinks and archive backup retain
type/rdev. Metadata refers to the logical carrier even after data open returns
a native endpoint. A retained origin carries both identities through dup,
proc-FD reopen and cold SCM transfer, including after unlink or sender exit.
`file_open` maps supported character-device numbers to explicit Android
endpoints: null, zero, full, random, urandom and the caller's controlling tty.
Unknown devices return ENXIO on data open; O_PATH and stat remain available.
Native access denials remain errors, and nodev denies new data opens without
invalidating existing IO. Readonly mounts protect metadata, not device IO.

## Native Publication

Eligible guest creation uses native leaves by default, without an experimental
flag. The parent must be attached, local, have
virtual permissions and no default ACL. The store probes user-xattr metadata on
an unnamed temporary file during admission/reconnection; failure retains the
catalogue allocator. Inspection does not probe or mutate. Trusted tree imports
using physical permissions retain their previous allocation path; OCI metadata
and directories created with guest credentials use virtual permissions.

An eligible private regular leaf (logical UID/GID zero, owner-readable/writable,
no sticky or set-ID bits) uses its native name and mode. Create, stat, ordinary
chmod, rename and unlink need no SQL writes, metadata xattr or forced synchronization.
Other ownership/modes use the same native-name representation with sparse
metadata. Creation prepares a private backing, installs its selector and complete
metadata, syncs it once, then atomically publishes the name with
`RENAME_NOREPLACE`. Interrupted preparation leaves only an untracked backing for
existing maintenance to collect, never a visible file with incomplete permissions.
These creates also need no SQL writes. Parents with default ACLs or physical
permissions still use the catalogue allocator.

Writable overlays share admitted upper-directory bodies with their merged
projection. `inode_overlay_native.c` admits native births and renames only when
that upper ownership is already published and the name has no lower-name or
catalogued-upper conflict. Lower-only directories, whiteout replacement and
copy-up retain transactional catalogue publication. Ordinary upper-only native
create/rename/unlink changes the single physical name, visible through both
projections, without per-file SQL writes. External mutation of a mounted upper
is not a coherent alternative interface to the merged namespace.
This is a storage mechanism, not a permission bypass: the provider must resolve the parent and enforce
identity, ACL, mount, readonly and Landlock policy under the existing store gate.
The executor's process umask is zero; creation receives the already-masked guest
mode. These helpers must never interpret arbitrary native-bind files as leaves.

Exceptional ownership/modes, access ACLs and file capabilities use a fixed,
little-endian `user.shroot.metadata` record. Physical mode 01700 is its selector,
not guest permission or a kernel
set-ID grant. The first transition writes and syncs the **old** logical state,
switches the selector, then atomically replaces the record with the new state.
Interruption/failure leaves old or new logical permissions, never widened backing
permissions interpreted as guest state. Later record updates require no barrier.
Returning to an eligible root-owned state is one native chmod; the old xattr
becomes inert, so subsequent stat again avoids xattr lookup. Missing or malformed
selected metadata fails explicitly. This requires usable native user xattrs before
enabling leaf creation in a store; capability denial must not be inferred away.

Metadata operations use the retained FD, with its proc magic link for O_PATH,
including after rename/unlink. They never reopen the former guest pathname.
Common records are read once into a bounded local buffer; larger ACL records
use a heap buffer after a size query. Every read validates the complete record,
and no native-leaf metadata or permission result is retained between operations.
The shared xattr boundary hides/rejects the reserved `user.shroot.` namespace.
Attribute copying for COW and overlay does not import a source's private record
or erase a destination's record. Portable backups export logical ownership and
permissions, omit private records and reject forged private records on restore.

Promotion retains the same physical regular inode, including existing FDs,
mappings, locks and data. The caller borrows the existing store gate and must
have one unchanged operation snapshot. The owner syncs the native body and
parent, commits an intent with FULL WAL synchronization, moves the body with
`RENAME_NOREPLACE`, syncs both directories, then publishes its object/name rows
and removes the intent in one FULL transaction. It resumes the original
operation snapshot without releasing the gate. Ownership and mode are read from
the validated leaf by the metadata owner, not supplied by its caller.
Promotion changes representation,
not the guest namespace: the enclosing operation owns its logical events.
Guest mount/superblock read-only policy is separate from sealed-store admission.
Exec and watch registration can publish a leaf in a writable backing store even
through a read-only guest view. Ordinary mutations still reject that view with
`EROFS`; sealed sources and inspection handles never admit publication.

Native identities are scope-qualified; root-scope identities are unchanged.
For shared upper/merged directories, the same promotion transaction publishes
each scope's object and name and their overlay link before deleting the intent.
Recovery derives these projections from retained directory ownership, not a
runtime-only writer callback. Open FDs and Landlock keys keep their original
scope-qualified identity. No second journal or storage revision is needed.

Hardlinks, mixed native/catalogue rename, pathname-based execution and attached
file-watch registration promote before consuming catalogue identity.
`O_PATH|O_NOFOLLOW` delivery reuses an existing stable catalogue source when
available. Without an application seccomp filter, native leaves retain exact-FD
transfer: opening a metadata handle neither exports a mutable pathname nor
promotes or synchronizes the file. Restricted callers retain durable source
publication, avoiding an additional `recvmsg` requirement. Filter installation
drains admitted operations before committing the new restriction. Unlinked native
FDs and sealed stores that cannot publish retain exact-descriptor transfer.
Sealing, backup and
overlay admission materialize the selected namespace before existing SQL-based
snapshot/COW logic runs. Moving into a physical-permission directory promotes too.
Whole-scope and subtree materialization collect directory identities once, close
the SQL cursor, then promote leaves. Repeating a filtered, sorted `LIMIT 1`
query per directory can rescan the scope and must not replace this snapshot.
No cursor crosses the promotion recovery journal's transaction boundary.
The source is not copied and no kernel hardlink permission is needed. Open native
FDs resolve through their actual proc alias, including deletion; after promotion
they resolve through the catalogue's device/inode association. Landlock file rules
pin the original FD so host inode-number reuse cannot reassign a grant.

For staged births, the watch owner pairs the move out of the backing root with
the exact kernel cookie at the guest parent and emits CREATE then OPEN, not a
guest rename. Subsequent data/close events follow the retained inode. Ordinary
native renames keep their original paired move events. No per-file SQL journal
entry or publication-marker transaction is needed for native creation.

Descriptor execution retains the exact image origin instead of promoting a name.
The filesystem owner rechecks logical execute permission, Landlock and mount
policy, including for an unlinked image with sparse metadata. The existing
process-owned origin survives CLOEXEC, fork and `/proc/self/exe` reexec; local
and foreign `/proc/PID/exe` reads use the same captured origin. Reexec or final
process release retires it. This adds no permanent native-object registry or
retained descriptor to unrelated files. Anonymous memfd execution keeps its
separate kernel-image owner and cannot substitute for failed managed admission.

`publication-v1.state` is a four-byte, shared atomic hint, not authoritative
storage. It is set before the intent can commit and cleared only after catalogue
publication. Existing owners inspect that memory under the store gate before
starting a transaction; an interrupted writer therefore cannot leave another
live owner reading a half-published namespace. The ordinary path adds no journal
query, file probe, sync or descriptor allocation. Cold admission always checks
the durable intent, independently of the hint's persisted contents. Inspection
reports EBUSY instead of completing a pending mutation.

Recovery checks the expected source/destination identities and completes the
same representation transition. Conflicting files are not overwritten, copied
or discarded. A pending IO failure remains an error until recovery succeeds;
it is not permission to replay the guest syscall. FULL synchronization applies
only to exceptional publication; normal connections retain their existing
NORMAL/temporary-OFF policy. This is not a second general-purpose namespace
database or a per-syscall schema migration.

`test_publication`, included in `test_resources.py BUILD --case stores`, covers
retained FD/mmap/flock identity, hardlinks and unlink, transition into the
existing shared-source snapshot/COW path, cold and already-open recovery,
process death during recovery itself, sync/move/catalogue failures, conflicting
native identity, admission rejection and zero steady-state journal queries.
Sixteen additional overlay SIGKILL cases cross four publication boundaries,
upper/merged initiation and live/cold recovery; they verify retained identities,
both projections and absence of false namespace events.
`test_native_leaf` covers 1024 ordinary native cycles with zero xattr/sync calls,
FD/mmap identity after deletion and pathname reuse, O_PATH metadata, returning to
implicit metadata, four SIGKILL points and four IO-failure points in the first
metadata transition, malformed records, native namespace events, promotion and
attribute-copy filtering. The stores runner also executes real backup/restore
and checks ownership, visible user attributes and absence of private records.
The integrated fixture also checks zero-write production namespace cycles, mixed
catalogue/native enumeration and notifications, CREATE/OPEN/CLOSE order, cold
descriptor recovery, ACL/capability promotion, Landlock pin lifetime, sealing,
snapshot COW, overlay lower/upper admission, simultaneous upper/merged native
watchers and paired rename cookies. These are not power-loss tests or
proof of complete mixed watch semantics; see the [watch limits](watches.md#limits).

## Paths And Directories

Absolute paths start at the model's root; relative paths use an explicit root
sentinel or a real backing-directory FD. Native descriptor duplication and
fork/exec inheritance preserve identity. Directory moves update the namespace
parent edge, so an already-open FD resolves `..` and its reconstructed path at
the new location. There is no process-global emulated cwd or cached path string.

The inode and mount walkers share the logical component lookup primitive.
Within one mount segment, the latter retains inode points and one read snapshot;
intermediate components do not allocate native descriptors. Native providers
retain their actual descriptors, while final parent/object results are pinned
before releasing the snapshot. Points retain the selected view and mount context;
native identity alone cannot choose a bind alias. Crossing a provider releases
the old transaction before entering another, avoiding cross-store lock ordering.

Read-only `stat`, `readlink` and access queries complete against the selected
inode inside that snapshot, without pinning final parent/object descriptors or
resolving the leaf again. Mount policy, logical metadata, executable-catalogue
projection and caller credentials remain authoritative. Operations returning
objects or changing names still retain their normal descriptor/dentry custody.

Base-store paths use the inode walker even after dynamic mounts. A compact
native-identity filter, derived from the mount placement index, rejects only
possible crossings; exact lookup confirms each hit. A confirmed crossing
restarts through the namespace walker before mutation. Publication, move and
detach invalidate the same index; there is no separate fixed mount-count limit
or persisted path cache. Short single-store operation windows remain available
after local topology changes. Before falling back to another mount/provider,
the inode owner revokes gate retention; subsequent scopes acquire/release their
own gates until the executor ends the window. Foreign topology imports and
restricted roots keep independent admission. No launch-topology snapshot or
cross-store lock retention is required.

A bounded, connection-local prefix cache remembers up to 128 resolved directory
routes, with at most 32 nodes per route. Catalogue mutation, rollback and an
external data-version change retire routes. Every reuse rechecks ancestor search
permissions, host-provider boundaries and exact current mount placement. The
root and starting directory identity are part of the key. Terminal entries and
native attributes are never cached here; symlink traversal, dot components,
constrained walks and deeper suffixes continue through the ordinary resolver.

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

Managed opens capture a stable `dentry_paths` identity in their descriptor
anchor. Rename moves that identity, including exchanges; unlink retains a
tombstone until the inode is collected. Descriptor readlink follows parent
renames and marks the selected name deleted even if another hardlink survives.
Reopening, fork/exec and descriptor transfer retain the same origin. Native
leaves need no catalogue entry until promotion; their original name then becomes
the first managed entry. The existing versioned cache covers entry selection.
`file_paths` remains the fallback for descriptors without an observed open;
ambiguous hardlink history still returns ENOTSUP there instead of guessing.

POSIX access/default ACLs are canonical Linux xattr values in `inode_acls`, shared
by every alias and copied with image metadata. Native leaves keep access ACLs in
their private metadata record until promotion. A transactional `acl_mask` on the
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
numbers and types, `.`/`..`, and opaque cookies. Committed catalogue name cookies
are not reused after deletion. Enumeration uses an indexed database read snapshot
per batch; changes between batches have ordinary non-snapshot traversal semantics.
An undersized first-record buffer fails without advancing the cursor.
Enumeration visits catalogue names, then native leaves using a separate temporary
directory description. Large host cookies use persistent store-wide mappings;
small cookies encode directly. Published offsets stay below INT32_MAX for F2FS
directory-seek compatibility; exhausted cookie ranges fail with EOVERFLOW. These
mappings preserve saved positions across reconnect and cold SCM transfer, rather
than keeping a process-local iterator. Native-phase order is filesystem-defined.
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
FIFO metadata is preserved without copying pipe data. Other special files,
set-ID bits without preservation, cross-device traversal
and xattrs other than the kernel-assigned SELinux label are rejected explicitly.
SELinux labels are not copied or changed. Overlapping source/storage trees and
nonempty destination namespaces are rejected. No fallback clears unsupported
metadata or claims it was preserved.

Native objects and data are synced before namespace commit. Failure before commit
leaves no published partial tree, but may retain untracked objects. An uncertain
commit requires reopen/inspection before retry. Exclusive maintenance can collect
untracked physical allocations; byte limits bound the current attempt, not
accumulated failed attempts while the store remains active.
Import is not execution from the new namespace or successful package installation.

## Transactions

Sealed images, writable instances and copy-up retain these transaction boundaries;
their [backing and lifetime contract](images.md#sharing-and-copy-on-write) is
independent of OCI parsing and launch-local directory attachments.

- Each connection reuses compiled inode, descriptor, name and directory queries,
  plus BEGIN IMMEDIATE/COMMIT/ROLLBACK programs. Every use resets the statement and clears all bindings
  before leaving the operation. A bounded metadata cache is admitted against
  SQLite's current data version; no read transaction survives the operation.
  The SQLite page cache has a demand-grown 8 MiB target per connection; this
  avoids repeatedly reading index pages during broad directory walks without
  reserving that memory for small stores. It is separate from the bounded row
  cache and does not change schema or invalidation semantics. Reverse directory
  lookup uses the object index, not a scan over the parent's children. Mount
  identity from a selected view does not require reconstructing the pathname.
  The exclusive store gate, not a read-only SQLite transaction, keeps catalogue
  observations consistent across the operation. Read scopes and native-name
  create/rename/unlink therefore issue no BEGIN/COMMIT. Catalogue mutation admits
  BEGIN IMMEDIATE before its first write; rollback and durable promotion keep
  their existing atomicity. The version check and interprocess gate are retained.
  Local preupdate hooks invalidate dependent object/name/backing/path rows,
  including trigger changes, without running SQL from the hook. The 4096-entry
  cache separates descriptor and pathname sets. Dependency-version buckets
  make invalidation independent of capacity; hash collisions only discard
  additional entries. A parent attribute change does not discard unchanged
  sibling names. External invalidation advances an epoch without clearing the
  entire allocation. Rollback of
  mutations discards speculative records; read-only failure does not clear them.
  A versioned empty-catalogue fact lets native-name directories skip per-name
  SQL absence lookups. It does not cache native directory contents or imply
  that the directory itself is empty. Name-row hooks, external data-version
  changes and rollback retire that fact before hardlink/overlay publication
  can make a catalogue entry visible.
  Other writers invalidate the cache on the next admission. Native attributes
  and permission decisions are not cached.
  Within one locked operation/window, permission checks may reuse validation of
  logical directory physical identities in a 256-entry set-associative table.
  This avoids repeated
  backing `stat` calls during path search, parent-write and sticky checks.
  Every check still evaluates the current node and caller; ACL, Landlock and
  overlay policy remain independent. Admission and promotion to a catalogue
  write retire this validation set. Leased source directories follow the same
  backing-identity contract. Native leaves, retained
  descriptors, physical-permission directories and user-visible stat bypass it.
  No permission result or physical attribute snapshot is retained.
  Resolved nodes in the current view also prime the descriptor index by backing
  device/inode, avoiding a second SQL lookup after open. Actual FD identity and
  native attributes still come from the kernel; old copy-up backings retain their
  independent lookup. Cache insertion replaces existing keys rather than
  consuming another slot for an alias.
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
  Final native-leaf resolution returns its fresh `fstatat` observation to stat,
  avoiding a second lookup of the same pathname. The resolved parent is passed
  to leaf lookup rather than fetched again. All stat paths share the logical
  attribute projection; native data attributes are never cached across calls.
  Fixed-width leaf-ID encoding preserves the existing format-17 bytes. Word-wise
  hashing selects connection-local cache buckets only; full keys still decide
  identity, with unchanged generation checks and invalidation.
- Image opening returns one retained FD, its object identity and canonical dentry
  path under one store admission. Ordinary opens do not reconstruct unused paths.
  Reconstructing a directory path uses versioned parent-name records.
- Ordinary writable opens retain read admission when no namespace or backing
  change is needed. Creation, copy-up and overlay publication promote that
  sole snapshot under its retained exclusive store gate. Nested read snapshots
  cannot promote. No pathname is re-resolved and no mutation is replayed.
- Catalogue-backed guest regular-file creation claims a preallocated backing from a batch of 32.
  All backing files and their containing directory are synced before inventory
  rows enter the WAL. Claim removal and logical-object publication share one
  transaction. No live or unlinked object is recycled. A claim restored by loss
  of unsynced WAL has its data, guest xattrs and timestamps reset before reuse;
  the host SELinux label remains untouched. Final native metadata is synced
  before publication. Failed refills leave only unreachable objects.
  Active directory watches retain the single-open allocation path, avoiding
  exposure of internal preparation events. Trusted operations without virtual
  credentials preserve native umask semantics and also allocate directly.
  Other types sync their containing directory before publishing a reference.
  Snapshots do not copy a source's allocation inventory.
- Overlay construction batches synchronization of newly allocated entries in
  the shared backing directory within one writer transaction. The directory
  barrier must succeed before commit; failure rolls back namespace publication.
  Native-leaf promotion precedes this batch, and individual object/data barriers
  are unchanged. Source registrations are reused only within that merge under
  the retained source lease, not cached across mounts or guest operations.
- Catalogue link, unlink, rename, replacement and exchange each use one database transaction.
  Names are byte strings, not UTF-8 text or concatenated SQL. A same-inode rename
  preserves both names; NOREPLACE rejects an existing target.
  Directory moves/exchanges change parent edges in the same transaction as names.
  Guest namespace changes also update the physical parent's mtime/ctime while
  retaining its atime, independently of watcher registration. Native regular-leaf
  operations keep the kernel's timestamp updates. Import and representation-only
  promotion preserve directory times; snapshot directories remain private.
  Backing times, like explicit utimens and data IO, are not rolled back by SQLite.
  Watch projection suppresses these internal directory attribute notifications;
  explicit guest attribute changes still publish their logical events.
- Eligible native creation, rename and unlink use kernel namespace operations
  under the same gate and a read snapshot, without catalogue writes. Native
  file fsync synchronizes the body; durability of its directory entry requires
  directory fsync or syncfs, as with ordinary Linux files. Native metadata/data
  changes are not rolled back by a later SQLite/read-delivery failure. Uncertain
  outcomes must not be replayed automatically.
- Reads resolve names in a snapshot. They cannot see half an exchange. Contents
  are not transactional: writes through returned FDs follow kernel IO semantics.
- Store opening, connection close and each metadata operation take an exclusive kernel `flock`
  on their private object-directory description before entering SQLite.
  Independent services for one store therefore serialize metadata, including
  reads; separate stores do not share a lock. The gate is released after commit
  or rollback and by the kernel when its owner dies. It adds no per-request heap
  allocation and is not held during RPC delivery, guest execution or ordinary
  IO through returned file descriptors. Connections must still be reopened after
  fork; an inherited description would share the lock owner.
- An independent shared root-directory lease retains the store's lifetime.
  Immutable source directories retain parent-root leases as well. Exclusive
  offline maintenance fails with EBUSY while a cooperating owner is active;
  namespace transactions keep their separate short-lived gate.
- Lock admission waits for kernel release, without polling or a settling delay.
  A caught signal can fail admission before the transaction starts. Kernel IO
  and lock waiting do not promise bounded service completion; the caller's RPC
  deadline bounds observation, not cancellation of an accepted request. Offline
  import must not contend with live clients.
- Connections use SQLite WAL with `synchronous=NORMAL` for persistent stores.
  Each operation commits atomic visibility, not a synchronous storage barrier.
  Ordinary unsynced operations may be lost after power failure. Automatic
  checkpoints start at 1024 pages, with a 4 MiB retained-WAL limit on reset. A large transaction or pinned
  external reader can exceed that threshold; it is not a storage quota.
  Temporary mounts retain transactional rollback but use `synchronous=OFF`.
  Watch wakeups observe the WAL, not checkpoint writes to the main database.
  Last-connection checkpoint/sidecar removal uses the same admission gate as
  opening and ordinary operations. SQLite's Unix VFS may retain closed main-DB
  FDs while a same-process peer holds POSIX locks; subsequent opens reuse this
  bounded concurrency high-water mark, and last-owner close releases it.
  Unsupported storage families/revisions are rejected; supported metadata and
  journal changes use the offline admission described above.
  No SQLite busy timeout or
  automatic replay is installed. An external SQLite writer bypassing the store
  gate still returns EAGAIN. An IO error during commit can have an unknown
  outcome: inspect before replaying a mutation.
- `fsync` and `fdatasync` first synchronize the actual data FD, then checkpoint
  committed metadata under the store gate. `syncfs` synchronizes backing storage
  before the same metadata barrier. The SQLite transaction ends before the full
  checkpoint, while the gate remains held. Other connections' committed metadata
  is included. Open-unlinked FDs and mounted/overlay stores retain their selected
  identity; O_PATH and invalid descriptors are rejected. Native attachments
  retain native barrier semantics. O_SYNC/O_DSYNC creation and reopening publish
  a metadata barrier before returning the data FD; subsequent data IO stays
  native. Imports, snapshot publication, sealing and exclusive metadata retirement
  also request explicit barriers. Data or checkpoint errors propagate; they are
  not converted into successful durability acknowledgements.
- Detached objects and uncommitted allocations are retained and audited separately.
  There is no live garbage collector for ordinary detached objects. Removing metadata while another process
  holds an FD would break descriptor lookup. Long-lived stores can therefore
  accumulate unreachable backing data; live reclamation remains unsupported.

SQLite supplies database transactions, not Linux filesystem semantics. References:
[atomic commit](https://www.sqlite.org/atomiccommit.html),
[isolation](https://www.sqlite.org/isolation.html),
[WAL synchronization](https://www.sqlite.org/pragma.html#pragma_synchronous),
[checkpoint completion](https://www.sqlite.org/c3ref/wal_checkpoint_v2.html),
[kernel lock ownership](https://man7.org/linux/man-pages/man2/flock.2.html), and
[Linux rename](https://man7.org/linux/man-pages/man2/rename.2.html).

## Checks

`test_schema` drives real cold admission with prospective revision steps: stable
file/hardlink identity, inspection and downgrade rejection, busy live owners,
concurrent updates, injected ENOSPC, and SIGKILL before/after commits including
rollback-to-WAL preparation. It rejects transaction/journal control in upgrade
SQL and checks dependent source leases, shared bodies and COW across revisions.
Ordinary concurrent admission waits for the current upgrade; exclusive
maintenance fails busy before waiting for the operation gate.
An authorizer fixture rejects full-audit pragmas during ordinary admission,
inspection, retained reconnects and file operations. File operations must not
reenter schema admission. `test_oci.py` checks unsupported families, wrong application
identity, unknown revisions, journal preparation and rebuildable indexes along
with the image/snapshot/backup workflows. These tests do not add old-format support.

`test_durability` checks data and WAL failures, directory-sync failure before
reserve publication, concurrent reserve claims, watch-event transparency,
hardlinks/rename/unlinked descriptors and snapshot-local inventory. A modeled
power-loss test kills a writer and discards uncheckpointed WAL, checking both
recovery to the old namespace and preservation after an explicit barrier.
This is not a physical power-cut test. `durability_guest` exercises actual syscall
routing with plain and nondumpable/filtered callers, tmpfs, overlay remounts and
native attachments. `test_resources.py BUILD --case durability` runs both.

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
- Existing writable opens admit no metadata write; unrelated local mutations
  preserve warmed paths. Same-transaction negative-name and trigger-count reads,
  rollback and nested-promotion rejection validate cache publication.
- The mounted-path fixture covers 24 directory depths with two final descriptor
  materializations and depth-independent transaction admission, plus native
  crossings, symlinks, error cleanup and another connection's parent rename.
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
  WAL. Reopening checks names, inode identity, database integrity,
  foreign keys and orphan accounting.
- WAL journals keep their inode across alternating writers and repeated automatic
  checkpoints. Small commits reuse bounded space; a logical hardlink wakes a
  watcher before checkpointing. Repeated connections retain a bounded native-FD
  high-water mark and release all descriptors after the final owner closes.
  Reopening audits the namespace; a foreign rollback-journal store is rejected without
  changing its journal mode.
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
copies. It requires another host with native hard-link support. FIFO import
is covered on the Termux host; shell policy blocks creating that source fixture.
Guest FIFO names and transport use the [named-pipe contract](fifos.md).
Directory RPC paging, inherited/shared cursors, concurrent readers, lost replies
and service restart are covered in `test_rpc.c`.

## Limits

Namespace-induced ctime updates, complete set-ID/file-capability clearing on
native data writes,
exact hardlink-dentry data notifications, live import/promotion, unreachable-object
reclamation and power-loss/out-of-space recovery remain incomplete or unvalidated.
RPC descriptor transfer does not supply storage reclamation policy.
Host ownership stays the real executor identity; guest ownership is logical.

Installed distributions, package-manager transactions and OCI image workflows
use this same namespace. Their [coverage](../../docs/shroot.md#coverage-and-limits)
does not imply a complete Linux ABI or filesystem isolation. Direct-backend
hardlink controls retain the host's independent restrictions. SQLite stays in
native namespace owners, outside guest execution.
