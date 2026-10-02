# Guest Execution Runtime

MagicDesk includes an experimental native ARM64 Linux execution adapter.
It runs prepared ARM64 ELF programs with glibc or musl through the explicitly selected shell or root
executor, without PRoot, chroot, Termux or a distribution manager. It is not a
security sandbox: guest programs retain the caller's authority and unadapted
syscalls can access Android resources. Use trusted programs and prepared stores.

## Boundaries

`native/guest-runtime` is C/assembly plus a standalone CMake build. It has no
Java tree, JNI entry points, Android framework policy or display ownership.
The app owns typed launch recipes, `.desktop` storage and lazy helper staging.
The existing shell, PTY and graphical services own invocation and presentation.
Renderer processes remain unprivileged.

Each launch captures its store, guest user, guest home, guest cwd, argv and graphics
connection. There is no current distribution, global guest root or singleton
per launch method. Separate guest stores and independent PRoot/chroot entry
scripts can be used concurrently. Each guest launch owns a supervised process tree
and one native namespace worker inside its supervisor; cancelling it does not cancel another launch.
Stores persist independently of those processes. Metadata operations on the
same store acquire a kernel file lock before entering SQLite and release it
after commit or rollback. Separate launches can share a store without exposing
ordinary internal transaction contention to guest syscalls. There is no polling
or transaction replay; data IO through opened descriptors stays native. An
external database writer that bypasses this gate still produces an explicit error.
The supervisor handles regular-file seeks through retained kernel descriptors
without namespace RPC; directory cursors remain namespace-owned. Filesystem
replies retain their connection until client release, with event-driven progress
and a bounded peer lifetime.
Ordinary open/stat and directory-read requests are handled directly by that worker through seccomp
notifications, without invoking a guest-side RPC. Protected accesses, O_PATH,
namespace mutations and directory seeks retain the task-affine adapter. Directory
reads publish their bytes before advancing the shared cursor; failed or partial
output cannot cause an automatic replay of a committed read.
Both paths use the same namespace engine and sealed-image catalogue.
Operation results and caller-owned output are independent of RPC framing; direct
directory reads use one reusable 64 KiB worker buffer rather than an RPC-sized batch.
The namespace owner reuses compiled queries and transaction programs, never cached
metadata or transaction snapshots. Node reads include transactionally maintained
name and child-directory counters; stat and directory membership need no counting
subqueries or second logical lookup. Native attributes and access checks remain live.
Optional `--statistics` profiles syscall stops, filesystem
operations, SQLite and CPU costs without per-call logs or clock sampling
on ordinary launches.

Actual UID 2000 and UID 0 are accepted without switching identity. Root is a
user choice, not a fallback or prerequisite. Neither the renderer nor a failed
command is elevated. PRoot continues to use the explicitly selected Termux
executor; prepared chroot scripts retain their existing root requirements.

## Commands

In a newly opened MagicDesk Shell console or terminal:

```sh
magicdesk-guest --probe
magicdesk-guest --import /absolute/prepared-rootfs /absolute/new-store
magicdesk-guest --store /absolute/store --home /tmp --cwd / -- /bin/sh -l
magicdesk-guest --store /absolute/store --user root -- /bin/sh -l
```

Import accepts an immutable prepared tree, not an archive or distribution name.
It publishes atomically into an empty store and retains real metadata checks.
The default import bounds are 2 GiB and 200,000 entries. Unsupported objects,
metadata, source mutation, overlap and an existing populated store are errors.
It runs no package scripts, downloads nothing and does not manage mounts.
`--preserve-ownership` on import retains source UID/GID, permission and set-ID
metadata in the inode store, including the root directory. Physical backing
files still belong to the executor and never acquire kernel set-ID bits.

Local OCI layouts use the separate offline image command:

```sh
magicdesk-guest image import /absolute/oci-layout /absolute/image --preserve-ownership
magicdesk-guest image create /absolute/image /absolute/instance
magicdesk-guest image run /absolute/instance -- /bin/sh
```

Instances share immutable file bodies and copy up on mutation. Image Env,
WorkingDir, Entrypoint and Cmd feed the ordinary guest runner. `--bind HOST GUEST`
and `--bind-ro HOST GUEST` attach existing directories to one launch, also for
ordinary `--store` commands. Import/staging does not probe guest-execution kernel
capabilities; execution does. See [images and filesystem views](../native/guest-runtime/images.md)
for formats, explicit ownership mapping, source lifetimes and limitations.
This provides OCI image execution, not Docker Engine or a container isolation boundary.
The [application-image checks](../native/guest-runtime/images.md#checks) exercise
stock entrypoints, network requests and persistent data, separately from import
and base-image shell execution. Unsupported image metadata and kernel interfaces
remain explicit errors.

The shortcut editor's Shell Linux method accepts a prepared guest store.
Its optional User field accepts the same guest user/group selection as the CLI.
Terminal commands use the shared retained PTY. Graphical recipes currently use
X11 or Wayland and an explicit host-visible XKB directory.
`X-MagicDesk-GraphicsConnection=routed` selects independent guest connections;
ordinary recipes select `auto`. Each graphical launch prepares
its own guest `XDG_RUNTIME_DIR` and D-Bus session. File-environment identity remains distinct from
host paths; an unavailable guest-file helper is an error, never host fallback.

## Graphical Connections

The selected command service owns a session-scoped abstract Unix endpoint and
admits only that executor's actual UID. It transfers each accepted connection
over Binder to the app-UID protocol server. Binder carries admission and FD
ownership only; protocol bytes and SCM_RIGHTS buffers travel directly through
the connected Unix socket. There is no stream proxy or privileged renderer.
Closing the session or losing its owner closes admission; outstanding FD handoff
has a bounded acknowledgement deadline.

Explicit `--socket-path SOURCE ENDPOINT` and `--socket-abstract SOURCE ENDPOINT`
arguments map exact guest connect addresses to that endpoint. Routes survive
fork/exec and environment replacement. The syscall adapter preserves the original
socket descriptor and kernel open-file description; no socket-node fabrication,
per-syscall allocation or descriptor registry is involved. Other destinations
retain their normal permission checks. General guest pathname sockets use the
inode namespace described below, independently of graphical routes.

Wayland clients receive an absolute `WAYLAND_DISPLAY` and may establish separate
connections, rather than share one inherited stream. X11 maps both the pathname
and abstract display address. Its normal MIT-MAGIC-COOKIE-1 handshake remains
mandatory. Each guest launch writes the supplied authority into its private
runtime directory; session cookies are not persisted in `.desktop` recipes.

## Guest Credentials

`--user NAME|UID[:GROUP|GID]` selects a launch-local guest identity. Names and
supplementary groups are resolved from that store's `/etc/passwd` and `/etc/group`,
not Android or another distribution. An explicit group suppresses automatic
supplementary-group membership; numeric UID:GID needs no account entry. Ordinary
launches without this option retain the executor's IDs and supplementary groups.
Preserved-ownership OCI images use their configured User, or guest root when it
is empty. `image run --user current` explicitly retains the Android identity.

`guest_identity` owns real/effective/saved/filesystem IDs, supplementary groups,
credential drops, virtual capability sets/securebits and no_new_privs. The supervisor tracks them per task across
fork, threads and exec. Libc's process-wide set-ID coordination remains libc's
responsibility. Auxv and admitted-image transitions use this same model.
`credential_registry` publishes immutable snapshots to the filesystem worker;
both USER_NOTIF and RPC use them. RPC checks the actor TID against the kernel
peer TGID. The registry lock protects snapshots only, not filesystem IO, and
ordinary requests allocate no group arrays.

`inode_metadata` enforces directory search, file access, ownership changes,
sticky directories, setgid-parent inheritance, watch registration and timestamp permissions against
logical inode owners/modes. `access()` checks real credentials for the whole
path; `faccessat2(AT_EACCESS)` uses effective filesystem credentials. File data,
mmap and already-open descriptors retain native kernel semantics. Metadata is
shared by hardlinks and retained across copy-up and independent launches.

This is virtual guest root, not Android root or a security boundary. Native
attachments, `/proc`, `/dev`, resource limits and SELinux retain real kernel
authority. Virtual capabilities authorize only implemented guest operations;
they do not become Android kernel capabilities. Guest capget/capset, KEEPCAPS,
bounding/ambient sets and exec share the same credential model, including
UID-before-GID service entrypoints. Internal Unix connections publish captured guest peer IDs
and groups; explicit SCM_CREDENTIALS messages use the same identity model.
External peers still see real Android credentials. Unmodified session D-Bus can
run as guest root, including independent clients sharing the store, without
disabling authentication. See the [IPC credential contract](../native/guest-runtime/ipc-credentials.md)
for ownership, implicit credential messages and transport limits.
General set-ID metadata is not executable admission: only explicitly admitted,
sealed ELF helpers receive a set-ID transition. Stored file capabilities are
logical metadata, not Android capabilities or ordinary-exec admission.
POSIX access/default ACLs use the same guest identity model for named users,
groups, masks, chmod and parent inheritance. Their numeric IDs never become
ACLs on Android backing files. Objects without ACLs retain the ordinary mode
check without an extra ACL query. No kernel permission denial is fabricated as success.

Guest kernel audit is not implemented. `socket(AF_NETLINK, ..., NETLINK_AUDIT)`
returns EPROTONOSUPPORT before reaching Android's global audit service, regardless
of the selected real or guest UID. Stock account tools use their normal no-audit
path. This neither disables Android audit nor claims that a record was logged;
other socket protocols and real kernel denials retain their normal behavior.
Application seccomp ERRNO/TRAP/KILL actions still take precedence.

## Prepared Userspace

The store must contain the application's complete matching libraries, data and
configuration. The runtime does not invent a Linux user, package records, DNS
servers, certificates or a machine ID. In particular, D-Bus needs an NSS entry
for the selected guest UID in the prepared system. Network clients need its
resolver configuration and CA trust store. GUI toolkits need fonts, icons and
their normal GSettings/GdkPixbuf/MIME caches.

`LinuxGraphicalEnvironment` owns one graphical-session wrapper for guest,
PRoot and chroot recipes. The distribution supplies `dbus-run-session` and
`dbus-daemon`, with its standard session configuration, listen address,
credential authentication, activation and limits. `dbus-run-session` owns
readiness and shutdown; the wrapper owns a private `XDG_RUNTIME_DIR`.
MagicDesk ships no D-Bus daemon and does not reuse Android's or Termux's bus.

## ELF And IPC

The bootstrap maps the main ELF and its optional absolute `PT_INTERP`, validates their
load segments before exec and supplies a kernel-style initial stack and auxv.
The initial stack has explicit inaccessible guards and a grow-down mapping,
preserving the kernel's stack gap when applications reserve adjacent address space.
There is no distribution or loader-name allowlist. The selected stock dynamic
linker owns relocations, dependencies, TLS and `dlopen`. A native supervisor uses
selective seccomp TRACE and USER_NOTIF for adaptation; hot data IO, memory, futex
and signal operations stay native. Guarded scratch-stack leases are recycled per
address space, independently of libc thread-stack size. Fork, vfork and thread
clone retain kernel lifetimes. The application owns SIGSYS and alternate stacks.

Static PIE and fixed-address ET_EXEC use the same loader. ELF load alignment is
retained, including 2 MiB alignment; an occupied fixed-address range fails without
replacing existing mappings. Large valid images are bounded by address arithmetic
and the kernel's mapping limits, not a separate image-size cap. Freestanding ELF
without an interpreter or mapped program-header table is also supported. This
does not admit set-ID or writable/executable load segments.

Exec preparation retains both the validated ELF and its optional interpreter
across bootstrap exec. The resumed bootstrap maps those exact descriptors without
reopening names or repeating command preparation; failed exec releases both.
Path opening returns the canonical dentry path and object ID from the same namespace
transaction. That identity travels with the image across exec, rather than being
resolved again after opening or entering the new process. Descriptor exec captures
the retained object's identity independently of its current names.
Descriptor and relative-dirfd exec include open-unlinked programs. CLOEXEC scripts
fail rather than losing their interpreter input. Bounded shebang parsing accepts relative interpreters and a
short header without a newline, preserves the optional argument as one string,
and rejects a truncated interpreter name. `AT_EXECFN` retains the caller's spelling; descriptor exec uses
the Linux `/dev/fd/N[/suffix]` form. Namespace `openat2` validates its extensible argument structure
and supports scoped beneath/in-root resolution, symlink restrictions and mount
boundaries. Cache-only resolution returns EAGAIN; unavailable host-crossing
semantics fail explicitly. The direct backend does not implement scoped resolution.

Pathname Unix sockets are namespace inodes whose native transport is an
abstract kernel socket. The filesystem service commits bind and name publication;
ordinary payload traffic and SCM_RIGHTS stay in the kernel. Connection identity
and explicit credential messages use the separate IPC metadata authority. Names, permissions,
relative addresses, returned addresses, rename, stale listeners and unlink/rebind
are tested. Unlinking a name does not invalidate existing connections. Different
stores have independent names; explicitly abstract sockets retain the host's
shared abstract namespace and are not isolated.
Named pipes use the same namespace and kernel pipe payloads, without requiring
native FIFO creation under Android shell policy. Peer-open rendezvous is
task-affine, outside namespace transactions. See the [FIFO contract](../native/guest-runtime/fifos.md)
for readiness and watch limitations.
Unix `sendmmsg` reuses address and ancillary adapters without copying payloads.
Credential-free sends need no per-message buffers. Tests cover partial batches, short stream writes, failed
result writes and SCM_RIGHTS. Unadapted socket operations are not thereby certified.

`/dev/shm` belongs to the guest store, including access relative to a `/dev`
descriptor or cwd. Execution preparation creates this directory with mode 01777
and guest-root ownership when absent; it does not overwrite existing metadata
or change the sealed image. POSIX shared memory, mmap, descriptor passing and unlinked
data work across independent launches. Store-scoped System V shared memory uses
native mapped backing files and a separate lazy IPC authority; it does not require
kernel SysV IPC support. See [shared memory](../native/guest-runtime/shared-memory.md)
for supported operations and lifecycle limits. Inotify combines native backing-inode
data events with transactionally committed directory/name events. Independent
watchers sharing a store receive create/link/unlink/rename events without polling.
Subscriptions and bounded queues belong to the existing namespace worker; watch
reads use lazily armed descriptor classes and retained kernel-object identity.
Protected readers copy in their own task without changing dumpability; queue
delivery is acknowledged before consumption is confirmed. See the
[watch contract](../native/guest-runtime/watches.md) for descriptor, hardlink,
protected-copy and kernel limits. The current store format is 12;
older stores are rejected, not migrated or deleted.

## Optional Kernel Support

Guest executables are never loaded into the app or shell service with
`System.loadLibrary`. `magicdesk-guest` stages a content-addressed immutable
version only when invoked. A launch first probes pidfds, subreapers, faccessat2,
executable mappings, selective ptrace and seccomp notifications in a child process. Probe failure reports
the failed operation and errno. Other tools, Desktop, Termux and prepared chroot
remain independent; the APK and Desktop SDK floors remain API 34 and API 35.

The same bootstrap binary must remain available throughout a guest tree because
exec reenters its syscall gate. New APK versions therefore stage new bundles,
never repair or replace a live version. Old bundles are retained rather than
garbage-collected while detached terminals or descendants may still use them.

## Coverage And Limits

Device coverage is RM11/NX809J, API 36, Linux 6.12.23, 4 KiB pages, actual
UID 2000. Build and injected missing-syscall checks do not emulate old kernels,
API 34, root execution or 16 KiB devices. Those remain separate validation needs.

The native fixtures exercise Debian bookworm/trixie glibc and Alpine 3.23 musl, shell/exec, threads/signals,
namespace hard links and package transactions, process-tree cancellation and
software Wayland/GTK rendering with input. The installed runtime also runs
unchanged Debian Mousepad and Galculator on a private virtual display without
Desktop. Checks cover Mousepad editing and saving, fresh-process file readback,
keyboard quit with actual zero process exit, D-Bus activation of dconf and
settings persistence across independent sessions. Prepared NSS lookup, DNS and
HTTPS with certificate verification work on the tested device.
Routed X11 and Wayland both run Mousepad and Galculator, including repeated
independent clients in one retained session. The X11 negative check rejects an
incorrect cookie before a window is mapped.

Installed `.desktop` recipes use the shortcut editor's actual launch builder.
Both protocols pass bidirectional Unicode clipboard exchange, Mousepad saving
with fresh-process readback, simultaneous Mousepad/Galculator launches sharing
one store, recipe reuse, and protocol close followed by actual zero-status
process exit. Window destruction alone does not end a still-running launch
command. File workflows check child-dialog cancellation, opening another guest
file, Save As, overwrite confirmation, directory creation and inaccessible paths.
Document titles and actual contents are checked independently. Input awaits the
dialog's Android host and focus, not merely its native map; X11 transients use
native-family events rather than assuming entries in the toplevel catalog.
The separate rapid repeated-digit Galculator check exposes the application's
100 ms toggle-button reset: a second accelerator can toggle the digit off and
be ignored by its handler. Client-side Wayland tracing confirms both key
press/release pairs; repeated digits work in Mousepad. No input pacing or
application-specific runtime workaround is applied. These checks do not
establish compatibility with arbitrary toolkits or GPU clients.

The Debian trixie Qt 6.8.2 software fixture passes real Android IME composition,
Unicode commit, append, field switching, private PINs, caret geometry and IME
insets, plus bidirectional Unicode clipboard exchange, a dependent dialog and
zero-status protocol closure. The strict surrounding-text correction check
fails: Qt can omit text-input-v3's final commit after deletion. A separate run
explicitly omits that stage; it is not counted as a correction pass. The same
client behavior and protocol boundary are documented in [Wayland IME coverage](wayland.md).

Alpine's stock Mousepad passes the same X11 and Wayland window, pixel, keyboard,
save/readback and zero-exit checks. Both libc fixtures cover tiny thread stacks,
nested signal file calls, failed exec, vfork, spawn file actions/masks and 64 execs
without accumulating seccomp filters. Independent launchers share pathname IPC,
POSIX SHM, file-data notifications and open-unlinked lifetimes; another store
does not resolve their names.

The prepared Debian Xfce session runs in one retained X11 viewer without
MagicDesk Desktop: xfwm4, panel, desktop, terminal and Mousepad. Tests check
editing, saving, WM resize, viewer detachment/reopening and zero-status logout.
Readiness awaits both shell surfaces and Xfce's D-Bus Idle state, not a delay.
This fixture selects software rendering and lacks optional system-bus services;
it is not a complete booted Linux system or a GNOME certification.

A separate Linux Mesa 26.2.3 Turnip/KGSL fixture runs `vkcube` on Adreno 840
under UID 2000 through both Wayland and X11. It checks changing Android pixels, 600 submitted
frames and zero process exit. The driver is built from unmodified Mesa sources
and installed only in the test store, not Android or the APK. Device enumeration
alone is not this check. Other GPUs, drivers, zero-copy presentation and performance
comparisons remain unverified.

Fresh preserved-ownership images run ordinary Debian APT and Alpine APK as guest
root under actual UID 2000. Focused checks cover signed repositories, dependency
installation, scripts/triggers, reinstallation, removal and subsequent installation,
without force-not-root or package-manager sandbox overrides. Debian installs
hello, curl, jq and ca-certificates including certificate update triggers; Alpine
installs curl, jq and D-Bus including its logical set-ID helper metadata.
`test_credentials_runtime.py` records exact images, commands and build identity.
These are bounded package workflows, not arbitrary maintainer-script or
complete-distribution compatibility. Kernel mounts, device creation, capabilities
and system-service startup may still fail. Executable-memfd scripts remain subject
to the actual Android execution policy.

Additional image checks cover Ubuntu 24.04 APT with systemd package configuration,
CentOS Stream 10 DNF, logical account management and authenticated session D-Bus.
Ubuntu Mousepad is checked through X11 and Wayland with editing, saved-file readback
and clean exit; QEMU user-mode runs an x86-64 ELF with file IO and fork/wait.
These are userspace workflows, not booted systemd or virtual-machine certification.
The [image coverage](../native/guest-runtime/images.md#checks) also records server,
language-runtime and compiler checks. Stock GDB live debugging uses the
[nested debugger contract](../native/guest-runtime/debugging.md), with explicit
request and lifecycle limits. Arch package testing on a kernel without Landlock
requires an explicit filesystem-sandbox opt-out; ordinary userspace does not.
Minimal Astra ARM64 userspace and Docker inside a QEMU TCG virtual machine have
separate checks; neither establishes booted-distribution compatibility on Android.
Gentoo ARM64 stage3 passes userspace and GCC/pthread/fork/file checks. Image import
reports omitted native `/dev` nodes instead of creating devices. Its signed repository download
passes; complete Portage synchronization and package builds remain unverified.

Official ARM64 VS Code with its Electron sandbox flags retained and Microsoft
C/C++ uses stock GDB through both the integrated-terminal FIFO transport and
stdio pipe transport. The fixture checks a breakpoint, stack/local inspection,
step, changed value and normal exit; it does not establish full sandbox isolation.
Ubuntu developer workflows cover authenticated Git clone/push over loopback SSH,
CMake/Ninja build/test/install/incremental rebuild, npm install/ci with workers,
filesystem notifications and HTTP, and pip venv/PEP517 C-extension wheel workflows.

Virtual-root IPC checks run stock Debian and Alpine session D-Bus, independent
root clients and a rejected different-user client. Debian additionally passes
GDBus service activation, caller-UID lookup and bidirectional FD delivery.
Kernel-generated implicit credentials are not translated. Debian account-tool
checks cover group/user creation, modification, supplementary membership, named
launches, home ownership and deletion. A non-root guest is denied and leaves
group files unchanged. D-Bus package configuration creates the messagebus account
and completes with a clean dpkg audit. The prepared image's service-start policy
remains in force; this is not system-bus or init-system certification. The daemon's
FD-limit warning retains the real kernel denial.

Static glibc and musl fixtures cover ET_EXEC/static PIE, 64 KiB/2 MiB alignment,
constructors, TLS, pthreads, heap, fork and descriptor exec. A separate
freestanding assembly program verifies raw SVC without libc or mapped ELF headers.
GIMP's Wayland fixture checks rendering, a keyboard-opened dialog and zero exit.
LibreOffice Writer/Calc Wayland checks edit an ODT/ODS document, verify client text through
the clipboard, save it, close with zero process exit and read the saved XML through
a fresh guest process. Writer readiness uses its actual text-input publication,
not a settling delay. The Wayland clipboard filters unsupported private MIME
entries individually, retaining valid text offers from the same source.
Writer also passes the edit/save/readback workflow in an X11 viewer with xfwm4.
The X11 checks use WM_CLASS rather than assuming a Wayland app_id. Without the
window manager, Writer does not publish a window within the test deadline.
GIMP's individual X11 host without a window manager opens its new-image dialog,
but the keyboard-close fixture times out with native focus still on its parent.
This fixture does not establish successful dialog interaction; host/client focus
and event readiness need separate investigation.
The Calc X11 insertion check fails: the cell contains the typed text instead of
that text followed by the original content. Neither observation is classified
as a guest-runtime defect without isolating client, input and window-manager behavior.

Debian Blender 4.3.2 renders its viewport through X11/GLX with Linux Mesa 26.2.3
Zink/Turnip on Adreno 840 and exits normally. A GTK GLArea fixture separately
checks four colored regions and an input-driven frame through Wayland/EGL.
It passes with llvmpipe and with a test-owned Zink build using the explicit
[non-DRM Wayland patch](../wayland-runtime/tests/mesa-wayland-zink.patch).
The unmodified Zink Wayland control produces blank frames despite reporting the
hardware renderer. Patched sources and libraries stay in isolated fixture paths;
neither Android nor APK drivers are replaced. These checks are not complete
Blender editing or arbitrary OpenGL application certification.

Linux driver discovery belongs to the prepared userspace. A Turnip library in
`/opt` is not discovered without a Vulkan ICD manifest in a standard guest
directory or an explicit `VK_DRIVER_FILES`. Debian Mesa 25.0.7's automatic X11
Zink probe deadlocks when `vkCreateInstance` fails: its error cleanup reacquires
the still-held instance lock. This can block GTK before window creation, even
for a text editor. Selecting the prepared Turnip manifest, or registering that
same manifest under `/usr/share/vulkan/icd.d`, passes the Mousepad edit/save/close
workflow without forcing software rendering. An explicit llvmpipe run is a
separate control, not the runtime's default or an automatic fallback.

## Application Filters And Browsers

The production supervisor preserves application signal handlers, alternate stacks
and installed seccomp filters. Focused tests cover ERRNO/TRAP/KILL precedence,
protected descriptor metadata, nondumpable exec, non-leader exec, job control and
retained descriptors. Kernel-only calls share one policy definition between the
domain checker and the seccomp fast path; argument-dependent permissions compare
all 64 bits. Identity, namespace and filter-state changes retain their observers.
Protected access uses task-affine export and same-site replay
without restoring dumpability. A second tracer cannot attach concurrently; crash
reporters requiring it remain unsupported.

`--admit-elf /absolute/guest/path` explicitly selects an ordinary ELF for a
launch-scoped sealed snapshot with logical set-ID metadata and credentials.
Initial entry and reexec use the same contract. Real credentials remain UID 2000
in the tested shell scenario; the source file is not modified, and actual set-ID
files remain rejected. Logical credential drops and no_new_privs are enforced
by the supervisor. Other operations retain real permission denials.

The [production browser fixtures](../native/guest-exec-lab/browser/README.md)
run stock Debian Chromium with its selected ordinary helper, three fresh profiles,
calculated JavaScript/DOM results and PNG output, without sandbox-disabling flags.
They observe real installed application filters and require process completion.
Firefox has a separate headless screenshot workflow.

These results establish application execution, not complete security isolation.
The limited proc-root model is not general chroot. Interpreter/library admission,
mapped-code protection, complete descriptor/root confinement and authenticated
filesystem authorization remain unfinished. The same-UID filesystem service does
not automatically enforce a caller's seccomp domain. Unadapted syscalls retain
host semantics, and Chromium child SIGTRAP exits are recorded separately from
successful page execution. See the [interception contract](../native/guest-runtime/interception.md).

Guest file sharing and live appearance helpers are not integrated for this launch
method. Kernel permission denials remain failures. Unprivileged user namespaces
are unavailable in the tested native shell control as well as the guest.

The focused watch suite checks Debian GIO directory notifications from an
independent writer and stock D-Bus config-watch registration/session requests.
Installed-APK Debian and Alpine workflows verify Mousepad's external-change
notification and actual reloaded text, plus Thunar's live external file creation,
selection/rename and reaction to moving the viewed directory. Both close with
zero process status. These prepared images lack some optional desktop services;
working session buses and GUI workflows do not certify system-service startup
or arbitrary package configuration actions under virtual root.
Wayland publishes a logical monitor before client
startup and replaces it when an Android host attaches; GTK's initial
monitor-scale warnings are absent in these checks. The kernel process name identifies the guest executable after
each exec. Current-process/thread `cmdline` and `auxv` opens produce guest-image
snapshots with native read/seek afterwards; they allocate an unlinked temporary
file only on those explicit opens, not on ordinary IO. Namespace `/proc/self/exe`
open/stat retains executable inode identity after unlink, path reuse and close_range,
without a permanent hidden FD. Foreign-process aliases, snapshot stat metadata and
descriptor-exec readlink's original dentry identity remain incomplete. The direct
backend retains path-based executable reopening. This is not a fully virtualized procfs.

The installed CLI also retains the shared terminal's controlling PTY: interactive
dash job control, Ctrl+Z/fg/Ctrl+C, detachment and return to the Android shell
were checked on the device above, without Desktop.

The [native contracts](../native/guest-runtime/README.md) describe syscall,
filesystem, execution-policy and lifetime limits. The
[fixture tools](../native/guest-exec-lab/README.md) retain signed package inputs
and exact device observations separately from the shipped runtime.
