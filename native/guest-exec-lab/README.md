# Guest Runtime Fixtures

Opt-in development tools and reference fixtures for
[`native/guest-runtime`](../guest-runtime/README.md). Runtime sources live only in
that native component; this directory is not packaged into the APK.

`prepare.mjs` authenticates Debian archive metadata and packages, then extracts
an isolated test rootfs without maintainer scripts or host installation.
`build.sh` builds the same CMake runtime as the APK and separate Bionic/glibc
fixtures. It requires the Termux development toolchain and JDK; installed guest launches
do not require Termux. Exact inputs are retained in the build's `manifest.json`.

```sh
node native/guest-exec-lab/prepare.mjs build/guest-fixtures --gtk
sh native/guest-exec-lab/build.sh build/guest-fixtures
python native/guest-exec-lab/test_device.py build/guest-fixtures --packages
python native/guest-exec-lab/test_graphics.py build/guest-fixtures
python native/guest-exec-lab/test_graphics.py build/guest-fixtures --gtk
```

For real application workflows, use a separate prepared build:

```sh
node native/guest-exec-lab/prepare.mjs build/guest-applications --applications
sh native/guest-exec-lab/build.sh build/guest-applications
python native/guest-exec-lab/test_device.py build/guest-applications --packages
python native/guest-exec-lab/test_graphics.py build/guest-applications --application mousepad --network
python native/guest-exec-lab/test_graphics.py build/guest-applications --application galculator
python native/guest-exec-lab/test_graphics.py build/guest-applications --application mousepad --routed
python native/guest-exec-lab/test_graphics.py build/guest-applications --application mousepad --routed --protocol x11
python native/guest-exec-lab/test_shortcuts.py build/guest-applications --protocol wayland
python native/guest-exec-lab/test_shortcuts.py build/guest-applications --protocol x11
```

For Qt text-input-v3, prepare the newer Debian userspace separately:

```sh
node native/guest-exec-lab/prepare.mjs build/guest-qt --qt --suite trixie
sh native/guest-exec-lab/build.sh build/guest-qt
python native/guest-exec-lab/test_device.py build/guest-qt --packages
python native/guest-exec-lab/test_qt.py build/guest-qt
```

Bookworm is the default suite; trixie uses the same pinned signature and package
hash verification, including the signed Release's exact codename. Qt packages
belong only to the prepared fixture store, not the APK. The Qt driver temporarily
selects the debug Android IME and restores the prior keyboard on completion or
failure. It retains a protocol trace and separate strict-correction and
`--skip-correction` reports; the latter cannot establish correction support.

Application checks require a current debug APK with the guest CLI installed.
Routed checks use the production guest connection/session wrappers and keep one
protocol session across successive clients. The X11 case also verifies rejection
of an incorrect MIT cookie. Receipts retain protocol and connection mode; pixel
capture, input, process exit and cleanup remain separate assertions.
They use its runtime and the production Java graphical-session wrapper.
The fixture-only `md-prepare-applications` configures real shell NSS identity,
toolkit caches, CA certificates and an explicit public DNS resolver in the owned
test store. `--network` opts into DNS and certificate-verified HTTPS checks.
Mousepad coverage includes editing, saving, fresh-process file readback and
reopening; both applications must exit normally, not merely lose their window.
Settings checks exercise real D-Bus activation and persistence, not a substituted
keyfile backend. Application logs and hashed screenshots accompany the JSON report.

`test_shortcuts.py` encodes the shortcut editor's real `LinuxLaunchRecipe` through
a debug-only Java fixture and launches the resulting `.desktop` files through
the ordinary launch service. It checks bidirectional Unicode clipboard exchange,
guest file saving, file-dialog cancellation, opening another file, Save As,
overwrite confirmation, directory creation and inaccessible paths. It also checks
concurrent applications in one store, recipe reuse and actual process exit after
protocol close. Catalog events identify Wayland dependent windows; native-family
events identify X11 transient dialogs and Wayland subsurfaces without guessed IDs.
Inotify receipts identify
the exact command's start and exit. Screenshots are point-in-time observations,
not synchronization barriers. Input requires the selected native window's
Android host and focus acknowledgement. Opening a file awaits the exact client
title and verifies the document contents independently through the clipboard.
The test restores an existing plain-text clipboard
and refuses to replace non-text contents. Launchers and their receipts remain in
the prepared test directory for inspection; their launches participate in Recent.

The default calculator workflow checks `7+8=15`. An additional
`--repeat-digit` check sends `2+2` without a pacing delay and requires `4`.
It currently reads `2` on the tested Wayland stack and retains a separate failing
`*-shortcuts-repeat-digit-results.json`; it must not be treated as passing
input coverage or hidden by a sleep. `--trace-wayland` retains the calculator's
client-side protocol log. Both key press/release pairs arrive before Galculator's
[100 ms toggle reset](https://github.com/galculator/galculator/blob/v2.1.4/src/ui.c#L748-L760):
the second click deactivates the button and its
[digit handler](https://github.com/galculator/galculator/blob/v2.1.4/src/callbacks.c#L95-L107)
ignores it. The Debian binary has the same logic. Mousepad's repeated-digit
control passes; production input is unchanged. Calculator configurations are
unique to each run, so another run's saved notation cannot affect the result.

Device checks use the configured MagicDesk MCP connection and require selected
shell UID 2000. They do not change identity, install an APK or start Desktop.
Graphical checks use a private virtual display and clean up their own sessions.
Results retain exact device/build identities, commands, failures and known limits.
Prepared shell-owned test stores remain at the recorded path for inspection.

Capability fault injection checks that missing kernel calls reject only guest
execution. Process fixtures cover concurrent distinct stores, cancellation and
orphan ownership. These checks do not emulate an old kernel or prove compatibility
with every distribution. See the native component's coverage and limits.

## Libc, IPC And Desktop Checks

`build-libc-fixtures.sh glibc|musl SYSROOT OUTPUT` builds the same execution,
thread/signal and IPC tests against the selected libc, without libc-specific
runtime branches. The musl fixture uses the official Alpine 3.23 ARM64 minirootfs
and its matching `musl-dev` headers/CRT. Prepare the distribution's ordinary
dependencies and toolkit caches before importing the tree; the ELF loader does
not install packages or create accounts. `fixtures/md-prepare-alpine` configures
only disposable GUI-test data for the real shell identity. The socket-aware
store requires schema 4; incompatible stores are rejected without modification.

`test_ipc.c local NEW_DIRECTORY` checks namespace socket names, permissions,
SCM_RIGHTS, unlink/rebind, rename and stale listeners. `paths` checks `/dev/shm`
through absolute paths, dirfd and a host-directory cwd. The separate static
Bionic `md-ipc-launch RUNNER STORE [OTHER_STORE]` starts independent supervisors
and filesystem services, checks shared memory, file-data inotify and unlinked
lifetimes, and optionally verifies name isolation from another store.
These are not directory-inotify or abstract-socket isolation tests.

For a prepared GUI store:

```sh
python native/guest-exec-lab/test_userspace.py BUILD \
  --store HOST_STORE --runtime STAGED_HELPERS --recipes BUILD/recipe-classes \
  --keyboard-directory HOST_XKB --protocol wayland --installed
```

Run again with `--protocol x11`. `--installed` selects the APK's lazy
`magicdesk-guest` command; omitting it selects immutable staged test binaries.
`STAGED_HELPERS` also supplies the exact-process exit watcher. Checks require
real pixels, keyboard editing, saved-file readback and process exit, not only a
mapped native window. Host/user HOME and Desktop ownership must remain unchanged.

The whole-desktop fixture uses the same connection and execution contracts:

```sh
node native/guest-exec-lab/prepare.mjs build/guest-desktop --desktop --suite trixie
sh native/guest-exec-lab/build.sh build/guest-desktop
python native/guest-exec-lab/test_desktop.py BUILD \
  --store HOST_STORE --runtime STAGED_HELPERS --keyboard-directory HOST_XKB --installed
```

Import and prepare the generated rootfs explicitly before running. Xfce readiness
has separate X11 WM/panel/desktop and D-Bus Idle acknowledgements.
`test_x11_desktop.c` checks WM activation/resize; `test_xfce_session.c` subscribes
before querying state. No sleep substitutes for readiness. Xfce uses an isolated
configuration, software GL and a retained root viewer; logout must return zero.
The fixture does not boot systemd, logind or a complete GNOME session.

## Linux GPU Fixture

`build-mesa.py SOURCE SYSROOT OUTPUT` builds an unmodified Linux Mesa Turnip ICD
with KGSL and DRM WSI, not an Android driver. The sysroot needs matching C/C++,
libdrm, Vulkan, Wayland, X11/XCB, xshmfence, expat, zstd, zlib and libelf development
packages. Supply them with repeated `prepare.mjs --package NAME`; signatures and
hashes are checked as for the base profile. Host Meson, Ninja, Python generators
and a `wayland-scanner` matching the target libwayland are build tools only.
Use a fresh output directory when changing Mesa/configuration.

Install `libvulkan_freedreno.so` at `/opt/md-gpu/lib/` and
`fixtures/turnip.json` at `/opt/md-gpu/turnip.json` in the owned test store;
its distribution supplies `vulkan-tools`. Do not replace Android libraries.
`test_gpu.py BUILD --store HOST_STORE --runtime STAGED_HELPERS
--keyboard-directory HOST_XKB --protocol wayland --installed` checks the hardware
ICD, changing Android pixels, 600 frames and zero exit. X11 is a separate
`--protocol x11` run with Xfce's window manager: the unmodified XCB cube does not
publish an ICCCM name/class, and the manager supplies its `WM_STATE`.
A software compositor or successful `vulkaninfo` alone
does not establish guest GPU rendering. `--trace` accepts the optional static
`md-trace-fault` observer; normal execution does not use ptrace.

Exact successful device coverage and unsupported ABI surfaces belong in
[Guest runtime](../../docs/guest-runtime.md#coverage-and-limits), not a general
claim of distribution or driver compatibility.
