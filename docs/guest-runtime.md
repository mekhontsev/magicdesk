# Guest Execution Runtime

MagicDesk includes an experimental native ARM64 Linux execution adapter.
It runs prepared glibc programs through the explicitly selected shell or root
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
retain their normal permission checks. Pathname socket creation is not implied.

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

`LinuxGraphicalEnvironment` owns the shared graphical-session wrapper. Guest
recipes require the distribution's `dbus-run-session`, `dbus-daemon` and
`dbus-uuidgen`. They select a unique abstract Unix listen address, avoiding a
pathname socket that the guest namespace cannot create. The distribution's
standard session configuration, credential authentication, activation and
limits are preserved; only the [listen address](https://dbus.freedesktop.org/doc/dbus-daemon.1.html)
is overridden. `dbus-run-session` owns readiness and shutdown. MagicDesk ships
no D-Bus daemon and does not reuse Android's or Termux's session bus. Ordinary
PRoot/chroot graphical recipes keep their standard D-Bus transport.

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

The native fixtures exercise Debian bookworm glibc, shell/exec, threads/signals,
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
command. The Wayland workflow checks child file-dialog cancellation and keyboard
selection of another guest file, then verifies the new document's title and
actual text through clipboard readback. It waits for the dialog's Android host
and input focus, not merely its native map. Captures verify the dialog and
opened document on the tested device. X11 transient dialogs still need
native-family observation beyond the toplevel catalog.
The separate rapid repeated-digit Galculator check exposes the application's
100 ms toggle-button reset: a second accelerator can toggle the digit off and
be ignored by its handler. Client-side Wayland tracing confirms both key
press/release pairs; repeated digits work in Mousepad. No input pacing or
application-specific runtime workaround is applied. These checks do not
establish Qt, IME or GPU compatibility.

This is not certification of a full
Debian base, arbitrary package maintainer scripts, APT, a Linux desktop or GPU
clients. Arbitrary pathname socket creation, guest file sharing
and live appearance helpers are not integrated for this launch method.
Static/non-PIE executables and non-glibc interpreters are rejected. Unsupported
syscalls and kernel permission denials remain explicit.

Namespace inotify remains unsupported; D-Bus reports that its session-config
directory cannot be watched. GTK reports monitor-scale warnings before the
Android host publishes an application output. These diagnostics are retained,
not suppressed. The kernel process name identifies the guest executable after
each exec, but `/proc/self/cmdline` and `/proc/self/auxv` still describe the
bootstrap, not a fully virtualized guest process.

The installed CLI also retains the shared terminal's controlling PTY: interactive
dash job control, Ctrl+Z/fg/Ctrl+C, detachment and return to the Android shell
were checked on the device above, without Desktop.

The [native contracts](../native/guest-runtime/README.md) describe syscall,
filesystem, execution-policy and lifetime limits. The
[fixture tools](../native/guest-exec-lab/README.md) retain signed package inputs
and exact device observations separately from the shipped runtime.
