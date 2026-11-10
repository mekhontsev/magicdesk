# Mount Views And Path Origins

[Native architecture](README.md) | [Resource model](process-resources.md)

Attachment ownership, portable mounts and guest-root resolution.

- [Mount Views](#mount-views)
- [Path Origins](#path-origins)
- [Guest Roots](#guest-roots)

## Mount Views

`mount_namespace` owns the attachment tree, retained native objects and native
mount-ID mapping. Tasks, registry entries and in-flight snapshots hold independent
references. Fork and threads retain the same view, regardless of CLONE_FS;
unsharing cwd/root or semaphore undo does not split it. Nonleader exec transfers
the executing task's reference with its other resources. Final release closes
retained descriptors outside the registry lock.
Mount rollback, slot retirement and namespace destruction use
`md_view_mount_close` for descriptor, policy and provider ownership. Closing a
partial instance leaves an empty detached slot while preserving its generation.

The filesystem worker builds the initial view before acknowledging readiness,
and remains the sole executor of path resolution and object registration. The
supervisor only manages references; it never reads the mutable object cache.
Registry snapshots stay valid after task replacement or removal. Dispatch selects
that snapshot's mount view and restores its previous borrowed context on failure
as well as success. A root from another view is rejected as stale.

An empty view allocates only its owner, not attachment arrays or object indexes;
requests without attachments retain the direct inode-engine path. Ordinary
requests acquire references without heap allocation, extra RPC or syscall stops.
`md_fs_mounts_copy` constructs an unpublished candidate on the filesystem worker.
It copies source/covered O_PATH references, parent relationships and per-mount
policy, then explicitly rebases the selected root. Inode roots retain their
backend identity; native roots receive a location in the new tree. A location
from the original native tree is stale in the copy. Neither registry publication
nor the original context changes on failure, and partial candidates release all
acquired descriptors.

Mount IDs identify instances, not attachment indexes. Root, shared-memory,
attachment and admitted native mounts use one allocator per owner process; IDs
are not reused, and exhaustion fails explicitly. statx and mount-table publication
use the same mapping. Copies have distinct IDs and independent lazy native-ID
maps. The object cache is not copied: only the selected native root is registered,
so copy cost depends on mount count, not the number of previously opened files.
Only O_PATH references are retained; FIFO reader/writer counts and flock/OFD-lock
lifetime remain with application descriptions.
Captured native opens retain their own anchor instead of registering a second,
view-lifetime object pin. Explicit object/image IDs and root locations register
lazily; anonymous transports still register because they carry no anchor.
Closing ordinary captured opens therefore releases their last backing pin even
while the mount view remains alive.

Guest clone/unshare with `CLONE_NEWNS` prepares the candidate asynchronously on
the filesystem worker, under the caller's file-table and FS-context turns.
The host clone/unshare omits that namespace flag; successful native completion
publishes the prepared guest view before a child resumes. Unshare also separates
CLONE_FS state. The copied cwd is rebased only when it belongs to the original
view; existing descriptors keep their original provenance. Failure or caller
death releases the candidate without replacing the task's owners. Virtual
CAP_SYS_ADMIN controls admission; it grants no host capability.

Mount views support directory/file bind mounts, recursive bind,
readonly remount, private propagation, move and normal/lazy unmount. Placement
uses stable slots and a validated acyclic parent graph, independently of slot
allocation order. Recursive preparation acquires the complete subtree before
publication. Move preserves mount IDs and open anchors. Lazy detach removes
lookup visibility while retained descriptors still address the old objects.
Path crossings use a worker-owned index keyed by parent mount and covered
device/inode once a tree exceeds eight attachments. Tiny trees retain the short
scan. Index construction preserves slot precedence, allocates no descriptors,
and is invalidated by placement, detachment, retirement and foreign graph merge.
Copies/imports rebuild it from their own rebased pointers; failed topology
publication leaves the old lookup authoritative. Mounted-root stacks are walked
iteratively with the graph's node bound, not recursively on the C stack.
Normal unmount rejects retained anchors, child mounts or roots belonging to
any FS context in the namespace, including prepared fork/unshare candidates.
Exported origins hold shared OFD range locks through queued rights, import and
forwarding. A normal unmount retains an exclusive nonblocking range lease through
publication, excluding a concurrent foreign-origin acquisition. Conflicts return
EBUSY without a wait, root escalation or a helper process. Lazy detach permits
existing references. Detached entries are collected during later mount admission
when no origin, selected root or child placement retains them. Collection drops
their object-cache records, policies and backing custody before slot reuse.
A slot generation prevents stale snapshots from referring to its replacement.
Nonrecursive topology changes check the selected operands' ancestry, not every
unrelated attachment. Recursive operations additionally reconcile the selected
subtree; leaf mounts use the existing child index to avoid a graph walk.
Namespace copying and table construction reconcile the complete graph.
Recursive bind reconciles its source recursively,
but only the target's ancestry: target admission does not copy its descendants.
Logical link membership, or native
link count for native targets, determines disconnection; rename retains the
same target identity. A disconnected placement and its descendants use the
common detach transaction without releasing live origins. Graph readers may
derive that disconnection from retained backing custody but cannot publish an
owner's graph or request an unmount. Ordinary file IO has no whole-tree scan.
Consecutive targets in one inode store share a fresh read snapshot; its gate is
released before entering another provider or publishing a topology change.
Known-detached collection is independent of removal discovery and returns
immediately when no detached objects exist. Admission at capacity performs a
full reconciliation before reporting ENOSPC; overlay admission also discovers
deleted placements before reserving shared upper/work storage. Detached
construction roots participate in the same retirement scheduling. Collection
with no retired slots does not allocate a candidate mount array.
Cross-namespace deletion does not yet push notifications to already-open mount
tables; reconciliation occurs at these topology observation boundaries.

Recursive bind and shared propagation use one backing-containment operation.
Inode-backed directories compare object ancestry in one read snapshot rather
than reconstructing names. A live bind of a removed source directory therefore
does not make an unrelated mount fail with ENOENT. Selected file identity and
ambiguous hardlink ancestry remain distinct; ordinary file IO does not perform
these topology checks.

`pivot_root` accepts the initial base root or an attached selected root and an
attached new root below it. The base is a stable mount instance, not a missing
origin. Its physical root descriptors are acquired only when its placement changes.
The old root is moved to the resolved put_old directory. Root and cwd references
exactly at the old root move to the new one; other cwd locations and open FDs
remain unchanged. Namespace-owned weak FS-context membership includes prepared
fork/unshare candidates, independently of task-registry publication. Each
context retains its namespace; a short lifecycle lock orders membership and
root relocation without holding a lock across guest execution. Root placement
is part of the same validated revision as attachment placement; prepared roots
and covered dentries are released on failed publication. Namespace copies retain
the relocated base and remap its parents. Recursive bind, child-busy checks and
retirement traverse the base and attachments in one topological order. Computing
that order takes linear time, including child-before-parent slot layouts: a
stable pass-number sort preserves the former scan order and rejects cycles.
Returning to the base preserves its explicit root identity for subsequent pivots; mountinfo
publishes the selected root once and the relocated base at its covered path.
Ephemeral and layered mounts use the filesystem backends described below.

The descriptor mount API separates three owners: a `fs_context` configuration,
a `mount_source` prepared filesystem, and a graph placement. Legacy mount uses
the same source factory and attachment transaction. Typed control input is
retained under the descriptor-table turn before worker submission, so close or
FD reuse cannot retarget a configuration operation. CREATE captures provider
resources using the opener's credentials and PID/cgroup/network ownership. Parameters,
created, mounted and failed phases reject invalid ordering. Ordinary filesystem
requests acquire no new owner or probe.

`fsmount` publishes a detached, parentless graph root and returns its existing
O_PATH origin through the shared FD delivery path. `move_mount` attaches that
same instance in its creating mount view without changing its mount ID or
retargeting earlier descriptors. Detached roots are included in namespace
copies and graph custody; graph validation distinguishes a parentless root
from an attachment under the namespace base. Closing the final origin permits
normal collection. Context FD close releases prepared source custody directly;
failed delivery/cancellation uses the existing result owner. Native `fsopen`
and host mount privilege are not used. Filesystem contexts use nonseekable
control-file IO, while cgroup controls retain their existing cursor semantics.
Reconfiguration, typed binary/path/FD parameters, cross-view placement and
unimplemented attributes are not silently accepted.

`fs_control` owns shared stat/access/statfs/open validation and descriptor
publication for native-backed cgroup/sysfs controls. Providers supply metadata,
filesystem type and control preparation; their mutations and provider-specific
errors remain separate. Origin capture, reopen, callback failure and carrier
release have one owner, with no provider object allocation per operation.

`mount_setattr` resolves a mount root by path or retained descriptor, including
a detached construction root. Readonly/nosuid/nodev/noexec/nosymfollow changes use the same
policy as legacy remount. Recursive changes preflight every selected mount's
writer authority, locked attributes and backing writability before publication
on the filesystem worker. Failure leaves all policies unchanged. Atime settings
are validated compatibility hints, not timestamp enforcement. Propagation uses
the same shared/private/slave/unbindable relationships as legacy mount calls;
ID mapping is explicitly unsupported. Nosymfollow is mount-local, not a store setting: the
component walker checks the selected mount before following a link. Bind aliases
can have different policies; retained dirfds follow their original mount's live
policy. Linux's user-namespace locks cover readonly/nosuid/nodev/noexec, not
nosymfollow. Link inspection remains available. Typed proc links check traversal
before delegation; an unclassified task-affine suffix is explicitly unsupported
when its policy cannot be retained. The syscall adapter validates the versioned binary
structure and zero extension before submitting typed bytes; ordinary IO gains
no allocation, counter or observer.

Mount-root admission uses resolved object identity for remount, propagation,
attributes, move, unmount and pivot. The initial inode root uses its logical
ID until relocation materializes a mount source. A proc-FD spelling has the
same authority as a direct path; chroot into an ordinary directory does not
make that directory the root of its mount.

`test_mount_api.py` exercises syscall state/order, descriptor flags, dup/fork,
SCM_RIGHTS, detached-root IO, readonly attachment, recursive attributes, locked
rollback, source cancellation and stock
Debian util-linux mounts. The native transient-store suite additionally checks
namespace copy, detached graph import/export and exact final FD counts.

Exec preparation retains the selected executable's mount origin, independently
of the construction FD and its CLOEXEC flag. Commit transfers that origin to the
process image; fork shares it, replacement/exit releases it, and cancellation
releases only the prepared candidate. Loaders and intermediate shebang images
are not pinned. This keeps detached `open_tree` executables and `/proc/self/exe`
valid during concurrent mount collection. The mount fixture exercises this
across descriptor exec, proc-image re-exec and parallel children.

Mounted inode filesystems retain backing custody separately from their placement.
Bind and namespace copies share that custody, not a SQLite connection or mount
policy. A worker-local storage cache opens an additional connection only on first
use; the initial-store path needs neither a cache lookup nor an allocation.
The cache retains at most 16 connections in LRU order. Scoped users exclude
eviction; cold admission and mount retirement release connections whose backing
custody has no other owner. Watches own independent connections and can outlive
cache eviction. No cache sweep or filesystem probe is added to ordinary data IO.
Path traversal, roots, metadata, object IDs and reopen select the mounted store.
Retained old-view dirfds remain usable after the original worker connection closes.
`storage_database` supplies the retained-directory SQLite VFS and result-to-errno
mapping for inode stores, IPC credentials and SysV authorities. It preserves the
directory FD through rename; stock Unix VFS IO, rollback/WAL journaling, locking
and nofollow opens remain in use. Each connection borrows a directory retained
until close, and failed opening returns no partial connection. SQLite's multithread
mode protects its global state across supervisor/worker owners; NOMUTEX connections
remain confined to one owner without redundant per-query connection locking.
Portable graphs retain each distinct mounted store's maintenance
and loaded-source leases once, including aliases. Imports reconnect under the
same custody after publisher exit, without trusting the original store path.
Watch instances own one queue and independent sources for each selected store.
Aliases share a source by store identity; additional sources retain their own
custody and connection rather than borrowing the filesystem worker's cache.
Watch descriptors are unique across the instance. Empty sources release their
observation resources; transactions and waits never span two stores.

### Shared Mount Policy

Each attachment and the base root own a `mount_policy`. Local views keep flags inline,
without a descriptor, mapping or extra allocation. Open/reopen, access checks,
metadata/xattr admission and mount-table output read the same policy. Namespace
copying captures the current flags into an independent local owner, not a shared
writable alias. Anchors retain the policy with their exact mount view.

Explicit private publication lazily creates one memfd/mapping per exported mount.
Its bounded header contains the format and a lock-free atomic flag word. Size
seals prevent resize; `F_SEAL_FUTURE_WRITE` leaves the publisher's existing mapping
writable while blocking FD writes and new writable shared mappings. Received
observers validate the layout, seals and CLOEXEC descriptor before taking ownership
and mapping it read-only. Failed import leaves the descriptor with its caller;
failed publication releases its temporary resources and preserves the local owner.
An observer can forward the descriptor but cannot promote itself to a writer.

Readers see the writer's atomic updates without RPC, polling, descriptor
duplication or SQLite. Once shared, repeated publication borrows the same FD and
flag reads/writes need no free FD slot. Queued rights and received mappings retain
the last state after publisher teardown or exit. This is kernel-owned state
lifetime, not a background custodian process. Ordinary data IO is unchanged.

This component transfers policy; the graph parcel below associates that policy
with source/covered O_PATH references. The verified private transport must bind
the parcel to its store and message. Atomic flag visibility does not provide
mount/remount transaction ordering across workers. Local mutations run on the
filesystem worker; mutable topology uses the revision contract below.

### Portable Attachment Graph

`mount_graph` publishes immutable revisions of an attachment graph into sealed
memfds. Each bounded, pointer-free manifest records parent indices, slot
generations and the kernel
identities of each source object, covered object and policy descriptor, including
the base-root policy and its optional relocated placement. The selected graph
root is explicit. Backing type, detached state and mounted-store identities
are explicit. Parent
indices need not precede children; cyclic or out-of-range relationships fail.
The parcel retains those actual O_PATH references and policy FDs, not paths or
application data descriptions. Native aliases remain separate mount entries;
nested mounts retain their exact covered dentry and parent. Replacement of an
old source pathname cannot redirect the imported graph. Mounted inode stores
append deduplicated `inode_custody` parcels; their exact spans and root identities
belong to the sealed graph. Even a repeated import validates all backing rights
before reusing a known view. Forwarding retains those original leases.

Host-backed sources and attachment points require an admission-time SCM_RIGHTS
check: opening an O_PATH descriptor does not imply that the executor may receive
it over a Unix socket. Startup binds and guest mount operations share this check.
Nontransferable carriers keep their metadata in the existing local custody path;
they are never published as incomplete portable graphs. Namespace copies retain
the result, and placement changes check newly selected host carriers. Ordinary
path lookup and file IO perform no transfer probe.

Proc provider roots carry their immutable visibility and PSI-admission policy
alongside the existing PID-namespace identity, upper-tree lease and superblock
policy. Import validates this typed policy before publishing the graph; live
revisions cannot replace it for an existing mount. This is a transient IPC
contract, independent of the persistent storage format.

The retained topology stream's device/inode identifies the graph across private
transfers and forwarding, independently of the publisher's process lifetime.
Mount IDs remain receiver-local and are allocated from its existing owner.
Import into an already known view verifies that identity and every descriptor,
then reuses its IDs and object cache. It does not reconstruct the cache or infer
identity from equal directory trees. A namespace copy has independent policy
and a new graph identity on publication. The origin-transfer owner below interns
imported views within one filesystem worker; concurrent workers cannot share one
mutable object cache.

Export returns an owned bounded parcel. Import always consumes it and publishes
a destination only after complete validation. Invalid seals, graph structure,
FD count/type/identity and allocation/mapping failures close partial resources
without replacing an existing destination. Empty views transfer the manifest,
root policy, anonymous pin carrier and stream. Placement needs at most 54
descriptors; each distinct additional
store adds 2..512 custody descriptors, up to 16 stores. The cold parcel allocates
only its actual FD count and travels through the existing bounded FD bundles.
Validation and duplicate-safe cleanup sort descriptor numbers rather than doing
quadratic scans of expanded vectors. Cold publication is lazy. Ordinary
unexported views create no manifest and ordinary data IO takes no new path.
Failed export can retain successfully materialized publication state in its
source view, but leaves no partial output parcel or changed mount policy.

The owner prepares a complete new revision, queues its immutable FD bundle and
then consumes the old head to expose the new one atomically. The bundle never
contains its own topology stream, avoiding SCM_RIGHTS reference cycles. Death
before commit preserves the old head; after writer exit readers retain the last
committed revision. Only that owner may publish. A foreign worker checks the
revision at operation admission; unchanged revisions require no allocation or
FD slot. Changed revisions are fully validated before merging into its local
tree. Old snapshots cannot roll back a newer view. Retired entries carry no
placement descriptors; replacement invalidates only that slot's old object cache.
Unpublished local mutation still fails ESTALE on export. Readonly updates use
the live policy mappings. Selected task root/cwd, native mount-ID-map transfer
and a complete application-origin vector are separate from graph publication.
`fs_origin_transfer` composes graphs with
backing custody and a message route. A valid graph alone does not authorize
access to another store. Local guest namespace creation has independent admission.

One empty anonymous regular file per exported graph carries independent byte-range
leases for its slots. Local origins need no extra descriptor. Exported and foreign
origins own readonly descriptions with shared OFD locks; kernel SCM_RIGHTS queues
retain them until receipt or discard. The writer temporarily takes an independent
exclusive lease for normal unmount or retirement. A receiver refreshes the graph
under its new shared lease and rejects stale generation/detach state before
publishing an anchor. Final close releases the lock without a counter protocol.
This carrier has no directory entry, payload data or per-request file creation.

### Portable Backing Custody

`inode_custody` captures the existing root maintenance lease and every currently
loaded lower's objects directory and maintenance lease. Its sealed, pointer-free
manifest records the root identity and ordered source IDs with each directory's
device/inode. It shares the bounded sealed-manifest primitive with attachment
graphs. Publication duplicates the original open descriptions: it never reopens
paths, changes flock state, queries SQLite or retains application data OFDs.

Import validates every descriptor, source/lease relationship and manifest field
before publishing an `inode_lifetime`. The root must match a retained directory
from the receiver's authenticated store scope; an equal pathname or a replacement
store cannot authorize it. Import always consumes the parcel, including on
failure. Partial export and failed import close all temporary rights without
changing the source's locks or publishing a partial owner. The private peer must
provide the actual locked descriptions: inode equality alone is not proof of
flock ownership, and import must not acquire or convert locks on those descriptions.

Imported custody uses the same lifetime constructor and allocation-free local
retain/release as a SQLite-backed store. The root and lower leases survive source
SQLite closure, sender exit, pathname replacement and forwarding. Queued rights
also retain the leases; discarding a queue, killing a receiver or releasing the
last owner lets the kernel release them. This needs no custodian process or new
descriptor per local reference. Ordinary data IO and unexported stores do not
enter the publication path.

A parcel contains two descriptors plus two per loaded lower, at most 512. The
transport must chunk that bounded vector and own partial receives; it must not
raise FD or in-flight limits. This is a snapshot of loaded sources, not a live
catalogue replica, a relocated SQLite catalogue or an authenticated origin
vector. Composition with attachment graphs and the message authority belongs to
the origin-transfer owner, not this backing component.

### Portable Message Origins

`fs_origin_transfer` exports a pinned, bound source message into one owned
control-plane parcel. Its compact sealed manifest contains only used records:
the exact message route, physical store identity, vector indices, unique O_PATH
anchors, mount slots/generations and spans identifying the graph/custody manifests. Repeated
anchors and shared owners are emitted once; unknown entries remain unknown.
Every known anchor carries a mount pin, including base-root origins created
before a subsequent pivot. They retain the old mount rather than following the
namespace's new root.
No mount pointer, SQLite connection or application data OFD crosses this boundary.
Publication is worker-only because graphs and loaded source catalogues are
worker-owned. The event loop retains the immutable message through completion.

A store-scoped import owner opens its O_PATH root relative to the retained store
directory, not by reopening its pathname or duplicating its maintenance lease.
Import checks the expected route, store, canonical indices, all rights and their
manifest identities before making origins usable. It always consumes the parcel.
New views and origins remain staged until the entire vector succeeds; failure
releases partial custody without changing an existing cache or destination.
Allocation and descriptor exhaustion return errors, not partial vectors or
automatic replay.

The import owner interns graphs by their retained stream identity. Repeated and
forwarded imports reuse receiver-local mount IDs and object caches while any
anchor retains that graph. Each worker owns its own cache; copying a namespace
still makes a distinct graph. Cache-only views are collected after successful
imports, by explicit collection, or on owner teardown, without periodic scans.
Base-store backing custody belongs to the anchors. Mounted-store custody belongs
to their graph, so cache collection also releases otherwise unused mounted-store
leases. Anchors survive cache teardown and retain all required backing owners.
Different transfers retain independent snapshots of loaded backing sources.

Imported origins use the same anchor constructor, filesystem engine and FD-table
batch publication as local origins. Local retains, ordinary data IO and unrelated
launches perform no serialization or cache lookup. All control rights are subject
to native descriptor/in-flight quotas; transport must own bounded chunking and
partial receive cancellation.

Matching a route or sealed bytes is not peer authentication. The surrounding
protocol must authenticate the issuing authority, exact message and receiving
connection, and supply that independently verified route to import. The serializer does not
select application framing, publish actual received application FDs, transfer
native mount-ID maps or order mutable topology/remount transactions. Those
production integration boundaries remain separate from the tested transfer.

### Self-Contained Origin Custody

`ipc_capsule` packages the same captured origins and actual data descriptions
into an immutable kernel-owned carrier. It requires a creation-time ORIGINS_V1
binding. The filesystem worker validates captured backing identities, issues
the directional route and serializes with `fs_origin_transfer`. No source
registry, live launch, RPC exchange or per-message persistent record is needed
to receive a published capsule.

`fd_bundle` stores each node as one seqpacket with its writer closed before
publication. A leaf holds up to 253 rights; larger metadata vectors use bounded
trees of those queues. Every traversal peeks at the only packet, without a
shared read cursor. Application data and metadata have separate bundles. The
outer queue owns both plus the original IPC epoch and store-maintenance leases.
Their queued references survive the last supervisor's exit, including messages
with no known origins. Discarding the carrier releases the complete tree.
Origin metadata never retains application data descriptions after queue disposal.

One lazy 32-byte key belongs to the live store IPC epoch. Creation uses its
existing metadata gate and atomic rename; an interrupted unpublished creation
is replaced by the next creator. The first owner of a new epoch removes stale
key state. Publishers authenticate the complete capsule header, including the
actual kernel carrier cookie, exact connection side/link and message nonce,
with HMAC-SHA256 from the existing pinned Mbed TLS dependency. The key is cached
by each worker owner, not looked up for every message. Native connections do
not create this owner or key.

The receiver performs a descriptor-free peek and verifies authentication before
installing any control rights. A copied header on another socket fails cookie
validation; replacing its cookie invalidates the tag. It then imports through
the common bounded metadata parser and annotates the actual data bundle's FDs.
The result is one `received_fds` owner with native flags and shared offsets,
usable after both the capsule and importer close. Unknown origins stay unknown.
Repeated/concurrent peeks leave the queues intact. Partial allocation or FD
installation closes only owned results; it cannot authorize application replay.

This authentication assumes trusted runtime custody of the store's private IPC
directory. It is not isolation from a hostile process with the same real UID
that can read or alter that directory. Native FD, in-flight and Unix queue-graph
limits remain explicit errors; the runtime does not raise them or substitute
unannotated delivery. Ordinary guest traffic uses NATIVE. Ancillary sends and
the receive adapter handle explicitly bound ORIGINS_V1 connections, including
mixed credentials. Addressed datagrams retain native semantics unless their
actual peer matches the binding. Unadapted consumers need a separate policy.

`--case origin-transfer` checks an independent exec sender exiting before a
fresh receiver exists, 253 mixed and all-unknown data rights, readonly/copied
views, repeated and concurrent peeks, shared offsets, copied/tampered headers,
native/wrong-direction bindings, allocation failures, five partial-FD limits,
interrupted key construction, key retirement and exact final FD/lease release.
Discard-without-import checks data pipe EOF. Separate bundle checks cover 253
and 600 rights, invalid shape and partial construction failure.

### Authenticated Origin Transfer

`ipc_origins` owns asynchronous capsule preparation and import on the existing
filesystem worker. Its event-loop owner admits at most 16 requests. It creates
no listener, control exchange, timer or additional thread. Its copied launch ID
is diagnostic metadata, not authentication; the capsule's epoch key and actual
socket binding authenticate the transfer.

Preparation retains one immutable capture containing the actual application
socket followed by up to 253 data descriptions, plus the matching origin
snapshot. Both must come from the same source-table turn. The worker resolves
the binding from that captured socket. ORIGINS_V1 lazily creates its capsule
owner, checks backing identities and returns an owned two-FD vector: the same
socket description and the completed capsule. NATIVE and unregistered peers
return the original captured owner on event-loop completion, without duplicating
descriptions or serializing origins. Completion exposes the selected format;
malformed or stale bindings remain errors rather than a native fallback.
Closing or reusing a guest socket number after capture cannot retarget the
prepared send. Preparation neither sends application bytes nor changes a
connection's creation-time format agreement.

Receive retains the actual socket and carrier in another immutable two-FD
capture. After validating the creation-time binding, the worker authenticates,
imports and peeks through `ipc_capsule`. It returns one annotated
`received_fds` owner, not metadata for caller-selected descriptors. Repeated
receives share native data offsets; known origins retain their backing leases
after the importer closes. Unknown-only vectors allocate no annotation array.
Neither operation looks up or contacts the source process.

Shared input reference counts change only on the event loop; the worker borrows
them through completion. Callers may release their handles immediately after
admission. Cancellation clears its handle and suppresses callbacks without
freeing running inputs. Pending/computed results retire on completion dispatch
or after worker stop/join. Allocation, native quota and authentication failures
return no partial custody and close only owned resources. No guest FD number is
guessed or closed, and no operation is replayed.

The source helper can export socket plus rights at one private-table clone.
A separate captured-send operation accepts a retained socket plus 0..253 rights,
covering both a capsule and an ordinary native vector. It clears inherited helper
FDs and installs a private import channel with atomic ADDFD_SEND. The kernel
receives all rights, including O_PATH, directly into an outgoing control block
in caller-owned control memory. It closes that channel before installing the
captured application socket at its original number. There is no per-right ADDFD, application
slot or lookup of original guest FD numbers. Import scratch and control ranges
are bounded, aligned and disjoint; partial import fails before application send.
The kernel reports both quota and control-copy failures as MSG_CTRUNC; the
helper returns EMFILE for incomplete private import and releases its whole table.
The queue remains supervisor-owned through exact reaping, so cancellation drops
both unreceived and installed private rights. Native filters, quota errors and
SIGPIPE remain observable; cancellation after committed send preserves its
byte count. The existing task-local send owns final execution.

The guest adapter uses this pipeline for ancillary and addressed Unix sends.
One outgoing owner spans the source-table turn, task/metadata snapshot,
private-table export, worker preparation and final task-local send. Export
releases the table turn at clone, before IO; its scratch can be reused for import
only after exact helper reaping. The adapter checks the selected format and
normalizes split rights headers into one bounded control block. No source FD
number is looked up again after capture. Cancellation before commit releases
prepared custody; cancellation during capture or send retains it through exact
helper reaping. Format selection inspects only the captured socket on the worker,
not a preliminary guest SO_DOMAIN call or a reusable source number. Non-Unix
sockets retain native selection and kernel ancillary validation. Releasing the
mapped buffer drops any unused prepared result.
An ordinary caller with an already admitted, unambiguous table-owned channel
reuses the broker capture path under the same table turn. No new infrastructure
FD is admitted for this optimization. Authenticated destinations can also use
broker sending; external peers retain task-affine IO and native credentials.
Explicit credentials join the prepared vector after captured-identity validation.
An optional native pathname anchor has separate custody and a private import;
it cannot consume the 253-right message budget or become an application right.
Its extra private slot and native in-flight quota remain real limits. Empty
rights need no rights-import channel unless a pathname anchor is present.
Native socket placement also requires its original number below RLIMIT_NOFILE;
a limit lowered beneath an existing socket number is an explicit capacity error.

`--case ipc-origins` checks source socket-number reuse, 253 mixed/repeated/unknown
origins, source worker shutdown, repeated receive and shared offsets. Eight
cancellation cases cover preparation/import before execution, during metadata
admission, after computation and after callback. Both worker-join cases reclaim
undispatched results. Failure injection and bounded concurrent admission verify
unchanged caller custody and exact cleanup. Independent exec tests receive only
after the sender exits; another receiver exits with an untaken result and live
worker, requiring kernel-only release of all data/maintenance references.
Native selection checks registered and external stream/datagram sockets, exact
owner/FD identity, pending/computed/callback cancellation, worker join and invalid
socket rejection. `--case fd-observation` checks both one-right and 253-right
captured sends, including protected/full caller tables, exact 254-slot helper
capacity, partial import, receive/close/send filter denial, SIGPIPE and terminal
cleanup. O_PATH, repeated descriptions and shared offsets survive the transfer.

`ipc_received` is the worker-owned message decoder used by `fs_buffer_service`.
It reads the transport binding from the captured socket, separates credential
tokens and expands an authenticated carrier into a distinct annotated data
vector. A compact index plan maps each native right to its output entries;
these indices are never guest FD numbers. Native messages borrow the original
custody without duplicating its descriptors or allocating an expanded vector.
The decoder never sniffs capsules on NATIVE or unregistered connections.

The receive owner keeps the original batch immutable and owns one expanded
vector per inspected message. Successful worker completion transfers that
vector before releasing the request. Cancelled and untaken results retire on
completion or worker join. Publication selects this same custody through
ordinary annotated `fd_return`, including partial output faults and terminal
observation; it does not receive FD numbers into a temporary application buffer
and copy them afterward. Subsequent batch messages replace expanded custody
only after the preceding message's publication has finished.
The terminal syscall acknowledgement releases the final receive custody; batch
and accept intermediate publications retain their explicit release boundaries.
Brokered messages fitting the shared payload and control buffers combine
admission with IO commit. Larger and batched messages retain staged preparation.
Both paths use the same socket timeout, nonblocking, low-water and rights owners;
no socket flags or readiness decisions are cached across operations.

`--case ipc-ancillary` runs guest send cases with an explicitly bound
socket inherited through the runner. Protected/unprotected cases cover one and
253 file/O_PATH rights, full caller tables, split headers, invalid FD, rejected
send, sendmmsg, payload EFAULT and a sandbox denying SO_DOMAIN. Import starts
only after the guest sender and its worker exit; exact origins, offsets, FD balance, payload EOF and final
maintenance admission must still hold. Four native-format blocked-send cases
cover protected/shared tables, caller death and source-slot replacement, with
no unrelated pipe writer or resource turn retained during IO. Signal checks
cover SIGPIPE delivery, masking and MSG_NOSIGNAL. A native/guest UDP control
checks payload delivery with ignored rights and unchanged descriptors.

The same case runs guest receive cases with a prepared,
explicitly bound socket inherited through the runner. No sender worker remains
at receive time. Protected/unprotected cases check 253 mixed rights, shared
offsets, logical openat through imported directory origins, repeated peek,
recvmmsg, control truncation, control/header EFAULT and zero/three free table
slots. Explicit credentials are tested on either side of the coalesced capsule,
with SO_PASSCRED enabled and disabled; NATIVE delivery leaves a capsule opaque.
Every case requires payload EOF, exact descriptor balance and final store
maintenance admission. The matrix also runs ordinary protected/unprotected
callers after a filtered CLONE_FILES child admits the shared channel and exits.
Their source tables, prepared sender tokens, imported rights and full-table
publication must remain correct without requiring a filter on the caller.
Seven `--case ipc-origins` worker cases check decoder
result ownership, cancellation before/after execution, join before callback,
untaken completion, wrong direction and partial annotation allocation failure.

Capsule queues consume native per-UID in-flight rights independently of free
table slots. A sufficiently reduced RLIMIT_NOFILE can make the private helper's
export fail with ETOOMANYREFS even with free slots. The guest fixture verifies
this explicit failure and unchanged caller descriptors; the runtime neither
raises the limit nor retries a potentially consumed message.

Production authorities still offer NATIVE. Source handling of explicit
credentials and addressed/mixed ancillary sends, unadapted consumers, native
mount-ID maps and mutable topology/transaction ordering remain separate contracts.

## Path Origins

The filesystem engine accepts optional `md_fs_anchor` origins for its captured
base descriptors. Each anchor retains the exact mount view, mount, an O_PATH
reference and the backing store's `inode_lifetime`. This owner keeps the existing
store/source directory descriptors and maintenance leases after the originating
worker closes its SQLite connection. Capturing or retaining origins duplicates
none of those descriptors. The SQLite connection, prepared queries, transaction
lock descriptor and watches remain worker-owned and close independently.
The retained root's device/inode identifies the store, not its pathname or a
connection's address. A sequential worker can reopen that same store, including
after rename, and use the origin; a different store or replacement at the old
path is rejected. Retained lower-source leases protect backing names through
copy-up and unlink. Releasing the last origin releases those leases immediately.
The worker alone mutates its mount view and source catalogue: reference counting
does not authorize concurrent workers or serialize a live view across processes.
Successful open/reopen operations can return an owned anchor when explicitly
requested. Other calls allocate no anchor; RPC clients cannot serialize pointers
or claim a view. The transport must obtain provenance from trusted task resources.

Relative paths, relative symlinks and `..` follow the origin's tree and policy,
even when the task selects a copied tree. Absolute paths and absolute symlinks
start at the task's current root. `RESOLVE_IN_ROOT` instead keeps the captured
base as the resolution root. `RESOLVE_NO_XDEV` checks mount/view changes,
including an absolute symlink resetting traversal. Link and rename cannot join
different mount instances. stat metadata and reopen retain the origin's mount ID
and readonly policy rather than inferring them from the current view.

Anchors neither retain readable/writable descriptions nor replace the input FD
for data operations. Directory offsets, FIFO peers and flock/OFD-lock release
remain kernel-owned. Anchor-capture failure closes the result FD and any pending
FIFO rendezvous descriptor. It does not undo earlier filesystem mutations or
authorize a retry.

The caller must associate each anchor with the correct captured descriptor.
Device/inode validation rejects a mismatched object; it cannot distinguish two
opens of the same inode through different mounts. An O_PATH pin alone is not an
open-file-description identity. There is no mirror of ordinary data descriptions;
read/write stays native and close is observed at its kernel completion.
The anchor's own descriptor needs no repeated identity syscall: its owner keeps
that slot alive until final release. Separately supplied descriptors still require
validation. Scoped walks retain a separate boundary pin only for `RESOLVE_BENEATH`
or `RESOLVE_IN_ROOT`; ordinary walks do not allocate an unused boundary descriptor.

General application SCM_RIGHTS provenance is not inferred from a successful
syscall return or a post-receive acknowledgement. Ancillary delivery can publish
valid descriptors before a later output fault. Its shared receive/publication
contract and remaining batch-fault boundary are described in
[Unix IPC credentials](ipc-credentials.md); those delivery rules alone do not
propagate arbitrary peer mount origins into the annotation table.

Cross-view relative IO works, but path/image-path publication for an origin in
another tree returns ENOENT instead of inventing a current-root path. Changing
the task root through such an origin is unsupported.

Native object IDs identify `(mount, device, inode)` within their retained view.
The walker or a trusted origin selects the mount, so aliases can retain distinct
IDs and readonly policy even after unlink. Image opening and root capture use
the resolved mount rather than inferring it from the backing inode. Root capture
requires a directory in the selected view and rejects mismatched or stale origins.
An unannotated descriptor fails with ENOTSUP when cached identities or uncached
source lookup reveal multiple mounts; a unique cached match retains the existing
fast path. The cache is not a replacement for complete descriptor provenance,
and public admission of duplicated/overlapping native sources remains disabled.

## Guest Roots

`chroot` selects a directory through the existing composed filesystem
walker, checks search permission and guest CAP_SYS_CHROOT, and changes the
calling task's filesystem context only after success. Shared CLONE_FS owners
observe that change; fork or successful `unshare(CLONE_FS)` gives an independent
context. Exec retains it, including for nondumpable processes. Registry locks
cover snapshots/publication, never filesystem IO.

Absolute paths and absolute symlinks start at the selected root; `..` stops
there. `md_fs_location` identifies the root by mount and backend object, not a
pathname prefix. Inode-store roots and directories inside native attachments
share this contract, including rename and retained-descriptor lifetime. Cwd
and existing descriptors are not moved or revoked: relative access through a
retained directory outside the new root remains possible, as with Linux chroot.
getcwd returns ENOENT when the directory has no path within the selected root.
Reconstruction stops at the root's own mount, so a detached mount retained as
the process root does not need a name in the former parent tree. Unlinked cwd
and detached child mounts still have no reachable path.
Mount-table snapshots omit attachments outside it and retain visible parent/child
mount IDs. Native roots preserve their attachment's readonly policy and the
kernel's permission checks; guest CAP_SYS_CHROOT grants no Android authority.

The syscall adapter receives task-local root selection, including when CLONE_VM
peers have independently unshared filesystem contexts. It has no mutable global
current-root cache. Ordinary launches need no additional RPC, SQL query or
per-request allocation. Only scoped-root adapter calls materialize a local
filesystem configuration.

An already-admitted, caller-captured native directory outside explicit
attachments becomes a retained detached root in this same mount model. Procfs
is identified at root admission and uses the existing proc component walker:
caller-relative `self`, parent traversal and descriptor provenance do not turn
into an unrestricted native suffix. This probe is not on ordinary path calls.
Retained `/proc` and `/dev` directory descriptors remain usable after root
selection. Their task-owned paths return to the caller's provider, never the
filesystem worker's `self`. Selecting the initial inode root preserves its
proc/dev boundary; a different root or mounted filesystem has ordinary names
there. The direct-rootfs fixture backend's separately admitted
`/proc/self/fd` and `fdinfo` restriction retains its irreversible policy; it is
not a fallback for the managed mount model.

Copying a descriptor table does not copy open-file descriptions: offsets remain
shared, while close-on-exec flags and descriptor closure become independent.
There is no supervisor mirror of every open-file description. Reads and writes
remain native; short table mutations have ordered completion observation.
