# OCI Images And Filesystem Views

The offline image utility accepts a local OCI image layout containing an OCI or
Docker schema-2 Linux ARM64 manifest. It imports layers into a sealed inode store,
creates independent writable instances and invokes the ordinary guest runner.
The native utility has no registry client, Docker daemon, kernel mount or privilege
change. The app-side [named-environment manager](../../docs/guest-runtime.md#commands)
supplies HTTPS acquisition, names and dependency-aware cleanup using this same API.
This is image execution, not OCI runtime-spec or Docker Engine compatibility.

## Commands

```sh
magicdesk-guest image import /host/layout /host/image --preserve-ownership
magicdesk-guest image create /host/image /host/instance
magicdesk-guest image inspect /host/instance
magicdesk-guest image run /host/instance -- /bin/sh
magicdesk-guest image run /host/instance --user 1000:1000 -- /bin/sh
magicdesk-guest image run /host/instance --bind /host/project /mnt -- /bin/sh
magicdesk-guest image import /host/layout /host/image2 --preserve-ownership --layers /host/layers
magicdesk-guest image rootfs /host/rootfs.tar.gz /host/rootfs-image
magicdesk-guest image backup /host/instance /host/backup.tar.zst
magicdesk-guest image restore /host/backup.tar.zst /host/restored-instance
```

Destinations must not exist. `--reference TAG` selects an index annotation;
ambiguous or incompatible selections fail. `inspect` returns the image config,
instance/image kind and retained immutable source directory identities.
`run` combines Entrypoint and Cmd; arguments after `--` replace Cmd.
`exec` requires an explicit command and ignores Entrypoint/Cmd. `login` uses the
selected account's home directory and login shell, or the explicit command after
`--`. HOME, SHELL, USER and LOGNAME derive from `/etc/passwd`; a missing numeric
account falls back to `/` and `/bin/sh` without creating an account. Image Env
overrides account defaults for run/exec, while login selects account defaults;
explicit `--env` values override both. Both use the same
launch parser and supervised execution path as `run`.
`--entrypoint PROGRAM` replaces Entrypoint, including an empty override to clear it.
WorkingDir and Env come from the image; `--cwd` and repeated `--env KEY=VALUE`
override them. PATH lookup uses the same filesystem view as execution, including
explicit attachments. Preserved-ownership images select their configured User,
or guest root if it is empty. `--user NAME|UID[:GROUP|GID]` overrides it using the
same credential model as ordinary guest launches; names and supplementary groups
come from the image's account files. `--user current` retains the executor's IDs.
A mapped-ownership image with nonempty User requires an explicit user override.
The actual selected executor remains UID 2000 or UID 0. Kernel permissions and
network namespace do not change. `inspect` includes the `guestUsers` policy.
Each instance gets a stable launch hostname derived from its storage identity;
`--hostname NAME` overrides it. The ordinary runner accepts the same option.
This changes guest uname's nodename, not Android's hostname or network namespace.

An instance is a normal guest store. Existing shell, PTY and graphical recipes
can use it through `--store`, including their explicit graphics routes and
sealed-helper admission. Closing a launch does not delete its store or volumes;
cancellation uses the existing process-tree guardian. Image configuration does
not create a second launch supervisor or a global current distribution.

`image resolver STORE TEXT [--replace]` prepares a bounded `/etc/resolv.conf`
under exclusive writable-store admission. By default it preserves nonempty files
and symlinks. Explicit replacement replaces the symlink itself, not its target.
The app-side manager selects DNS policy; the native tool never chooses a resolver.

`image export-tree STORE /GUEST/PATH HOST_DEST` exports a bounded regular-file
tree through the inode API. It skips symlinks, rejects special files and publishes
atomically without replacement (32 MiB, 4096 entries, depth 12). The Android
adapter uses this for XKB data without extracting or guessing the store layout.

## Import Boundary

`image_oci.c` selects descriptors, `image_json.c` uses the existing SQLite JSON
parser, `image_io.c` verifies SHA-256 and bounded archive streams, and
`image_layer.c` applies names through the inode API. Libarchive, zlib, Zstandard
and the SHA-256-only Mbed TLS component link only into the offline image tool,
not the supervisor or syscall adapter. Sources and licenses are pinned in
`image-dependencies.cmake` and packaged with the APK.

Descriptor sizes/digests and uncompressed diff_ids are checked before applying
each layer. Private input snapshots prevent a verified source blob being replaced
between hashing and extraction. Tar, gzip and zstd layers are supported. JSON is
bounded to 8 MiB, each blob/uncompressed layer to 8 GiB, and imported content to
8 GiB/200,000 entries. Duplicate keys/names, NULs, malformed metadata, unsupported
compression, ambiguous indices and digest failures are errors.
`--blobs DIRECTORY` selects an explicit SHA-256 blob directory instead of the
layout's `blobs/sha256`. This lets the registry adapter supply its verified cache
without hardlinks, symlinks or temporary duplicate downloads. All native digest,
size and diff_id validation remains mandatory.

Archive entries never go through a host disk extractor. Absolute entry names and
`..` components are rejected. Whiteouts and opaque directories remove only lower
names before that layer's entries are applied. Forward hardlinks are resolved
without substituting copies or symlinks; unresolved/cyclic links fail. Directory
metadata is accumulated across layers and applied before publication, so final
restrictive modes do not block later-layer extraction into the private staging
tree. Import runs no image scripts.

Import requires exactly one ownership policy. `--preserve-ownership` retains
archive UID/GID and permission bits in logical inode metadata, including set-ID
bits, without changing host credentials or making backing files set-ID.
`--map-current-user` explicitly maps ownership to the caller and removes set-ID
bits. Preserved-ownership imports retain POSIX access/default ACLs and
`security.capability` as logical inode metadata. Neither is installed on host
backings or grants Android privileges. Identical duplicate xattr encodings are
accepted; conflicting values are rejected. Other xattrs, NFSv4 ACLs and unsupported
special nodes are rejected rather than silently imported with different meaning.
FIFO entries retain their namespace metadata and hardlinks; live streams are
created lazily per instance, never copied from an image. Their declared archive
payload must be empty. See [named pipes](fifos.md) for execution limits.
ACL/capability-bearing inputs require preserved ownership.
General set-ID metadata does not grant execution admission.

Character/block-device entries under native `/dev` are omitted with their path,
type and major/minor numbers reported. The same runtime path classifier excludes
guest-owned `/dev/shm`. Omission removes an older-layer placeholder but creates no
host device, guest inode or ordinary-file substitute. Archive paths, duplicates,
links and supported metadata are still validated; symlink ancestors cannot redirect
device omission into another directory. Devices outside that native subtree remain
unsupported, as do ACL/capability-bearing device entries. At execution, `/dev/null`
is Android's real device; unavailable `/dev/console` retains the kernel error.
Interactive terminals use their existing PTY, not an invented system console.

The prepared userspace remains responsible for NSS, DNS and CA certificates.
Image Volumes, ExposedPorts,
Healthcheck and StopSignal do not provision host resources or change supervision.

`image_publish.c` owns private staging and no-replace atomic publication. Failure
before publication removes only that private tree. An fsync failure after rename
leaves the published destination for inspection; it is not permission to retry
or delete it. Imports and backups retain an exclusive lock on their private
`.md-image-<id>` directory until publication or cleanup. A short parent-directory
lock serializes creation with recovery; active writers are never reclaimed.
`image recover-staging DIRECTORY` removes only abandoned staging with the
expected name, type, owner and permissions, without following symlinks. Publication
also recovers its parent's abandoned staging before starting new work. The app's
Java management child binds its native helper lifetime with a verified parent
PID and a parent-death signal. Process-kill tests do not establish power-loss recovery.

Before publishing a new image, the importer removes unreachable namespace objects,
private backings and source references left by replacements or whiteouts. This
operates only on its unpublished staging store, never on live instances or their
open-unlinked objects.

## Sharing And Copy-On-Write

An object has a stable logical device/inode and a separate current native
backing. Names and hardlinks refer to the logical object. A sealed image supplies
immutable regular-file backings through source directory FDs; snapshots copy
namespace metadata and directory/symlink objects, not regular file bodies.
Native hardlinks are not required. Source paths and device/inode pairs are
validated when an instance owner opens them.

`--layers DIRECTORY` selects an existing shared layer pool. `image_pool.c` stores
regular-file bodies by verified uncompressed layer digest and archive-entry ordinal.
It publishes each pool atomically, and concurrent imports adopt the winning pool
before retaining its inode identities. A pool has no guest namespace database;
each image contains one final namespace. Runtime lookup uses the object's direct
source descriptor, without walking a chain of image layers. Input blob digests
and diff_ids are still verified on reuse. Pools are trusted immutable local data,
not a security boundary against another process with the same Android authority.

Guest owners, permissions, ACLs and capabilities belong to each image's logical
objects, not the shared native file. Native data permissions are executor-owned.
Equal byte contents do not imply hardlinks: only archive hardlink entries share
a logical object within an image. Reapplying one layer while an old alias remains
uses private storage for the new object rather than merging those independent
inodes. Writable instances retain the same copy-up path for pooled and ordinary
image sources. No kernel OverlayFS, FUSE, mount or native hardlink is required.

The first mutable open/reopen promotes the logical object under the store's
normal transaction gate. Data, supported xattrs, mode and timestamps are copied
to a private staging file, synced and closed before publication into the watched
object directory. FICLONE is optional; unsupported or denied cloning uses bounded
buffered IO. All aliases switch together. Subsequent read/write/mmap and locking
use native descriptors without per-byte mediation.

Already-open readonly descriptors retain their lower contents; newly opened
descriptors see the upper backing. Descriptor identity lookup recognizes both.
Writable promotion requires actual read access to the lower data: unreadable
lower files are not temporarily chmodded, impersonated or treated as empty.
Internal copy-up IO does not synthesize application modification events.
Logical watch subscriptions follow the object across promotion.

Image directories must remain at their recorded locations while instances depend
on them. `inspect` exposes dependencies; the native utility does not perform
automatic image deletion or live object GC. The app-side library owns persistent
dependency checks for its resources. Removing an instance after its
launches stop does not remove its image or attached directories. The caller
must not edit sealed backing files outside the runtime. Sharing is not a security
boundary: lower descriptors can share kernel locks and native observation effects.

Each open store retains a shared lifetime lock on its root, separately from the
short namespace transaction lock. Source directories retain their own parent-root
leases. Exclusive offline maintenance fails with EBUSY while an owner retains the
store. These leases protect cooperating live operations; they do not replace a
persistent image-dependency catalog or prevent external filesystem deletion.
`remove STORE` and `remove-layer LAYER` are exclusive offline operations. Their
caller must first verify persistent dependents. A successful admission renames
the resource to a deterministic private tombstone before deleting it; repeating
the original removal completes an interrupted tombstone without requiring a
still-valid SQLite database. A conflicting name or live owner is an error.

## Rootfs Archives And Backup

`rootfs` imports a tar, gzip or zstd rootfs through the same bounded inode importer,
with preserved guest ownership and no OCI whiteout interpretation. It creates a
sealed image whose default command is `/bin/sh`. It runs no installation scripts.
Other codecs are not enabled. Archive input is copied into a private bounded
snapshot before parsing; observed source mutation rejects publication.

`backup` requires exclusive store ownership. It emits a self-contained zstd PAX
archive, including lower file bodies, image configuration and guest-user launch
policy. `restore` publishes a new writable store with no lower dependencies.
Names, hardlink relationships, symlinks, FIFO metadata, guest UID/GID/mode,
access/default ACLs, file capabilities, user xattrs and file atime/mtime are
retained. The format carries guest mode independently of libarchive's ACL mode
representation. Native Android SELinux labels are not exported; unsupported
native xattrs fail rather than being silently lost.

Backup is filesystem data, not a process checkpoint. Sockets are omitted with
their paths reported; running processes, FIFO streams, open descriptors and
launch-local attached directories are not saved. Restore creates new backing and
logical inode identities; ctime and sparse-allocation layout are not preserved.
Output publication does not replace an existing archive. The source store and its
immutable dependencies must not be modified through external tools during export.

## Attached Directories

`struct md_filesystem` is the namespace owner's explicit composition of its
inode store, sealed executable catalogue and optional attachments. Both direct
USER_NOTIF requests and task-affine RPC use `md_fs_execute`. The empty attachment
case goes directly to the inode engine. No mount policy enters the inode database.

`--bind HOST GUEST` and `--bind-ro HOST GUEST` belong to one launch. Source and
target must be existing directories. Guest targets `/`, `/dev`, `/proc` and
`/sys`, and overlapping or nested attachments, are rejected. Both endpoints are
retained by descriptors. Component
resolution handles guest/native symlinks, dirfds, `..` across the boundary and
openat2 scopes. Cross-view link/rename returns EXDEV; removing a mount root is
EBUSY. Native permission/SELinux failures remain failures.

Native metadata identity is retained across rename/unlink. Readonly policy also
applies to descriptor-based metadata changes and writable reopens, not just
pathname creation. Guest root cannot bypass host access checks or readonly policy.
Ownership on attached host paths remains native, not stored as guest inode metadata.
Open files keep kernel offsets/data ownership; retained
O_PATH references do not hold extra writable descriptions or file locks. Native
directory reads share the publish-before-cursor-commit contract. Watches use the
existing native-event path rather than a second watcher system.

The view allows at most 16 attachments and 65,536 retained native identities.
References are released with the launch; capacity exhaustion is explicit.
Pathname socket creation/address translation in an attached directory is not
implemented. Existing guest-store sockets and explicit graphics routes remain
separate supported paths. Native host hardlinks require the host's permission;
the inode store's logical hardlink emulation does not apply to host volumes.
These bindings do not implement mount(2), mount propagation, OverlayFS, FUSE,
cgroups or filesystem confinement. A future mount adapter should update this
same view with explicit namespace ownership, not duplicate path translation.

## Checks

`native/guest-exec-lab/test_environment_cli.py` exercises the installed APK's
named CLI through actual UID 2000: public Alpine/Debian pulls, independent stores,
run/exec/login, writable/readonly directory attachments, portable backup/restore
and dependency-aware prune followed by execution of the independent restore.
A real guest HTTP request retains an active store while removal and backup must
return EBUSY; both become available after that process exits.
Its Java catalog/registry fixtures separately cover name validation, ownership
contention, credential scope on redirects, TLS downgrade rejection and cache
digest failures. These workflows need no Termux execution or Desktop session.

`test_environment_workflows.py` extends this to account login, fresh APT/APK
GUI package installation, independent concurrent operations, helper-owned file
exchange, X11/Wayland editing and clipboard, and relaunch after backup, deletion
and restoration. It uses the installed APK and ordinary Start recipes under
actual UID 2000; its virtual display has no Desktop session or HOME lease.

`test_oci.py` covers codecs, metadata, whiteouts, links, malformed inputs,
digest failures, concurrent shared-layer publication, independent equal files,
repeated-layer aliases, unreachable-source cleanup and self-contained backup.
`test_snapshot.c` covers sharing, promotion,
hardlink aliases, retained FDs, mmap and file watches. `test_mounts.c` checks
cross-boundary resolution, readonly retained FDs and cursor publication.
`test_oci_runtime.py` imports real Alpine and Debian OCI layouts and runs both
through the production guest runtime under actual UID 2000, without Desktop.
It reimports into the same layer pool, checks independent modifications and
launches restored userspace without lower dependencies. Reports retain image
digests, command output and the selected device/build.

`test_oci_services.py` exercises stock ARM64 application images through
the selected UID 2000 shell service. It runs image entrypoints and checks loopback
network requests, shutdown and persistent data across separate launches. Service
log events establish readiness; timeouts fail the check. This suite exposes
application requirements beyond base-image shell execution. Passed workflows:

- Nginx 1.30.5 Alpine: default entrypoint, `/dev/stderr` logging, HTTP and
  graceful worker/master shutdown.
- Redis 7.4.11 bookworm: default root entrypoint including setpriv's capability
  sequence, PING, SET/GET, SAVE, shutdown and saved-data reload.
- PostgreSQL 17.11 Alpine 3.23: stock initdb/entrypoint, System V shared memory,
  SQL writes, shutdown and data readback after restart.
- Python 3.13.15 Alpine 3.23: spawned process pool, POSIX shared memory across
  processes, SQLite persistence and HTTP.
- Node 22.23.3 bookworm: worker threads, child exec, file persistence,
  fs.watch and HTTP.
- Apache 2.4.68 trixie: event workers, repeated HTTP requests and graceful stop.
- Memcached 1.6.45 Alpine 3.24: configured non-root image user, TCP set/get,
  atomic increment, deletion and protocol shutdown.
- Eclipse Temurin 21 noble: source compilation, worker threads, mapped-file
  persistence, child process, WatchService and HTTP across independent launches.
- Caddy 2.11.4 Alpine: original file-capability metadata, HTTP serving and
  shutdown through its local admin endpoint.
- MariaDB 11.8.9 noble: stock entrypoint, user/database setup, InnoDB with native
  O_DIRECT, SQL writes and readback after restart using the instance hostname.
- PHP 8.5.11 Alpine: SQLite WAL, commit/rollback, persistence, subprocess locks
  and compression; Ruby 3.4.11 trixie: threads, fork/exec, persistent JSON and HTTP.
- .NET SDK 10.0 Alpine: C# compilation/JIT, async IO, mapped-file persistence,
  subprocesses, filesystem watching and TCP across separate launches.

`test_distribution_runtime.py` covers signed Ubuntu 24.04 APT and CentOS Stream
10 DNF installation/reinstallation, accounts, non-root permission denial,
session D-Bus and a Python multiprocess service. The Arch Linux ARM fixture uses
an unchanged signature-verified official rootfs archive wrapped as an OCI layer;
its imported POSIX ACLs remain visible through getfacl. Package checks explicitly
own and terminate any GnuPG agents; launch completion never abandons descendants.
DNS configuration belongs to the private test instance, not the runtime.
Arch's base userspace and ACL import pass, but its default pacman download
sandbox requires Landlock. On this device the native UID 2000 control returns
ENOSYS for Landlock as well; default package synchronization fails. The runtime
does not turn that missing kernel isolation into a successful no-op.
The fixture's explicit `--arch-without-landlock` disables only pacman's filesystem
sandbox, preserving its syscall filtering and signature policy. With this opt-out,
package installation/reinstallation, account permissions, session D-Bus and Python
SQLite/multiprocess/shared-memory/HTTP workflows pass. Systemd hooks attempting to
change Android device ownership still receive real permission denials; no systemd
instance is booted. Full kernel/initramfs upgrade completion is not established.

Mount identity comes from one launch-local filesystem view: inode root, guest SHM,
native attachments and exposed native mounts share statx and proc mount tables.
Mount IDs are not kernel-global unique IDs. Optional mount lookup does not add a
canonical-path walk to ordinary stat/fstat. Directory FD metadata, empty-path
readlink and reopening with O_NOCTTY/O_NOATIME use ordinary descriptor contracts,
not package-specific rules.
OCI launch and inspection read configuration and image properties under the same
store admission gate as namespace operations. Inspection releases that gate before
writing its result to stdout; a slow reader cannot hold a filesystem transaction.

The Gentoo ARM64 OpenRC stage3 checks use an independently signature- and
SHA-512-verified official archive. The OCI wrapper preserves tar contents; the
production importer reports omission of `/dev/console` and `/dev/null`, supplied
by native `/dev` at execution.
The prepared userspace passes GCC compilation, pthreads, fork/exec and file IO,
and runs Python and Portage's information command. `emerge-webrsync` downloads
the repository snapshot and successfully verifies its OpenPGP signature with
stock gemato. Full repository extraction did not complete within the fixture's
900-second bound, so source-package build/install/remove is not a pass. Portage's
default sandbox FEATURES remain unchanged and their build-time behavior is not
yet established by this check.

Ubuntu Mousepad is checked through both graphical protocols with keyboard input,
save/readback and clean exit. `test_qemu_runtime.py` runs a freestanding x86-64
ELF under the distribution's QEMU user-mode emulator, checking file data,
fork/wait and status. It does not test KVM or full-system emulation.

`test_development_runtime.py` installs stock Debian trixie GCC/G++ and GDB,
compiles/runs C and C++ programs with a shared library, worker thread and file
IO, then separately tests a debugger breakpoint, stepping and backtrace.
Stock Ubuntu GCC/G++ and GDB 15.1 additionally pass live breakpoints, source steps,
locals, backtrace, thread events, signal delivery, fork detach and child exec.
The supervisor owns [nested debugging](debugging.md), separately from kernel
ptrace ownership. The supported request set and untested lifecycle cases remain explicit.

`test_qemu_docker.py` boots the official Alpine ARM64 virtual-machine kernel and
initramfs with Ubuntu QEMU 8.2.2 TCG under actual UID 2000. Inside that VM, Docker
Engine pulls/runs Alpine, verifies a volume across two containers and shuts down.
This is full-system software emulation with its own Linux kernel, not KVM and not
Docker Engine directly on Android. Ports and bootstrap resources remain loopback-only.

`prepare_astra_userspace.py` and `test_astra_userspace.py` exercise a minimal ARM64
userspace assembled from Astra's official 4.7_arm repository: glibc, shell,
fork/exec, hardlinks, archive/compression round trips and core utilities. Repository
Release/Packages/package SHA-256 checks over HTTPS establish consistency; a pinned
release-signing key is not yet configured. This is not full-OS or security-feature
certification. The installed base-files identifies itself as Orel 2.13.1.

The ARM64 netshoot image passes proc network tables, address/route inspection and
listening-socket inspection through ss's proc fallback. NETLINK_SOCK_DIAG remains
denied on the test device. Actual shell policy permits observations denied to the
ordinary Termux UID; this does not grant CAP_NET_ADMIN, raw sockets or firewall control.

These observations are from the RM11/API 36 device identified in
[guest coverage](../../docs/guest-runtime.md), not cross-device guarantees.
`test_service_runtime.c` checks descriptor reopening and path identity, native
O_DIRECT, capability/securebits transitions, file capabilities, POSIX ACLs,
initial stack boundaries, protected copies, application seccomp precedence and
the [shared-memory contract](shared-memory.md). Passing a suite
with staged native helpers does not establish APK packaging; `--build` records
that distinction and verifies uploaded binary hashes in its report.

The image format contract is [OCI image-spec](https://github.com/opencontainers/image-spec).
