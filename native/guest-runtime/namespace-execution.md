# Namespace Execution Experiment

The internal `libmagicdesk_guest_bootstrap.so --namespace ENDPOINT PROGRAM [ARGS]` mode executes
Debian/glibc programs from the imported inode namespace. It does not fall back
to the direct rootfs for unsupported virtual paths. This is an experimental
executor, not a mount namespace, security sandbox or complete Linux ABI.

## Ownership

`namespace_run.c` owns a filesystem service and a separate subreaper guardian.
The guardian retains the service for the complete guest tree, including
double-fork/setsid descendants, and preserves the initial program's exit status.
Cancellation, frontend death and service failure use signalfd/pidfd observation
and bounded termination/reaping. See the [process lifetime contract](process-lifetime.md)
for ownership, exact guarantees, coverage and remaining limits.

`service_main.c` runs the dedicated SQLite owner under the direct bootstrap.
Its private storage never depends on the namespace service it supplies. The
service retains the already-selected real UID and uses umask zero; creation
requests contain modes masked using the requesting process's kernel umask.
There is no emulated root or automatic identity change after a denied operation.

`namespace.c` is the freestanding syscall adapter. `namespace_proc.c` owns
host/proc object selection, using the shared `proc_paths.c` classifier. Paths, FD stat, directory
read/seek and namespace mutations use the typed RPC contract. Data IO, mmap,
fcntl, flock and ordinary descriptor duplication remain native. The client has
no SQLite, libc, mutable process-global cwd, FD cache or shared client lock.

`program_files.c` supplies the same program-open/identity boundary to initial
launch and exec preparation. Both the stock loader and target program are opened
from the selected backend. Glibc still owns linking/TLS/dlopen. Exec carries the
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
  service without using its mask as their policy. Other guest credential contexts
  are not implemented; the tested identity is unchanged shell UID 2000.
- Cwd is a real backing-directory FD retained by the kernel. Relative requests
  capture it with openat; getcwd reconstructs the current virtual parent path.
  Renaming the directory preserves cwd and inherited dirfd identity.
- fstat/newfstatat/statx publish namespace link counts with native metadata.
  Directory read/seek uses the shared open-file-description cursor. Host pipe,
  socket and other unowned descriptors retain native FD semantics.
- chmod, chown, timestamps, truncate and statfs resolve a retained backing FD,
  then use kernel operations under the caller's real identity. Permission failures
  remain failures. Namespace mutations do not yet update every POSIX ctime or
  emit translated inotify events.
- Path xattrs use the retained inode through `fd_metadata.c`, shared with chmod.
  The proc magic link selects that FD's object, including an O_PATH/no-follow
  symlink inode; it does not resolve the guest symlink text a second time.
  Attribute values remain kernel-owned data, not SQLite rows or RPC payloads.
  Reads, writes, lists and removals retain native permission checks, binary values
  and descriptor/hard-link identity. No heap, mutable cache or client lock is added.
  Installing a default ACL through either a path or FD returns ENOTSUP until
  virtual-parent inheritance exists. Import still rejects source xattrs other
  than the kernel-assigned SELinux label; ACL/ownership emulation is not provided.
- Explicit absolute `/proc` and `/dev` mappings use the host with the selected
  current-process/thread magic links described below. Cross-mount symlinks,
  arbitrary proc aliases, relative host-directory traversal and socket path
  translation are not complete. This is not a complete virtual procfs.
- Inotify projection and special-file creation remain unsupported.
  Unsupported operations return errors, not a direct-rootfs retry. Descriptor
  exec/openat2 and guest-owned SIGSYS remain unsupported in both executors.

Unix client destination paths reuse this same file contract through
`socket_calls.c`, including explicitly inherited host-directory aliases.
The namespace does not yet create socket nodes or import live sockets; its
device transport fixture uses abstract endpoints instead. Native credentials
and SCM_RIGHTS are retained. Pathname bind and Unix sendmmsg return ENOTSUP;
returned peer/source addresses are not virtualized. See the
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
renames. A regular-file descriptor has inode identity but no retained virtual
dentry: readlink returns ENOTSUP, even for a single current name. It must not
guess a hard-link name or expose a backing-store filename. Native pipe/socket
link text and unowned host links retain kernel behavior. Detached directory
paths are not fabricated. Link/rename through host path aliases remain unsupported.

All temporary state is invocation-local. The syscall dispatcher keeps metadata
payloads out of the ordinary open/chdir path; RPC request and reply phases reuse
one local wire buffer. File operations allocate no heap or scratch mappings.

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
also check shared hard-link attributes, descriptor access checks and default-ACL
rejection through both path and FD operations.

Unmodified Debian `cp -a` and `tar --xattrs --xattrs-include='user.*'` copy/archive
and restore payload and user attributes, checked after fresh service starts.
Strict `cp --preserve=xattr` returns an error attempting to set `security.selinux`
on the tested firmware in both backends. User attributes are copied, but that
does not make the command successful. The runner records this native policy
limit separately; the adapter never conceals or grants the denied label change.

With `--packages`, unmodified Debian dpkg installs the fixture at version 1.0,
upgrades to 2.0 and purges it. Each phase uses a fresh service process and checks
committed package state, payload and maintainer-script results. The direct rootfs
remains unchanged. Unmodified dpkg-deb also extracts the official gzip archive;
the extracted hard-link pair shares device/inode identity. The direct executor
retains the same failing hard-link controls as a separate comparison.

This is one real package lifecycle, not APT/full-distribution compatibility.
Guest APT, arbitrary maintainer scripts, root ownership requirements,
broader GTK/Qt workflows, notifications, object reclamation, power loss, performance and API 34/16 KiB
coverage remain separate gates. Execution-policy checks described in the main
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

References: [O_PATH and proc descriptor paths](https://man7.org/linux/man-pages/man2/open.2.html),
[proc descriptor semantics](https://man7.org/linux/man-pages/man5/proc_pid_fd.5.html),
[Linux xattr path operations](https://github.com/torvalds/linux/blob/v6.12/fs/xattr.c).
