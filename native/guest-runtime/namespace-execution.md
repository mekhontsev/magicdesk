# Namespace Execution

The internal supervisor/bootstrap pair's `--namespace ENDPOINT PROGRAM [ARGS]` mode executes
ARM64 glibc/musl programs from the imported inode namespace. It does not fall back
to the direct rootfs for unsupported virtual paths. This is an experimental
runtime used by named Shroot environments, not a mount namespace, security sandbox
or complete Linux ABI.

## Ownership

`namespace_run.c` owns a subreaper guardian. The guardian retains the supervisor
and its namespace worker for the complete guest tree, including
double-fork/setsid descendants, and preserves the initial program's exit status.
Cancellation, frontend death and service failure use signalfd/pidfd observation
and bounded termination/reaping. See the [process lifetime contract](process-lifetime.md)
for ownership, exact guarantees, coverage and remaining limits.

`fs_worker.c` owns SQLite on a supervisor thread, using native Bionic IO.
Its private storage never depends on the namespace service it supplies. The
owner retains the already-selected real UID and uses umask zero after guest fork; creation
requests contain both the requested mode and the requesting process's kernel
umask, so default ACL inheritance and mode-only creation share one policy.
There is no automatic identity change after a denied operation. Explicit guest
users and sealed-image admission share the supervisor's per-task credential model
without changing the real UID; see [interception](interception.md).

`namespace.c` is the freestanding syscall adapter. `namespace_proc.c` owns
host/proc object selection, using the shared `proc_paths.c` classifier. Paths, FD stat, directory
read/seek and namespace mutations use the typed RPC contract when task-affine
execution is needed. Ordinary open/stat notifications reach the same engine
directly through `namespace_broker.c`. Data IO, mmap,
fcntl, flock and ordinary descriptor duplication remain native. The client has
no SQLite, libc, mutable process-global cwd, FD cache or shared client lock.

`program_files.c` supplies the same program-open/identity boundary to initial
launch and exec preparation. Both the stock loader and target program are opened
from the selected backend. The stock interpreter owns linking/TLS/dlopen. Exec carries the
endpoint in bootstrap arguments, not environment variables, and retains one
seccomp filter and the real kernel cwd/descriptors.

## File Contract

- Atomic open/create resolves symlinks and performs name publication in one
  transaction. O_EXCL races have one winner; mode-zero creation returns the
  requested access-mode FD without reopening it. CLOEXEC is applied in the guest,
  independently of the service's safe descriptor-transfer defaults.
  Regular-file `mknodat` uses the same exclusive creation boundary.
- The caller reads `Umask` from `/proc/thread-self/status`, without temporarily
  mutating a shared fs_struct. Forked clients with different masks share one
  service without using its mask as their policy. Filesystem authorization uses
  the caller's guest filesystem IDs and supplementary groups from the supervisor's
  registry; attached host objects retain real kernel permission checks.
- Cwd is a real backing-directory FD retained by the kernel. Relative requests
  capture it with openat; getcwd reconstructs the current virtual parent path.
  Renaming the directory preserves cwd and inherited dirfd identity.
- fstat/newfstatat/statx publish namespace link counts and logical owners/modes
  with native data attributes.
  Directory read/seek uses the shared open-file-description cursor. Host pipe,
  socket and other unowned descriptors retain native FD semantics.
- chmod, chown and timestamps resolve a retained backing FD and use typed
  filesystem operations with the requesting task's credential context. Truncate
  and statfs retain native data operations after namespace access checks.
  Namespace mutations do not yet update every POSIX ctime.
  `fchmodat2` retains `AT_EMPTY_PATH` and `AT_SYMLINK_NOFOLLOW`, using the
  selected backing FD and the same guest metadata model. Native host fallbacks
  keep kernel permission errors and syscall availability.
- Path xattrs use the retained inode through `fd_metadata.c`, shared with chmod.
  The proc magic link selects that FD's object, including an O_PATH/no-follow
  symlink inode; it does not resolve the guest symlink text a second time.
  Ordinary attributes retain kernel-owned binary values and native checks.
  Stored objects additionally check guest ownership, mode and ACL permissions
  before opening the selected backing for the attribute operation. `user.*`
  follows regular-file/directory and sticky-directory rules; `trusted.*`
  requires guest CAP_SYS_ADMIN before the kernel's own checks. Descriptor calls
  recheck current permissions even when the file was opened before chmod.
  Writes copy up in the same namespace transaction as the permission check.
  Reads and listings resolve the current backing even through a retained lower
  descriptor; listing itself does not impose read permission on file contents.
  POSIX ACLs and file capabilities on stored objects instead belong to the shared
  logical credential/metadata model; they never grant Android authority.
  ACL transfers use a bounded, immutable descriptor payload only for explicit
  ACL operations, without enlarging the common RPC frame or adding a mutable
  client cache. Listing merges logical names with native attributes. Native
  attachments retain kernel ACLs and launch-local readonly policy.
- `/proc` and `/dev` map to the host, except `/dev/shm`, which belongs to the
  guest store. Relative operations based at a host directory recognize these
  boundaries too. Selected current-process/thread magic links are described below.
  Cross-mount symlinks and arbitrary proc aliases remain incomplete.
- Inotify combines backing-inode data events and transactional directory/name
  events through the [shared watch owner](watches.md). Exact hardlink-dentry data
  attribution remains incomplete. General special-file creation is unsupported.
  Unsupported operations return errors, not a direct-rootfs retry.
- `openat2` uses the same transactional walker with explicit beneath/in-root,
  no-symlink, no-magic-link and mount constraints. Contradictory or unknown flags
  fail; cache-only returns EAGAIN. Native host-directory resolution is validated
  before adapting proc image opens. Unsupported crossing semantics return errors.

Unix client destination paths reuse this same file contract through
`socket_calls.c`, including explicitly inherited host-directory aliases.
The namespace creates socket inodes backed by unique abstract kernel endpoints;
bind and pathname publication belong to its filesystem service. Offline import
does not import live sockets. Explicit launch routes
translate selected connect addresses to executor-owned abstract endpoints without
creating filesystem nodes; independent X11 and Wayland GTK clients use this path.
Native credentials
and SCM_RIGHTS are retained. Returned addresses restore the original bound name;
Unix sendmmsg shares this translation with partial-batch and short-stream semantics. See the
[transport coverage and native shell restriction](README.md#unix-client-transport).

RPC deadlines bound failure, not kernel IO duration or remote cancellation.
An error after transmission can leave a committed mutation or advanced directory
cursor. The adapter never replays it. POSIX callers receive an errno, not an
exactly-once guarantee; operation receipts/recovery policy remain future work.

## Proc Object Identity

The adapter recognizes absolute `/proc/self`, `/proc/thread-self`, current
PID/TID and current-thread `/proc/PID/task/TID` paths for `fd/N`, `cwd`, `root`
and `exe`, plus `/dev/fd/N` and the three standard-stream links. Repeated slashes
and `.` components within those prefixes are accepted. Recognized foreign-process
magic links return ENOTSUP; they cannot be assigned this namespace's identity.

Opening a file descriptor alias uses the kernel's proc magic link: it rechecks
permissions and creates a new open file description, with an independent offset.
It is not implemented as dup. Following metadata operations pin the referenced
object with O_PATH and reuse the ordinary namespace FD contract, preserving
virtual link counts, even after all names have been unlinked. Entry/no-follow
operations retain the proc symlink's own identity. `/dev/stdin` readlink still
returns the native link text, rather than pretending it is a proc magic link.

A directory suffix such as `/proc/self/fd/N/child` captures the directory first
and uses the common namespace path dispatcher. Directory enumeration, symlinks,
creation and metadata therefore share normal virtual-directory semantics. For
unowned host directories, suffix operations use the retained native directory
through its proc alias. The guest `root` and `exe` links select the virtual root
and the supplied guest executable, rather than Android's root or the bootstrap.

Readlink can reconstruct a namespace directory's current parent path, including
renames. Regular files retain unambiguous parent/name history, including deleted
names. Any hardlink history makes descriptor-dentry reconstruction ambiguous:
readlink then returns ENOTSUP rather than guessing a surviving name or exposing
a backing-store filename. Native pipe/socket
link text and unowned host links retain kernel behavior. Detached directory
paths are not fabricated. Link/rename through host path aliases remain unsupported.

Explicit current-process/thread cmdline and auxv opens return a guest-image
snapshot backed by an unlinked temporary file. Reads and seek use the ordinary
native descriptor, without a data proxy. Foreign-process snapshots and proc-like
stat metadata remain unsupported. Executable object IDs support later `exe` open/stat
without a retained guest FD, even after unlink, name reuse and close_range.
The original dentry for descriptor-exec readlink is not retained. `AT_EXECFN`
preserves the invocation spelling independently of executable inode identity.

All temporary state is invocation-local. The syscall dispatcher keeps metadata
payloads out of the ordinary open/chdir path; RPC request and reply phases reuse
one local wire buffer. Ordinary file operations allocate no client heap or scratch
mappings; explicit ACL values use a bounded descriptor payload.

## Verification

The device runner uses a new owned laboratory directory on each run. It imports
a prepared rootfs, then executes stock Debian dash/cat/wc/stat from the inode
namespace under UID 2000. No Android installation or user distribution changes.

`test_namespace.c` verifies actual intercepted syscalls, not direct model calls:
atomic/exclusive creation, access modes, umask, native hard-link data/FD/mmap/lock
identity, virtual nlink/statx, open-unlinked exec inheritance, symlinks, directory
cursors, cwd after rename, metadata and real ownership failures. The existing
`test_exec.c` also runs through this backend: bad pointers, failed exec cleanup,
minimum thread stack, asynchronous signal-handler file calls, posix_spawn file
actions and 64 execs with one unchanged filter.

`test_proc.c` runs natively on the build host and through both executors. It
checks six FD spellings, no-follow and exclusive opens, stat/statx link counts,
independent offsets, permission failures, xattrs, directory rename/enumeration,
suffix operations and spawn file actions, cwd/root/exe, native pipes/sockets,
host-directory suffixes, standard input, invalid pointers and descriptor cleanup.
Concurrent workers use libc's minimum thread stack. Open-unlinked descriptors
remain usable after exec; namespace readlink and foreign-process limits are
asserted rather than treated as supported behavior.

`test_xattrs.c` runs on the native build host and through both executors. It
compares retained-FD metadata with native file/symlink metadata, checks binary
values, follow/no-follow behavior, permission and pointer errors, four concurrent
workers on 128 KiB stacks and descriptor cleanup after failures. Namespace tests
also check shared hard-link attributes, descriptor access checks and malformed
ACL rejection through both path and FD operations. `test_service_runtime.c`
checks valid ACLs, named principals, chmod, umask/default inheritance, sockets,
hardlinks and unlinked descriptors through actual intercepted syscalls.

Unmodified Debian `cp -a` and `tar --xattrs --xattrs-include='user.*'` copy/archive
and restore payload and user attributes, checked after fresh service starts.
Strict `cp --preserve=xattr` returns an error attempting to set `security.selinux`
on the tested firmware in both backends. User attributes are copied, but that
does not make the command successful. The runner records this native policy
limit separately; the adapter never conceals or grants the denied label change.

With `--packages`, unmodified Debian dpkg installs the fixture at version 1.0,
upgrades to 2.0 and purges it. Each phase uses a fresh namespace owner and checks
committed package state, payload and maintainer-script results. The direct rootfs
remains unchanged. Unmodified dpkg-deb also extracts the official gzip archive;
the extracted hard-link pair shares device/inode identity. The direct executor
retains the same failing hard-link controls as a separate comparison.

This is one fixture lifecycle, not full-distribution compatibility. Separate
fresh-image APT/APK checks and selected upgrades are documented in the
[application coverage](../../docs/guest-runtime.md#coverage-and-limits).
Arbitrary maintainer scripts, privileged kernel operations,
broader GTK/Qt workflows, exact hardlink-dentry notifications, object reclamation,
power loss and API 34/16 KiB coverage retain their documented limits. Execution-policy checks described in the main
README still apply; X_OK is not a replacement for full kernel execution policy.

The [software GUI fixture](README.md#software-gui) also runs from the imported
namespace. Its Wayland connection survives the service/guardian/guest launch
boundary without extra copies retained by those supervisors. Both a native
memfd and an unlinked namespace file supply wl_shm pixels to MagicDesk's app-UID
compositor. Android pixel inspection, pointer/key delivery and protocol close
use the same fixture as the direct backend, without Desktop or root.
The authenticated GTK profile also imports a 312 MB prepared tree, then runs
stock `gtk3-demo-application` with actual fonts, icons, keyboard input and clean
exit. Manual menu/text/child-dialog checks and the bounded automated smoke test
are distinguished in the main README. No inode-service or syscall changes are
needed specifically for GTK.

The application profile additionally runs stock Mousepad and Galculator through
the installed APK's namespace CLI. D-Bus uses its ordinary pathname address with the
distribution's session policy, activates dconf and retains settings across fresh
sessions. Mousepad saves edited text that a separate guest launch reads back.
Focused watch checks separately cover GIO monitoring and D-Bus registration;
this GUI fixture's network checks cover
prepared NSS, DNS and authenticated HTTPS. The real package-manager workflows
use separate officially prepared images.

References: [O_PATH and proc descriptor paths](https://man7.org/linux/man-pages/man2/open.2.html),
[proc descriptor semantics](https://man7.org/linux/man-pages/man5/proc_pid_fd.5.html),
[Linux xattr path operations](https://github.com/torvalds/linux/blob/v6.12/fs/xattr.c).
