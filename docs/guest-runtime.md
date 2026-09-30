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

Each launch captures its store, guest home, guest cwd, argv and graphics
connection. There is no current distribution, global guest root or singleton
per launch method. Separate guest stores and independent PRoot/chroot entry
scripts can be used concurrently. Each guest launch owns a filesystem service
and a supervised process tree; cancelling it does not cancel another launch.
Stores persist independently of those processes. Metadata operations on the
same store acquire a kernel file lock before entering SQLite and release it
after commit or rollback. Separate launches can share a store without exposing
ordinary internal transaction contention to guest syscalls. There is no polling
or transaction replay; data IO through opened descriptors stays native. An
external database writer that bypasses this gate still produces an explicit error.

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
```

Import accepts an immutable prepared tree, not an archive or distribution name.
It publishes atomically into an empty store and retains real metadata checks.
The default import bounds are 2 GiB and 200,000 entries. Unsupported objects,
metadata, source mutation, overlap and an existing populated store are errors.
It runs no package scripts, downloads nothing and does not manage mounts.

The shortcut editor's Shell Linux method accepts a prepared guest store.
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

## Prepared Userspace

The store must contain the application's complete matching libraries, data and
configuration. The runtime does not invent a Linux user, package records, DNS
servers, certificates or a machine ID. In particular, D-Bus needs an NSS entry
for the actual executor UID in the prepared system. Network clients need its
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
There is no distribution or loader-name allowlist. The selected stock dynamic
linker owns relocations, dependencies, TLS and `dlopen`. Runtime-owned guarded
signal stacks isolate syscall adaptation from libc thread-stack sizes; ordinary
file calls allocate no heap or temporary mappings. Fork, vfork and thread clone
retain kernel lifetimes. SIGSYS remains reserved.

Static PIE and fixed-address ET_EXEC use the same loader. ELF load alignment is
retained, including 2 MiB alignment; an occupied fixed-address range fails without
replacing existing mappings. Large valid images are bounded by address arithmetic
and the kernel's mapping limits, not a separate image-size cap. Freestanding ELF
without an interpreter or mapped program-header table is also supported. This
does not admit set-ID or writable/executable load segments.

Descriptor and relative-dirfd exec pin the validated ELF across bootstrap exec,
including open-unlinked programs. CLOEXEC scripts fail rather than losing their
interpreter input. Bounded shebang parsing accepts relative interpreters and a
short header without a newline, preserves the optional argument as one string,
and rejects a truncated interpreter name. `AT_EXECFN` retains the caller's spelling; descriptor exec uses
the Linux `/dev/fd/N[/suffix]` form. Namespace `openat2` validates its extensible argument structure
and supports scoped beneath/in-root resolution, symlink restrictions and mount
boundaries. Cache-only resolution returns EAGAIN; unavailable host-crossing
semantics fail explicitly. The direct backend does not implement scoped resolution.

Pathname Unix sockets are namespace inodes whose native transport is an
abstract kernel socket. The filesystem service commits bind and name publication;
ordinary traffic, credentials and SCM_RIGHTS then bypass it. Names, permissions,
relative addresses, returned addresses, rename, stale listeners and unlink/rebind
are tested. Unlinking a name does not invalidate existing connections. Different
stores have independent names; explicitly abstract sockets retain the host's
shared abstract namespace and are not isolated.
Unix `sendmmsg` reuses address translation without copying payloads or allocating
per-message buffers. Tests cover partial batches, short stream writes, failed
result writes and SCM_RIGHTS. Unadapted socket operations are not thereby certified.

`/dev/shm` belongs to the guest store, including access relative to a `/dev`
descriptor or cwd. POSIX shared memory, mmap, descriptor passing and unlinked
data work across independent launches. Native file-data inotify works on retained
backing inodes; directory/name events are not synthesized. Directory watches
return ENOTSUP instead of silently missing logical namespace changes.

## Optional Kernel Support

Guest executables are never loaded into the app or shell service with
`System.loadLibrary`. `magicdesk-guest` stages a content-addressed immutable
version only when invoked. A launch first probes pidfds, subreapers, faccessat2,
executable mappings and seccomp/SIGSYS in a child process. Probe failure reports
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

Separate fresh-image checks use real Debian APT and Alpine APK with signed
repositories, dependency installation, scripts/triggers, HTTPS verification,
reinstallation, removal and subsequent installation. Selected real upgrades cover
Debian's PCRE2 package and Alpine's musl. The prepared userspace explicitly selects
the package managers' unprivileged/chrootless options and real UID 2000. Debian's
ucf fixture additionally opts into file updates in its disposable image; its normal
non-root dry run must not be mistaken for configured LibreOffice registry files.
An unrestricted Alpine base upgrade is not certified. APK 3.0.1 attempts executable
memfd scripts that this device denies even in the native shell control; its fallback
reports ENOENT for an absent script path. APK 3.0.8 runs those triggers and the
Alpine 3.23.0-to-3.23.6 upgrade returns zero. Its base-system script nevertheless
reports an unapplied ownership change for `etc/shadow` and continues. The runtime
preserves that denial; the package manager's exit code does not certify every
configuration action. These are bounded package workflows, not arbitrary maintainer-script
or complete-distribution compatibility.

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
Blender editing, arbitrary OpenGL application or browser certification. Firefox content
processes fail when installing their SIGSYS handler. Debian ARM64 Chromium also
fails startup with Crashpad signal-stack/SIGSYS errors; its sandbox is not disabled
to classify that test as passing.

Guest file sharing and live appearance helpers are not integrated
for this launch method. Application-owned alternate signal stacks, guest SIGSYS
handlers and nested application sandbox compatibility remain unsupported.
Shared-VM non-thread clone requires vfork ownership; other such clones fail
explicitly. Kernel permission denials remain failures.
An isolated USER_NOTIF probe verifies remote memory, pidfd descriptor duplication,
descriptor injection and coexistence with guest SIGSYS/altstack/longjmp on this
device. It is not the production syscall adapter. Unprivileged user-namespace
creation also fails in a native shell control, independently of the guest runtime.

D-Bus reports that its session-config directory cannot be watched. Wayland publishes a logical monitor before client
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
