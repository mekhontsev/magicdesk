# Guest FUSE

Shroot provides FUSE as a native guest filesystem adapter. A stock guest daemon
opens `/dev/fuse`, negotiates the Linux wire protocol and serves ordinary guest
mounts. Android's real FUSE device and kernel mount permission are not used.
The selected executor identity, namespace owner, DAC, Landlock and mount policy
remain authoritative. This adds no persistent store format or APK dependency.

- [Ownership](#ownership)
- [Protocol and Policy](#protocol-and-policy)
- [Append and Notifications](#append-and-notifications)
- [Mapping Limits](#mapping-limits)
- [Descriptor Closure](#descriptor-closure)
- [Verification](#verification)

## Ownership

- `fuse_device` owns the typed character device and daemon IO. Final device
  closure aborts pending operations instead of leaving a sleeping worker.
  Pending requests retain their abort error; new operations on a disconnected
  mount return `ENOTCONN`, including through bind aliases and cached names.
- `fuse_transport` owns bounded requests, unique IDs, replies, cancellation and
  notification delivery. Completion callbacks run outside the connection lock.
  Invalidation handlers run under that lock to serialize context teardown;
  they only update view state and never call back into the transport.
- `fuse_session` owns INIT negotiation, credentials and advertised capabilities.
- `fuse_view` owns node IDs/generations, names, attributes and mount operations.
  Carrier names do not consume one retained host FD per node.
- `fuse_file` owns each open handle, IO and release. `fuse_image` prepares sealed
  private images for mmap and executable admission. `fuse_snapshot` shares
  validated images by inode within a bounded per-mount cache. `fuse_mapping` owns the
  lazy per-inode backing for read-only shared mappings.
- `watch_source` supplies bounded provider events to the existing `inode_watch`
  owner. No listener, event descriptor or event record is allocated until a
  guest watches that provider. Native and journal-backed watches retain their
  existing paths.
- `fs_continuation` and the existing filesystem worker own suspended operations.
  Captured operands remain valid across daemon waits. Mutations are not replayed
  after partial completion, and no store transaction or FD-table turn spans a
  guest daemon wait.

Namespace clones and bind mounts retain the provider. Lazy unmount removes its
placement but preserves open descriptions. Last-anchor release schedules mount
collection on the filesystem event loop; ordinary workloads need no periodic
collection. The provider closes only when its remaining owners are gone.
The same owner retains the mount's source label and `fuse[.subtype]` identity;
both mount-table formats escape these fields. Auto-unmount helpers can therefore
verify their filesystem through namespace clones and recursive bind mounts.

## Protocol and Policy

The offered protocol ceiling is 7.31; negotiation accepts compatible older
daemons down to minor 12. Unsupported capabilities are not advertised. Request
queues are bounded at 256 requests and 8 MiB; a packet cannot exceed 1 MiB.
File IO is chunked at 16 KiB. Queue saturation returns an explicit error.

Regular-file `read`, `pread` and their vectored forms retain one OFD turn across
transport pages. A full packet is not a short syscall result: the adapter keeps
copying until the requested count, EOF, a short daemon reply or an error. Only
confirmed bytes advance the cursor; positional reads leave it unchanged. A
signal or copy failure after a delivered prefix returns that prefix instead of
restarting the syscall. Devices and directory records keep their separate packet
semantics; ordinary native-backed files never enter this path. Suspected EOF is
checked against fresh daemon attributes rather than a stale cached size, while
`FOPEN_DIRECT_IO` leaves EOF to the daemon.

The adapter implements lookup/attributes, regular files/directories/symlinks,
creation, rename, links, removal, xattrs, data IO, directory iteration, truncate,
sync and statfs. Daemon errors propagate. Mounts enforce owner/allow_other and
default_permissions, and start nodev/nosuid. Explicit privileged guest remounts
use the common mount-policy owner. Executable admission reads identity from the
retained provider object, not its separately materialized loader image; set-ID
and nosuid remain guest policies and never grant host credentials. Private
executable mappings and execution still enforce noexec.
INVAL_INODE, INVAL_ENTRY and DELETE notifications invalidate relevant state,
subject to the shared-mapping limits below.
Positive names and attributes honor the daemon's independent protocol TTLs.
Names are indexed by parent/name; per-node links retain path and open-description
identity through rename/unlink. Deadlines start at request submission, and a
reply crossing a mutation or invalidation cannot renew a stale lease. Zero or
malformed TTLs do not enable caching. No timer thread or persistent index is used.

Cached metadata never caches an authorization decision: every access uses the
current credentials, mount policy and daemon connection state. Mutation checks,
append size queries and executable/private-image validation still fetch fresh
attributes. Ordinary reads still reach the daemon; mapping backings do not
become a read cache.

## Append and Notifications

Append uses current OFD flags, including changes made by `F_SETFL`, and a fresh
daemon size query. Writes, descriptor truncation and truncating opens share a
lazy per-inode queue across independently opened descriptions. An accepted
operation retains its inode turn until the daemon replies, even if its original
guest task exits. The descriptor owner's turn and cursor likewise survive an
abandoned write; a later operation cannot overtake its effects. Partial writes
advance only by accepted bytes. Positional append follows Linux's `O_APPEND`
behavior without changing the OFD cursor. No global filesystem lock spans a
daemon wait, and unrelated files continue independently.

Inotify reports successful guest operations, not attempted requests: create,
open, copied file/directory reads, write, attributes, close, unlink and paired rename events.
Open descriptions retain their dentry through rename/unlink; inode watches
survive unlink until final descriptor closure. `IN_EXCL_UNLINK`, one-shot masks,
duplicate-watch handling, queue overflow and provider teardown are covered.
`O_PATH` retains that lifetime without manufacturing open/close events. Invalidation alone is not a
mutation event; a daemon's DELETE notification is. Changes to a daemon's backing
files outside the FUSE mount cannot be inferred without protocol notification.

Each listener queues at most 16,384 provider records and reports overflow rather
than growing indefinitely. Object-indexed interests filter masks and unlinked
names before queue allocation; unrelated provider traffic cannot fill the queue.
Unmount notification is retained separately so even
an overflowing queue eventually retires its watches. Provider event storage is
ephemeral and does not change format 17 or create persistent journal traffic.

## Mapping Limits

MAP_PRIVATE and executable images use sealed snapshots, not a coherent Linux
page cache. Preparation checks attributes again after copying; detected changes
fail with EAGAIN. A new mapping revalidates metadata and the invalidation epoch.
Exec and mmap share these images across opens only when the daemon requests
FOPEN_KEEP_CACHE. Other opens invalidate the node's cached image; FOPEN_DIRECT_IO
does not use it. Fresh attributes and the node's mutation/invalidation generation
gate every reuse, without caching permissions or mount policy. The per-mount
LRU retains at most 64 images and 256 MiB; oversized files are not retained.
Eviction closes only the cache's descriptor, leaving published mappings intact.
Ordinary native-backed files never enter this cache.
Cancelled preparation has not published an application mapping and explicitly
permits retry after signal delivery, including handlers without SA_RESTART.
Its internal daemon wait must not make the loader's mmap return EINTR.
The kernel delivers signals normally; accepted writes and ioctls do not acquire
this repeatable-preparation property. No file-table or memory
turn remains held while a stopped daemon is awaited.
When a provider finishes before the synthetic wait enters the kernel, its
cancelled syscall is drained through the exit stop before adapter registers are
restored. Otherwise a later seccomp/kernel return can overwrite a successful
provider result. The same completion owner covers open and close waits; ordinary
native-backed IO does not use this state.
Existing private mappings do not follow later daemon writes. Same-size writes
without observable timestamp changes or invalidation cannot be inferred.
Safe sealed snapshots use the common task-local mapping/FD-loan path described
in [shared memory](shared-memory.md#read-only-anonymous-views). They need no new
helper thread inside an application's seccomp sandbox.

Read-only MAP_SHARED and MAP_SHARED_VALIDATE use one native backing inode per
FUSE node, shared across independent opens and fork. Publication revalidates the
snapshot and mutation epoch. Confirmed guest writes and truncations update the
same backing, including abandoned operations whose daemon reply arrives later.
Existing mappings therefore observe these writes, truncate faults and zero-filled
regrowth. Only independently opened O_RDONLY descriptions leave this owner;
shared PROT_WRITE and later writable mprotect fail. FOPEN_DIRECT_IO shared
mappings remain unsupported. This is not a MAP_PRIVATE substitution.

The backing uses O_TMPFILE in the provider's private directory, not a memfd
whose read-only reopening may be denied by shell policy. Its two retained FDs
and file bytes are allocated only for mapped nodes and retained until provider
teardown; munmap does not yet reclaim them early. Ordinary files and unmapped
FUSE nodes allocate no shared backing. A backing-update failure is reported and
invalidates its contents by truncation rather than silently exposing stale data.

There is no general shared-page writeback or daemon-driven page-fault service.
Data INVAL_INODE on a node with shared backing returns EOPNOTSUPP; attribute-only
invalidation remains supported. Independent changes to the daemon's backing
data are not guaranteed coherent. These limits do not block read-only Snap
images, including libxkbcommon's shared XKB rules mapping in Firefox.
INTERRUPT and DESTROY protocol requests are not yet implemented; closing
the connection is the final cancellation boundary. Node metadata is bounded at
65,536 entries per view, retained until provider teardown rather than managed
by a kernel-style dentry eviction policy. Private images consume memory backing
proportional to the copied file. Cross-independent-launch provider transfer is
not supported. This is a useful subset, not full kernel FUSE compatibility.

## Descriptor Closure

Confirmed descriptor retirement is separate from reference release and cancelled
publication. Each closed alias issues `FLUSH`; final description release issues
`RELEASE`. Explicit `close` returns the daemon's error after releasing the native
FD slot. `dup2/dup3` replacement and `close_range` wait for completion but ignore
flush errors. No FD-table turn spans a daemon wait, so another thread can reuse
the number immediately. An interrupt returns without replaying the native close;
the queued flush retains its own description until reply or connection teardown.
An `ENOSYS` reply disables subsequent flush requests for that connection, as in
Linux. Ordinary native objects do not acquire provider requests or waits.

Exec retires CLOEXEC aliases before the new image resumes, including a nonleader
thread replacing its group leader. Normal exit relinquishes the task's shared
table ownership after continuation from its terminal stop; the virtual debugger
receives `PTRACE_EVENT_EXIT` before file retirement, as in Linux. The last owner
completes FLUSH before becoming waitable. Neither barrier requires guest-memory access or an injected
wait in the new image. Fatal termination abandons waiting but retains accepted
terminal requests through reply or connection teardown.

## Verification

Native fixtures cover wire validation, queue bounds, device closure, deferred
worker progress, metadata/mutations, failed publication, cancellation, snapshot
invalidation, independent metadata TTLs, late replies, indexed-name collisions,
rename exchange, cancelled-write cursor ownership, bounded watch overflow,
shared-mapping write/truncate visibility across opens and fork, write protection
and carrier FD limits. `test_fuse.py` exercises installed stock
clients in a private runtime under Android UID 2000:

- `rofiles`: mutations, concurrent append, `F_SETFL`, positional IO, inotify
  lifetimes and daemon-wait cancellation. Ordinary operations use stock cache
  TTLs; a separate zero-TTL mount verifies actual daemon waits across
  SIGSTOP/SIGCONT, descriptor reuse and daemon death.
- `portal`: stock document export, Flatpak read/write and live revocation.
- `session`: production graphical-wrapper cleanup with live document exports,
  an unrelated FUSE provider, daemon death and a foreign mount that must remain
  attached. Checks source-file preservation and command exit status. The portal
  case uses this same wrapper instead of a test-only unmount trap.
- `squashfs`: concurrent reads, ELF/script execution, provider-source set-ID
  metadata and nosuid suppression, shared-library loading, read-only shared mmap
  with writable shared-mapping rejection,
  private mmap interrupted by non-SA_RESTART signals with a stopped daemon,
  completion/signal races across independent opens, 2048 repeated mappings under
  a seccomp filter rejecting invalid syscalls, noexec and a file retained through
  lazy unmount. The filter catches early-wait completion that restores registers
  before the cancelled syscall has actually exited.
- `close`: controlled daemon errors, delayed replies, shared FD-table reuse,
  signals, caller death, replacement/range closes, debugger/exec/exit barriers and the
  connection-scoped unsupported-operation cache, with and without a guest filter.

`test_snap.py` separately boots stock systemd and snapd in a disposable Debian-like
store and requires install, run, refresh and removal of hello-world or Firefox.
Firefox must render a known page and produce the expected PNG pixel; successful
namespace construction alone is not an application pass. It uses a
shared root mount before snapd creates image mounts, as required by its
mount propagation. The fixture launcher establishes that topology; it does not
patch snapd or suppress unmount errors. Hello-world also passes under strace;
Firefox is tested without tracing, which heavily perturbs cold namespace setup.
`--app-launches N` requires a fresh profile and validated image on every launch.
The complete application log is retained and checked for loader and child-process
failures: browser recovery must not conceal a failed subprocess behind a good PNG.
A refresh with no available update verifies the refresh command,
not migration to a different snap revision.

`--namespace-only` measures one fresh confined-shell launch per boot; it retains
the snap for another independent boot. `--snap-file PATH` can use a cached
official image with normal signature checks when the external store is
unavailable. `--cold-launch-checks N` also exercises repeated launches within
one boot, with namespace-start/finish markers separate from total elapsed time.
Real cgroup population events let systemd retire completed scopes. The cgroup
freezer parks tasks at owned interception boundaries without injecting SIGSTOP;
new children inherit effective freezing, including the first member of an empty
frozen group. CUPS boot checks exercise snapd's freezer and namespace preparation
through repeated starts and real IPP requests, with the stock Snap timeouts.

Init fixtures mount private `/tmp` and `/var/tmp`, as well as `/run`, before
booting systemd. Its tmpfiles cleanup must not remove another launch's compiler
files.

The Snap fixture uses stock Ubuntu and snap-owned libraries. Default lazy entry
and explicit `--base` share the same filesystem and FUSE owners;
`--machine-entry` additionally requires measured accelerated completions.
Application filters retain Base admission, regardless of the caller's libc.

`--user-session` starts stock `user@0.service`, uses its session bus and requires
a successful transient user unit before invoking Snap. It also works with
`--namespace-only` for a focused boot/delegation/scope check that retains the
installed image. The guest identity is root; the Android executor remains shell.
This is distinct from GUI verification. Non-root guest portal tests use the
packaged fusermount set-ID metadata through ordinary execution, with no
`--admit-elf` override.
The [Firefox Snap GUI fixture](../guest-exec-lab/desktop-workflows.md#ubuntu-firefox-snap)
checks keyboard/pointer input and download/chooser readback under Wayland.
Its hardware WebRender and WebGL checks use the installer's local GPU content
provider; see [Snap GPU drivers](../../docs/graphics.md#snap-gpu-drivers).

Testing Snap is not evidence of AppArmor confinement: the tested Android kernel lacks
AppArmor, and snapd reports its own reduced confinement capabilities. No
application sandbox is disabled to make a test pass.
