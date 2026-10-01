# OCI Images And Filesystem Views

The offline image utility accepts a local OCI image layout containing an OCI or
Docker schema-2 Linux ARM64 manifest. It imports layers into a sealed inode store,
creates independent writable instances and invokes the ordinary guest runner.
There is no Docker daemon, registry client, kernel mount or privilege change.
This is image execution, not OCI runtime-spec or Docker Engine compatibility.

## Commands

```sh
magicdesk-guest image import /host/layout /host/image --map-current-user
magicdesk-guest image create /host/image /host/instance
magicdesk-guest image inspect /host/instance
magicdesk-guest image run /host/instance --user current -- /bin/sh
magicdesk-guest image run /host/instance --bind /host/project /mnt -- /bin/sh
```

Destinations must not exist. `--reference TAG` selects an index annotation;
ambiguous or incompatible selections fail. `inspect` returns the image config,
instance/image kind and retained immutable source directory identities.
`run` combines Entrypoint and Cmd; arguments after `--` replace Cmd.
`--entrypoint PROGRAM` replaces Entrypoint, including an empty override to clear it.
WorkingDir and Env come from the image; `--cwd` and repeated `--env KEY=VALUE`
override them. PATH lookup uses the same filesystem view as execution, including
explicit attachments. A nonempty image User requires `--user current` because the
runtime does not impersonate that account. The actual selected executor remains
UID 2000 or UID 0. Programs retain its permissions and network namespace.

An instance is a normal guest store. Existing shell, PTY and graphical recipes
can use it through `--store`, including their explicit graphics routes and
sealed-helper admission. Closing a launch does not delete its store or volumes;
cancellation uses the existing process-tree guardian. Image configuration does
not create a second launch supervisor or a global current distribution.

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

Archive entries never go through a host disk extractor. Absolute entry names and
`..` components are rejected. Whiteouts and opaque directories remove only lower
names before that layer's entries are applied. Forward hardlinks are resolved
without substituting copies or symlinks; unresolved/cyclic links fail. Directory
metadata is accumulated across layers and applied before publication, so final
restrictive modes do not block later-layer extraction into the private staging
tree. Import runs no image scripts.

`--map-current-user` explicitly maps ownership to the caller and removes set-ID
bits. Xattrs, ACLs and special nodes are rejected, not silently imported with
different meaning. This is not faithful root-owned extraction of arbitrary images.
The prepared userspace remains responsible for NSS, DNS, CA certificates and
unprivileged package-manager configuration. Image Volumes, ExposedPorts,
Healthcheck and StopSignal do not provision host resources or change supervision.

`image_publish.c` owns private staging and no-replace atomic publication. Failure
before publication removes only that private tree. An fsync failure after rename
leaves the published destination for inspection; it is not permission to retry
or delete it. Process-kill tests do not establish power-loss recovery.

## Sharing And Copy-On-Write

An object has a stable logical device/inode and a separate current native
backing. Names and hardlinks refer to the logical object. A sealed image supplies
immutable regular-file backings through source directory FDs; snapshots copy
namespace metadata and directory/symlink objects, not regular file bodies.
Native hardlinks are not required. Source paths and device/inode pairs are
validated when an instance owner opens them.

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
on them. `inspect` exposes dependencies; there is no automatic image deletion,
live object GC or reference-counted registry. Removing an instance after its
launches stop does not remove its image or attached directories. The caller
must not edit sealed backing files outside the runtime. Sharing is not a security
boundary: lower descriptors can share kernel locks and native observation effects.

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
pathname creation. Open files keep kernel offsets/data ownership; retained
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

`test_oci.py` covers codecs, metadata, whiteouts, links, malformed inputs,
digest failures and cleanup. `test_snapshot.c` covers sharing, promotion,
hardlink aliases, retained FDs, mmap and file watches. `test_mounts.c` checks
cross-boundary resolution, readonly retained FDs and cursor publication.
`test_oci_runtime.py` imports real Alpine and Debian OCI layouts and runs both
through the production guest runtime under actual UID 2000, without Desktop.
Reports retain image digests, command output and the selected device/build.

The image format contract is [OCI image-spec](https://github.com/opencontainers/image-spec).
