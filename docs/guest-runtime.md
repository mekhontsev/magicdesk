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
Stores persist independently of those processes. Shared-store contention is
reported explicitly, without automatic transaction retries.

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
Wayland, an explicit host-visible XKB directory and an inherited connection FD.
`X-MagicDesk-WaylandConnection=inherited` records that transport independently
of executor UID; ordinary recipes select `auto`. Each graphical launch prepares
its own guest `XDG_RUNTIME_DIR` and D-Bus session. File-environment identity remains distinct from
host paths; an unavailable guest-file helper is an error, never host fallback.

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

This is not certification of a full
Debian base, arbitrary package maintainer scripts, APT, a Linux desktop or GPU
clients. X11 transport, arbitrary pathname socket creation, guest file sharing
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
