# Guest Runtime

Experimental, native-only ARM64 component for the already-authorized Android
shell or root executor. The APK packages its executables, but loads none into
ART and probes no guest kernel capability during application startup. Explicit
guest launches retain the selected UID. This is **not a security sandbox or a
complete Linux ABI**. See the [application contract](../../docs/guest-runtime.md).
No proroot binary, decompiled implementation, PRoot tracer, container manager,
custom glibc linker, root transition or SELinux change is used.

## Boundary

- `bootstrap.c` is a freestanding static ARM64 ELF entry point, without Bionic
  or glibc dependencies. It accepts the already-selected UID 2000 or UID 0, enables
  no-new-privileges and retains one syscall gate at a stable virtual address.
  It does not acquire Desktop, input or HOME.
- `elf.c` maps only the rootfs's stock `ld-linux` PT_LOAD segments and supplies
  its initial stack and auxiliary vector. It reserves its own mapping before
  applying MAP_FIXED, validates bounds and rejects writable executable segments.
  The Debian loader owns dependencies, relocations, TLS and dlopen. This code
  does not implement a glibc dynamic linker or patch executable instructions.
- `trap.c` installs one seccomp filter, forwards selected file and socket syscalls
  through SIGSYS, and admits the bootstrap's raw syscall gate to avoid recursion.
  It covers raw SVC and libc-internal calls equally. `raw.c` / `raw.S` provide
  freestanding primitives, kernel error returns and bounded self-memory copies.
- `file_calls.c` owns file syscall argument translation, separately from signal
  delivery. Its single catalog in `file_calls.h` drives dispatch and filtering,
  so a supported file operation cannot accidentally bypass the adapter.
- `socket_calls.c` owns client-side Unix address translation. Its syscall catalog
  likewise feeds dispatch and filtering. Path destinations reuse the file adapter
  and retain an O_PATH FD throughout connect/send; no second path resolver,
  payload proxy, heap allocation or socket registry is introduced.
- `fs.c` owns direct-backend path resolution: rootfs, cwd/dirfd, symlinks,
  intermediate components and explicit host `/proc` and `/dev` mappings. It
  does not use libc, heap allocation, locks, a mutable cache or thread-local
  errno. Filesystem races are not isolated; this is not a mount namespace.
- `proc_paths.c` classifies explicit current-process/thread proc aliases and
  `/dev/fd`/standard streams for both backends. `namespace_proc.c` pins referenced
  objects and routes namespace directory suffixes through the ordinary syscall
  adapter. Proc file opens retain native reopen semantics, not dup semantics.
- `exec.c` validates the target and handles shebangs before re-executing the
  bootstrap. Kernel exec retains PID, descriptors and the installed filter.
  The bootstrap restores its SIGSYS handler before entering the stock loader,
  without adding another filter. Missing executables return errors to the caller.
- `inode_store.c`, `inode_db.c`, `inode_path.c`, `inode_directory.c` and
  `inode_import.c` form a separate
  [inode namespace](inode-store.md), not a syscall backend. They use SQLite
  transactions for names/parent edges and ordinary kernel objects/descriptors
  for shared data and stable identity, paged directory cursors and atomic offline
  import. SQLite is linked only into the dedicated Bionic service and test
  fixtures, never the bootstrap or signal handler. The APK build pins the
  unmodified SQLite amalgamation by version and SHA-256.
- `fs_service.c` exposes the model through a separate
  [filesystem service](filesystem-service.md). Its event-loop thread
  owns SQLite; the freestanding `fs_client.c` and `fs_wire.c` transfer native FDs
  over per-call Unix sockets without shared client locks. It is a launch-owned
  child executable, not an Android service or persistent system daemon.
- `namespace.c` connects the syscall boundary to that service in the explicit
  [namespace execution mode](namespace-execution.md). `program_files.c` shares
  loader/program opening between initial launch and exec. `namespace_run.c`
  and `process_owner.c` own the service and complete guest process tree through
  a dedicated subreaper. `service_main.c` performs native Bionic storage IO,
  independently of any guest loader. See the [lifetime contract](process-lifetime.md).
- `event_wait.c` supplies monotonic event-driven descriptor waits to RPC and
  process ownership, without polling or a libc dependency.
- `fd_metadata.c` applies namespace chmod and path xattrs to a retained native
  inode under the caller's real identity. Attribute values do not enter SQLite
  or the RPC protocol; ordinary data and FD operations remain kernel-owned.

The stock loader receives `--inhibit-cache --library-path ... --argv0 ...`;
there is no preload library. Rootfs/bootstrap configuration is carried in exec
arguments, not dependent on the guest preserving environment variables. Guest
argv0, supplied environment, PATH lookup and shebangs are covered by fixtures.
Only dynamic AArch64 PIE executables using `/lib/ld-linux-aarch64.so.1` are
accepted. Static, non-PIE, non-glibc-interpreter and setid targets are rejected.

SIGSYS is reserved by the adapter: replacing its disposition returns ENOTSUP,
and it is excluded from guest signal masks. The handler permits nested SIGSYS
when a guest signal handler interrupts translation and accesses a file. All
translation scratch is invocation-local. Exec alone allocates a guarded 256 KiB
temporary stack because libc's posix_spawn child has a small stack. Kernel exec
discards it on success; an error unmaps it. Normal file calls allocate no heap
or scratch mappings. clone/vfork remain kernel operations, not C-handler emulation.

The bootstrap's fixed-address mapping and exact binary remain unchanged
for a running process tree. The app stages immutable content-addressed bundles;
updates never overwrite an active version. Address-space conflicts remain a
launch failure. The internal `--resume`
entry is not an authorization boundary. No speed, full-distribution
compatibility or ordinary app-UID claim follows from these checks.

## Execution Policy

The selected executor identity must be allowed to execute the bootstrap and to map
the guest loader/libraries with executable permissions. The loader mapper uses
private RW mappings followed by final segment protections, never simultaneous
RWX; policies forbidding RW-to-RX transitions can still reject it. Existing
success on one device is not coverage of hardened memory/execution policies.

Guest command preparation checks X_OK and rejects setid targets. X_OK is not a
substitute for the kernel's full execution/LSM checks. O_MAYEXEC-style checks,
documented upstream through [AT_EXECVE_CHECK](https://kernel.org/doc/html/next/userspace-api/check_exec.html),
must be assessed separately from readable files and executable memory mappings.
The adapter does not implement that check: intercepted execveat returns ENOTSUP.
Before supporting an interpreter/linker that uses the interface, validate the
actual kernel ABI and securebits policy, then route checks to the real backing
FD under the existing identity. Never fake a successful check, strip a security
flag, elevate identity or relax SELinux after denial. The tested Debian bookworm
loader does not establish compatibility with future loaders or Android kernels.

## Coverage And Limits

Verified on Nubia NX809J, Android 16 / API 36, Linux 6.12.23 with 4 KiB pages,
through MagicDesk's Shevery-backed shell service, UID 2000 in `u:r:shell:s0`.
The guest uses Debian bookworm glibc 2.36-9+deb12u14, dash and coreutils.

The following checks cover the direct backend. The namespace backend's supported
operations and tests are listed in [its contract](namespace-execution.md).

- Debian shell, child cat, pipelines, numeric identity and exit status.
- File reads/writes, stat/statx, directory enumeration, cwd/dirfd, symlinks including
  absolute targets, O_NOFOLLOW/O_EXCL, rename and unlink.
- chmod/fchmodat, actual chown permission failures, nanosecond timestamps through
  paths and descriptors, no-follow symlink timestamps, truncate, statfs, xattrs,
  atomic rename flags and inotify. Ownership stays UID 2000; changing it to root
  fails rather than manufacturing success or silently changing execution identity.
- dlopen of a guest plugin, per-thread TLS, concurrent file reads, pthreads and
  signals under the unmodified Debian dynamic linker.
- fork/exec, vfork, posix_spawn with file actions/cwd/signal masks, an explicit
  child environment, argv0, PATH, shebang and inherited Unix socket IO.
- 64 consecutive execs preserve PID, no-new-privileges and the number of seccomp
  filters. Failed exec/spawn return to the caller. `/proc/self/exe` readlink/open
  and the supplied AT_EXECFN identify the guest executable. Each exec updates the
  kernel `comm` name to its basename, subject to Linux's 15-byte name limit.
- Concurrent file reads, file calls on libc's minimum 128 KiB thread stack,
  file calls from asynchronous guest signal handlers, invalid pointers returning
  EFAULT and a pathname ending at a protected page boundary.
- Current-process/thread `/proc/.../fd/N`, cwd/root/exe and `/dev/fd/N` aliases,
  independent reopen offsets, real permission checks, no-follow behavior,
  open-unlinked files across exec and native pipe/socket descriptors. Both
  executors run the same `test_proc.c` fixture, including proc-directory suffixes
  in posix_spawn file actions. Namespace regular-file readlink is explicitly
  unsupported; its [contract](namespace-execution.md) separates inode identity
  from the missing virtual dentry identity.
- A bounded direct-backend guest can be terminated, followed by another successful
  launch. The namespace runner separately tests whole-tree lifetime, double-fork,
  setsid, stopped/TERM-ignoring descendants, frontend/service death and concurrent
  launch isolation; this is not protection against hostile processes or kernel stalls.

Negative controls are required, not hidden or counted as compatibility passes:

- execveat/fexecve and openat2 return ENOTSUP. The selected filesystem calls are
  not the complete Linux syscall surface: guest ownership emulation, socket creation/returned addresses,
  arbitrary proc aliases, io_uring and other entry points are not comprehensively
  virtualized. Unselected syscalls retain host semantics. Run trusted fixtures
  only; arbitrary programs can access host resources with the selected executor's authority.
- Hard links return EACCES in both the guest and an equivalent direct shell
  control on this device. They are not replaced with copies or fake success.
  These are controls for the direct backend; the namespace backend supplies
  shared-inode links. No package maintainer scripts are run by preparation.
- `/proc/self/cmdline` and `/proc/self/auxv` retain the kernel's bootstrap view;
  they are not a complete guest procfs. Locales, metadata/ownership
  emulation, Qt and client GPU buffers are not validated. NSS/DNS and GTK coverage is bounded
  by the software GUI checks below.
- Guest-installed seccomp filters, application-owned SIGSYS handlers, alternate
  signal stacks with nested file calls and arbitrary tiny stacks are not covered.
- API 34, ordinary app UID at MagicDesk's targetSdk, other firmware and 16 KiB
  pages need actual coverage; a successful build is not that coverage.

## Unix Client Transport

`connect`, `sendto` and addressed `sendmsg` select path destinations through the
same open-path contract as ordinary files. A retained O_PATH descriptor supplies
the kernel address through `/proc/thread-self/fd/N`; it is closed after the call.
The kernel still checks socket type, socket permissions and peer policy. This
does not turn readable files or accessible directories into permission to connect.
Relative paths and symlinks use the selected file backend's existing semantics.

Launch options `--socket-path SOURCE ENDPOINT` and `--socket-abstract SOURCE ENDPOINT`
declare at most eight exact connect routes to abstract Unix endpoints. Route
configuration is immutable per process and copied into bootstrap exec arguments,
not retained through environment variables. `socket_routes.c` performs bounded
stack-only address translation on the original socket; it does not replace an
FD or its open-file description. Binary abstract suffixes do not match textual
aliases. Duplicate routes, oversized addresses and invalid options are rejected.
The app's [graphical admission service](../../docs/guest-runtime.md#graphical-connections)
owns endpoint creation, peer authorization and transfer to X11/Wayland servers;
the native runtime owns none of that Android policy.

Unrouted abstract addresses, including embedded zero bytes, family-only autobind,
AF_UNSPEC disconnection and other address families retain native behavior.
Connected data IO, ancillary messages, SO_PEERCRED, descriptor flags, shared
offsets and mmap stay kernel-owned. Addressed sendmsg copies only its header and
address; payload, iovecs and control data are passed to the kernel unchanged.

Pathname bind returns ENOTSUP until socket-object creation can be committed by
the namespace owner. Unix sendmmsg is also explicitly unsupported, including
connected batches, rather than accidentally sending unconverted paths. Returned
addresses from getpeername/recvmsg/etc. retain host spelling; reverse namespace
mapping is not implemented. These are client-transport checks, not a complete
Unix socket namespace or permission to create listeners in the guest filesystem.

`test_sockets.c` has separate native-reference, explicit-adapter and intercepted
guest modes. The Termux host tests successful pathname connections, including a
guest-root path resolved by the actual adapter. Device fixtures independently
exercise both Debian executors with abstract stream/seqpacket/datagram endpoints,
a literal inline SVC connect, inherited listeners, SCM_RIGHTS with shared memfd
data/offsets/mmap, native credentials, malformed pointers, permission/type errors
and FD cleanup. The host adapter test is not a seccomp integration test.

On the tested shell domain, native pathname bind fails with EACCES on both an
ordinary shell-owned path and its proc-directory alias, while ordinary file
creation succeeds. This is recorded as a separate LIMIT control, not a passed
pathname transport test and not suppressed or retried as root. Generic shell
connections to externally owned pathname servers remain unverified. Explicit
routes instead pass independent X11 and Wayland GTK clients through the installed
namespace runtime, including reopening Mousepad in the same retained server and
rejecting an incorrect X11 cookie. The route fixture also verifies fork/exec,
environment replacement, SCM_RIGHTS, mmap and unchanged descriptor identity.
An inherited Wayland FD still represents only one connection, not an endpoint
for independently connecting child processes.

## Software GUI

The optional graphics profile supplies authenticated Debian libwayland-client
1.21, libffi, xdg-shell protocol definitions and XKB data. `test_wayland.c` is a
glibc PIE client using the stock Debian library, not a Bionic GUI or a custom
Wayland protocol implementation. It runs through both execution backends with
the existing `graphics.start` / `graphics.execute` shell contract. The retained
compositor stays under MagicDesk's app UID; Binder handoff supplies one connected
FD via `WAYLAND_SOCKET`. Connection transfer uses the shared Wayland adapter,
not a guest-specific display server or elevated renderer.

On the tested API-36 shell domain, four combinations pass: direct/namespace
execution with either a memfd or a created-and-unlinked regular file for wl_shm.
The existing compositor can receive and map both descriptor kinds without label
changes. The observed labels are `appdomain_tmpfs:s0` and `shell_data_file:s0`;
this is device coverage, not permission to relabel other objects. The reverse
keymap-FD transfer is readable by the shell client.

`test_graphics.py` uses a private 1000x700 virtual display without Desktop, HOME
or input-routing changes. It verifies actual Android pixels in four color
regions, pointer press/release, key press/release, configure handling and the
xdg_toplevel close request. Native mapping, Android-host attachment, application
readiness and pixel inspection are distinct checks; attachment alone is not a
render/input readiness signal. Client frame callbacks are not treated as proof
of Android scanout. Production MCP observations bound waits; client execution
has a cancellation deadline, not a startup settling delay.

Each scenario stops only its own graphical session. Cleanup releases the owned
display and console. Exact run IDs, source/build identities, buffer labels,
pixel samples and client logs are recorded in `graphics-results.json`.
This fixture is a first software client, not GTK/Qt, a full Linux desktop,
child-client endpoint, clipboard/IME or client hardware-acceleration coverage.

The separate `--gtk` profile supplies unchanged Debian GTK 3.24.38,
`gtk3-demo-application`, Adwaita assets and DejaVu fonts. Both the direct and
namespace executors display this stock application through the same Wayland
handoff. Manual checks cover typed text, pointer-operated menus, the separate
About dialog and protocol closure of parent and child windows. No GTK, glibc,
syscall-dispatch or compositor patches are needed for these workflows.

`test_graphics.py --gtk` prepares GSettings, GdkPixbuf and MIME caches using
their Debian tools, verifies font discovery, checks rendered toolbar pixels and
quits via GTK's Ctrl+Q action. The test uses the standard keyfile GSettings backend
without a D-Bus session; this is not dconf, portal or accessibility-bus coverage.
Task-surface screenshots avoid Android launch-animation transforms and are
retained with hashes in `gtk-results.json`. A partially delivered Quit chord is
not replayed: acceptance requires both native window disappearance and an actual
zero process exit. Separate pointer/text/dialog checks remain manual, not implied
by the automated smoke test.

The `--applications` profile includes authenticated Debian Mousepad, Galculator,
curl and CA certificates with their selected dependencies. Its guarded test-only
setup supplies NSS entries for real UID 2000, a resolver, CA bundle, machine ID
and toolkit caches. It neither configures a user's distribution nor fabricates
package installation records. The GUI runner uses the installed APK's guest
CLI and compiles the actual app-side `LinuxGraphicalEnvironment` wrapper for
its session commands, rather than maintaining a second D-Bus policy.

`test_graphics.py --application mousepad --network` verifies NSS, DNS and
certificate-checked HTTPS; session-bus activation of dconf and persistent
GSettings; document editing, saving and readback through a fresh guest process;
rendered pixels and keyboard quit. `--application galculator` checks a second
unchanged application with keyboard input, pixels and graceful process exit.
The host-side receipt watcher uses inotify and an event deadline: window removal
alone cannot pass the exit assertion. Complete logs are downloaded with SHA-256
verification, separately from bounded console output. Graphical checks use
the distribution's standard D-Bus configuration with a unique abstract address,
not the demonstration's keyfile-only settings backend. Namespace inotify is
still unsupported, including D-Bus's session-config directory watch.

Known gaps: GTK emits monitor-scale critical warnings during initial output
discovery; the compositor publishes application outputs when Android hosts attach.
Tools reading the unvirtualized kernel command line can still identify the
bootstrap even though the kernel process name follows the guest. These are recorded limitations, not hidden by
log suppression or application-specific environment overrides. GUI success does
not establish complete procfs, Qt, IME, clipboard or GPU compatibility.

## Package Transactions

The optional package fixture uses unmodified Debian dpkg 1.21.23 and tar 1.34.
The namespace backend passes installation at version 1.0, upgrade to 2.0 and
purge, checking committed database state, payload and actual postinst/postrm
results across fresh service processes. It also extracts the official gzip
archive and verifies the hard-link pair's shared inode. These checks do not
modify the direct rootfs. This is a fixture lifecycle, not full Debian/APT
compatibility.

The direct backend retains independent negative controls:

- Package inspection/decompression and dpkg architecture configuration succeed.
- `dpkg --force-not-root --install` unpacks the lab package and executes its real
  postinst, but exits 2 when hard-linking the database's `status-old` backup.
  `status` remains empty and `status-new` contains the pending state. A postinst
  marker or a package query is not proof of a committed transaction.
- The unchanged official gzip 1.12-1 archive also fails extraction: its
  `bin/uncompress` is a hard link to `bin/gunzip`. Preparation retains this archive
  without extracting or rewriting its links. This is not a complete Debian base.
- These outcomes are recorded as `limitation: true` / `LIMIT`, separately from
  successful capabilities. The fixture root is disposable; no user distribution,
  Android package, system path or Termux package database is changed.

The isolated inode-store fixture validates shared native data/FD/mmap identity,
indexed link counts, atomic namespace operations, cross-process contention and
23 deterministic SIGKILL recovery boundaries. It models regular files,
directories, symlink inodes, parent relationships and stable directory FDs, not
a full guest filesystem. Its 189 syscall
comparisons run both directly on the build host and through the guest path
adapter on the device. The service fixture verifies FD transport, concurrency,
nested signal calls, shared directory read/seek offsets across fork/exec/service
restart and explicit uncertain outcomes after lost replies. Offline import copies
the prepared Debian tree in one transaction, with bounded input, explicit metadata
limits and three additional SIGKILL checks. Native source hard-link import needs
another host: current Android identities deny fixture link creation. Shell also
denies source FIFO creation; its rejection is tested on the Termux host.
The namespace fixtures also test actual syscalls, shared FD/cwd across exec,
atomic open/mknodat creation, client umask, metadata permissions and path/FD
xattrs. Debian `cp -a` and tar user-attribute archive round trips pass. Strict
`cp --preserve=xattr` retains the native SELinux label-write denial, in both
backends; a partially copied file is not a successful strict copy. Default-ACL
installation is explicitly rejected until virtual-parent inheritance is modeled.
Before exposing APT,
complete ABI coverage and lifetime semantics. Do not replace links with copies,
add dpkg-specific exceptions, manufacture success or fall back to root.

## Build And Run

Current build workflow uses Termux's Clang/LLD, SQLite development library, the
host-only `libandroid-spawn` for the native proc test,
Node.js, GnuPG/gpgv, xz, dpkg-deb and tar as development tools.
`libmagicdesk_guest_bootstrap.so` has no interpreter, dynamic dependencies or
relocations; Termux is not its execution provider. Guest artifacts depend on the
selected Debian libc, not Bionic. The external lifecycle test driver uses Android's
Bionic to observe the executor independently. Build inputs use public ELF and Linux kernel
ABI headers, not glibc-private layout tables.

From the repository root:

```sh
node native/guest-exec-lab/prepare.mjs build/guest-exec-lab
sh native/guest-exec-lab/build.sh build/guest-exec-lab
python native/guest-exec-lab/test_device.py build/guest-exec-lab
# Include namespace package transactions and direct-backend negative controls:
python native/guest-exec-lab/test_device.py build/guest-exec-lab --packages

# Separate graphics profile; cached archive bytes are authenticated again:
node native/guest-exec-lab/prepare.mjs build/guest-exec-gui-lab --graphics --cache build/guest-exec-lab
sh native/guest-exec-lab/build.sh build/guest-exec-gui-lab
python native/guest-exec-lab/test_device.py build/guest-exec-gui-lab --packages
python native/guest-exec-lab/test_graphics.py build/guest-exec-gui-lab

# Real GTK application; host APT selects dependencies, ImageMagick reads test PNGs:
node native/guest-exec-lab/prepare.mjs build/guest-exec-gtk-lab --gtk --cache build/guest-exec-gui-lab
sh native/guest-exec-lab/build.sh build/guest-exec-gtk-lab
python native/guest-exec-lab/test_device.py build/guest-exec-gtk-lab --packages
python native/guest-exec-lab/test_graphics.py build/guest-exec-gtk-lab --gtk
```

Preparation requires a fresh output directory. It downloads a small set of
Debian ARM64 packages over HTTPS. GnuPG verifies the Release signature against
the pinned Debian 12 archive primary fingerprint
`B8B80B5B623EAB6AD8775C45B7C5D7D6350947F8`, then the Release -> Packages -> .deb
SHA-256 chain is checked. Key material is downloaded from Debian but not trusted
without that pinned identity. Extraction runs no maintainer scripts. The retained
`manifest.json` identifies exact versions, digests, trust anchor and which
archives were extracted. This curated build fixture is not a full installed base;
its moving suite is not a pinned snapshot. The GTK profile uses build-host APT's
URI plan over the already authenticated index in an isolated state/configuration
directory, with no downloads, installation or maintainer hooks performed by APT.
It selects `dbus-x11` instead of the systemd session alternative. Each planned
archive is matched back to the authenticated index and independently hash-checked.
Host extraction retains gzip, perl and perl-base as archives only because their
hard links cannot be created on this host; it does not substitute copies or
symlinks. The GTK fixture does not need those executables. This is build-time
dependency selection, not a claim of a configured Debian base or working guest APT.
An explicit `--cache` can reuse a prepared directory's signed index and matching
archives without changing its sysroot. Cached metadata and packages still pass
the pinned signature and digest checks. The graphics profile additionally needs
a host `wayland-scanner`; protocol code is generated from the selected Debian
protocol package, not copied from the APK's compositor build.

The device runner reads the existing MagicDesk MCP entry in `~/.codex/config.toml`
without printing credentials. It refuses a service UID other than 2000, uses
`scripts/mcp-client.py` for verified upload, and opens/closes a headless console.
It neither changes service identity nor installs an APK. Every run gets its own
shell-owned `/data/local/tmp/md-guest-lab-...` directory. Artifacts remain there
for inspection; `build/guest-exec-lab/device-results.json` records the path,
device/build identities and all commands/results. Cleanup must address that
exact owned directory, not other Linux environments.

`build.sh` also runs host path-resolution, inode-store, offline-import,
filesystem-service, retained-FD metadata, proc-descriptor and socket
native-reference/explicit-adapter assertions. It checks the freestanding RPC
client's dependency closure and rejection of a modified Release, a truncated
signature and a missing key. Device commands have failure/cancellation time
bounds; there is no readiness polling or settling delay.
Desktop self-tests are unrelated and are not launched.

References: [glibc loader interface](https://man7.org/linux/man-pages/man8/ld.so.8.html),
[kernel seccomp contract](https://www.kernel.org/doc/html/latest/userspace-api/seccomp_filter.html),
[Debian archive keys](https://ftp-master.debian.org/keys.html),
[dpkg non-root mode](https://manpages.debian.org/bookworm/dpkg/dpkg.1.en.html),
[Unix address and credential semantics](https://man7.org/linux/man-pages/man7/unix.7.html),
[MagicDesk privilege boundaries](../../docs/privilege-modes.md).
