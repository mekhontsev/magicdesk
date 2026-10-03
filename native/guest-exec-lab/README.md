# Guest Runtime Fixtures

Opt-in development tools and reference fixtures for
[`native/guest-runtime`](../guest-runtime/README.md). Runtime sources live only in
that native component; this directory is not packaged into the APK.

## Named Environment Workflows

`test_environment_workflows.py --tag NAME --stage all` installs fresh public
Debian and Alpine images through the installed APK's shell-owned catalog. It
checks package installation, account login, concurrent cancellable operations,
authenticated file import/export and cleanup, then simultaneous X11/Wayland
editors with keyboard input, bidirectional clipboard and saved-file readback.
Backup checks remove the original instances, restore new storage identities and
relaunch graphical clients against the restored files.

The fixture uses a virtual display without Desktop and retains its two named
environments and archives for inspection. `--stage prepare|accounts|files|graphics|backup`
can run a focused phase against the same tag. `--dns system` is the default;
an explicit address opts into that resolver for the test stores when Android's
network uses Private DNS or a VPN. `--apk PATH` installs through the normal MCP
update protocol; `--lease ID` reuses an existing wake lease without releasing it.
Reports and screenshots live under `build/environment-workflow-*` and
`build/check-*`. No Termux executor, Android root or HOME lease is required.

The file phase invokes debug-only `GuestFilesInstrumentation` with an exact
store. It exercises production shell admission, the selected-guest static helper,
Android import, independent guest read, SCM_RIGHTS export and owner-loss cleanup,
without creating a graphical session.

`test_environment_catalog.py --names NAME NAME` uses prepared named Debian and
Alpine environments with Python 3 and Mousepad. It checks independent catalog
identities, rendered X11/Wayland windows, shared terminal ownership, and exact
CLI/MCP stop without cancelling a peer. Test-only desktop entries are added to
the selected guests and removed after successful checks. The owned virtual
display and graphical sessions are released on exit.

`test_environment_recovery.py --help` documents an opt-in process-death check.
Its `--kill-app` mode terminates MagicDesk during a native restore and reopens
the exported launcher, interrupting any other work in that app process. Run it
only on an idle device. It uses a private library, preserves the default catalog,
verifies helper termination, then recovers staging and retries the restore.
Neither fixture reboots the device or requires a Desktop session. Reports are
`build/environment-catalog.json` and `build/environment-recovery.json`.

## Prepared Runtime Fixtures

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
The native prepared-command fixture runs under the selected shell identity and
checks interpreter descriptor pinning across name replacement, borrowed/unlinked
executables, argument identity and descriptor cleanup. It is not run in the
Termux app domain, whose syscall restrictions differ from the guest executor.

## Libc, IPC And Desktop Checks

`build-libc-fixtures.sh glibc|musl SYSROOT OUTPUT` builds the same execution,
thread/signal and IPC tests against the selected libc, without libc-specific
runtime branches. The musl fixture uses the official Alpine 3.23 ARM64 minirootfs
and its matching `musl-dev` headers/CRT. Prepare the distribution's ordinary
dependencies and toolkit caches before importing the tree; the ELF loader does
not install packages or create accounts. `fixtures/md-prepare-alpine` configures
only disposable GUI-test data for the real shell identity. Fixtures require the
current runtime's store schema; incompatible stores are rejected without modification.

`test_ipc.c local NEW_DIRECTORY` checks namespace socket names, permissions,
SCM_RIGHTS, unlink/rebind, rename and stale listeners. `paths` checks `/dev/shm`
through absolute paths, dirfd and a host-directory cwd. The separate static
Bionic `md-ipc-launch RUNNER STORE [OTHER_STORE]` starts independent supervisors
and filesystem services, checks shared memory, file-data inotify and unlinked
lifetimes, and optionally verifies name isolation from another store.
These are not directory-inotify or abstract-socket isolation tests.

`build-static-fixtures.sh glibc|musl SYSROOT OUTPUT` additionally builds static
ET_EXEC and PIE programs at 64 KiB and 2 MiB alignment. The glibc sysroot requires
its matching `libc6-dev` and GCC 14 static support libraries; musl requires its
matching development archive and compiler builtins. `build.sh` also builds a
freestanding raw-SVC ELF without libc or mapped program headers.

```sh
python native/guest-exec-lab/test_abi.py BUILD \
  --libc glibc --libc-build BUILD --static-build STATIC_BUILD \
  --store DISPOSABLE_HOST_STORE --runtime IMMUTABLE_STAGED_HELPERS
```

Repeat with the musl outputs and Alpine store. These checks include descriptor
and dirfd exec, open-unlinked/name-reused executable identity, bounded shebang
parsing, 64 successive execs, thread/signal stacks, vfork, spawn file actions,
process-image snapshots, Unix sendmmsg partial batches and SCM_RIGHTS.
They install fixture programs only into the explicitly selected disposable store.

## Distribution Packages And Applications

`fixtures/md-import-debian-rootless` prepares a copied official Debian rootfs
for the real shell UID before import. It does not fabricate package records.
`md-package-debian`, `md-package-alpine` and `md-package-upgrade` exercise real
signed repositories, dependencies, scripts, HTTPS, removal and updates in
disposable stores. Preserve the official image hash and package-manager logs.
The Debian ucf preparation explicitly enables real configuration-file updates
in that fixture; the normal non-root dry run is not a configuration pass.
These scripts are not a product distribution installer or a container manager.

```sh
python native/guest-exec-lab/test_distribution_app.py BUILD \
  --store DISPOSABLE_HOST_STORE --runtime IMMUTABLE_STAGED_HELPERS \
  --keyboard-directory HOST_XKB --protocol wayland --application writer
```

The prepared store supplies its normal packages and caches. `writer` and `calc`
edit/save ODF documents, check clipboard text, close normally and read saved XML
in a new guest process. Both pass on Wayland; Writer also passes on X11 with
`--x11-manager`. Calc's X11 insertion check remains failing, and Writer startup
without the manager remains unverified. The fixture retains these failures,
including the expected cell contents, rather than weakening its assertions.
`gimp` checks a dialog and closure. `blender` uses its
viewport fixture; the tested Debian build requires `--protocol x11 --x11-manager`.
`gtkgl` checks actual colored pixels and an input-driven frame; `--software`
selects the llvmpipe control. `--gpu-prefix` selects an explicitly staged test
driver directory. Reports retain build identity, output, screenshots, process
status and cleanup. A trace/event acknowledgement is required where a client
publishes readiness; a screenshot or delay is not a readiness barrier.

Browser acceptance uses the [production browser fixtures](browser/README.md),
including stock Chromium JavaScript/PNG output without sandbox-disabling flags.
No completed workload establishes full isolation. `md-notification-test` is
a separate USER_NOTIF/remote-memory/FD-injection feasibility probe, including
guest signals and longjmp. It is not an alternative installed runtime.
`md-notification-lifecycle-test` adds native mask/altstack/TRAP/ERRNO/TSYNC,
thread/fork/exec, listener lifetime and cancelled-request checks without ptrace.
It deliberately asserts the shell's protected-process denials as boundary
checks, not successful access: pre-opened memory is not a capability for a fork
child or replacement image. ADDFD and explicit SCM_RIGHTS still work after
dumpable is disabled. The fixture never enables it again. Execute these native
controls separately from the guest runtime:

```sh
python native/guest-exec-lab/test_notifications.py BUILD
```

The runner checks the selected UID, stages fresh binaries, retains hashes,
kernel/build identity and exact results, and closes its console. It does not
install an APK or start Desktop. `test_process_context.c` separately checks
independent command-line/auxv snapshots and identical socket names routed by
different explicit contexts, without a process-global route fallback.

The separate [sandbox boundary fixtures](sandbox-research.md) compare native
seccomp precedence, cooperative signals and direct filesystem RPC with the
current guest adapter. They deliberately reproduce policy failures: a completed
run is not successful browser sandboxing. Build and execute them independently:

```sh
sh native/guest-exec-lab/build-sandbox-fixtures.sh BUILD
python native/guest-exec-lab/test_sandbox.py BUILD \
  --runtime IMMUTABLE_STAGED_HELPERS --store DISPOSABLE_GLIBC_STORE
```

`test_hybrid.py` separately exercises selective TRACE plus USER_NOTIF, protected
processes, register-only exec, guest-side bounded memory copying and fresh
same-site notification replay. It traces only its native fixture children and
does not alter the installed adapter. The same research note records positive
controls and unresolved security/lifecycle requirements.

```sh
sh native/guest-exec-lab/build-hybrid-fixture.sh BUILD
python native/guest-exec-lab/test_hybrid.py BUILD
```

The [browser fixtures](browser/README.md) build the production runtime, including
its supervisor, and distinguish startup from a rendered artifact. The separately
labelled renderer-only experiment uses an unsandboxed parent/zygote; it cannot
replace the stock-browser check. Filesystem broker authorization and full
isolation remain separate from application compatibility.
The [native isolation fixture](chromium-isolation.md#measured-boundary) tests
namespace, chroot, credential and Landlock availability without loading a guest
or enabling ptrace/seccomp mediation. Unsupported capabilities are observations,
not passing browser tests.

The [external domain fixture](chromium-isolation.md#external-domain-fixture)
checks read-only file admission for two independent roots, default-deny syscall
confinement, inherited-FD leakage, application seccomp precedence and fail-closed
memory/listener errors. It uses no ptrace and is not virtual chroot or browser
support:

```sh
sh native/guest-exec-lab/build-domain-fixture.sh BUILD
python native/guest-exec-lab/test_domain.py BUILD --repeat 10
```

The [helper/context control](chromium-isolation.md#helper-and-protected-memory-control)
combines shared root/cwd state with protected-memory copying and original-site
kernel replay. It compiles the matching Chromium helper source without edits;
the optional full-main mode executes the stock entry point and a native fixture
client, not the browser. Source hashes and license are retained under BUILD.
The explicit namespace fallback, fixed-image exec scope and remaining set-ID/
guest-ELF gates are documented with the results:

```sh
python native/guest-exec-lab/build-suid-context.py BUILD
python native/guest-exec-lab/test_domain.py BUILD --fixture suid-context --repeat 10
python native/guest-exec-lab/test_domain.py BUILD --fixture suid-context --suid-main --repeat 10
```

`md-exec-policy-test INTERPRETER` compares executable memfd scripts under the
native shell and guest. X_OK succeeding does not override a kernel exec denial.

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

Zink additionally needs `libwayland-egl-backend-dev`, `libxcb-glx0-dev` and
`libxxf86vm-dev` in the authenticated SDK. Build it with `--driver zink`.
`--wayland-zink` explicitly applies the repository's Mesa 26.2.3 non-DRM
Wayland patch exported by `scripts/install_linux.sh --print-mesa-patch` to a
private source copy and records its hash; the original source
tree remains unchanged. Keep patched and unmodified prefixes separate, and run
the unmodified control. A patched fixture result is not upstream compatibility.

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
`md-trace-fault` observer. The production supervisor already owns ptrace for its
guest tree; a second observer cannot attach concurrently.

`test_userspace.py --environment NAME=VALUE` records explicit guest-client
driver controls; `--software` selects llvmpipe. For an isolated Turnip prefix,
use `--environment VK_DRIVER_FILES=/opt/md-gpu/turnip.json`. Installing that
manifest in the test store's `/usr/share/vulkan/icd.d` exercises ordinary loader
discovery without a driver override. These controls do not change Android or
APK drivers.

`test_installer.py --distro ID --name TEST_NAME --gui MODE` exercises the public
installer through MagicDesk's shell executor. It checks package setup, locale,
timezone, an ordinary guest account and settings persistence across resume.
The named test environment is retained. `--resume` continues that fixture;
`--arch-without-landlock` explicitly opts out of pacman's filesystem sandbox.
The test uses explicit guest DNS, not Android's encrypted resolver.

`test_installer_graphics.py --names TEST_NAME ...` launches the installer's
catalog entries on an owned virtual display without Desktop. It records actual
native windows, Android hosts, screenshots and launch failures, then releases
its sessions and display. `--entry TEXT` filters desktop-file names. An optional
`--lease ID` retains an existing phone awake lease; otherwise the test owns a
virtual-display lease. Package installation alone is not a passed graphics test.

To check the Debian installer's actual login profile, use
`test_distribution_app.py BUILD --store HOST_STORE --runtime STAGED_HELPERS
--keyboard-directory guest:HOST_STORE --user root --home /root
--protocol wayland --application gtkgl --installed-profile turnip`.
Repeat with X11, Blender on X11, and `--installed-profile software` after switching the
installer profile. This mode injects no driver overrides and asserts the actual
renderer. GTK checks four color regions, an input-driven frame and zero process
exit. The test holds its virtual display awake; release any separately held MCP
awake lease before the run. `--user` selects a guest account, not Android root.

The Debian Mesa 25.0.7 fixture can block in GTK's initial GLX probe when there
is no discoverable Vulkan ICD. Its [Zink initialization](https://sources.debian.org/src/mesa/25.0.7-2%2Bdeb13u1/src/gallium/drivers/zink/zink_screen.c/)
jumps to `zink_destroy_screen` after a failed `zink_create_instance` while
holding `instance_lock`; destruction locks it again. The native stack waits in
that mutex, and `VK_LOADER_DEBUG=error,driver` reports no drivers. Supplying the
prepared Turnip manifest passes the same workflow. Keep this distribution
failure distinct from the guest syscall mechanism or Android frame transport.

Exact successful device coverage and unsupported ABI surfaces belong in
[Guest runtime](../../docs/guest-runtime.md#coverage-and-limits), not a general
claim of distribution or driver compatibility.

## OCI And Filesystem View Checks

Configure `native/guest-runtime` with `-DMAGICDESK_GUEST_FIXTURES=ON` and build
`bootstrap supervisor run service image test_snapshot test_mounts`.
The offline importer tests require Python and the `zstd` CLI:

```sh
python native/guest-exec-lab/test_oci.py BUILD_DIRECTORY -v
```

`fetch_oci_fixture.py REPOSITORY REFERENCE NEW_DIRECTORY` downloads a public
Docker Hub ARM64 image into a local OCI layout. It is a development fixture,
not an installed registry client. Preserve the returned manifest digest for
reproducible inputs. Run independent Alpine and Debian layouts through actual
shell UID 2000:

```sh
python native/guest-exec-lab/test_oci_runtime.py BUILD_DIRECTORY ALPINE_LAYOUT DEBIAN_LAYOUT
```

The runner stages production helpers, tests snapshot and mount contracts, then
imports each layout and creates two instances. It checks copy-on-write,
concurrent independent launches, readonly attachments, cross-boundary symlinks,
working directories, PATH execution from an attachment and release after launch.
It retains exact commands, results, digests and device/build identity, without
starting Desktop or changing privileges. Uploaded layouts and stores remain for
inspection. The installed CLI additionally needs ordinary Shell-console checks;
standalone helper coverage alone does not verify APK packaging and dispatch.

The installed named manager has a separate workflow:

```sh
python native/guest-exec-lab/test_environment_cli.py --apk app/build/outputs/apk/debug/app-debug.apk
```

It downloads public Alpine and Debian images through the APK's registry client,
exercises independent names, run/exec/login, directory attachments, backup/restore
and explicit prune under UID 2000. A guest HTTP request holds a real launch open
while removal and backup are required to fail. The fixture's Python controller
is development tooling, not an installed runtime dependency; guest acquisition
and execution use no Termux command or service. Reports retain the exact APK,
image configuration, storage paths and command results.

Stock service-image workflows and development tools:

```sh
python native/guest-exec-lab/test_oci_services.py BUILD --nginx NGINX_LAYOUT --redis REDIS_LAYOUT --postgres POSTGRES_LAYOUT
python native/guest-exec-lab/test_oci_services.py BUILD --python PYTHON_LAYOUT --node NODE_LAYOUT --httpd HTTPD_LAYOUT --memcached MEMCACHED_LAYOUT --java TEMURIN_JDK_LAYOUT
python native/guest-exec-lab/test_development_runtime.py BUILD --debian DEBIAN_LAYOUT --install
python native/guest-exec-lab/test_service_runtime.py BUILD DEVICE_STORE OTHER_DEVICE_STORE --installed
```

The first two commands use packaged helpers by default; `--build BUILD` explicitly
stages a native build. MariaDB and Caddy have corresponding image arguments.
PHP, Ruby and .NET SDK images have separate arguments as well. Staged runs record
each native helper's SHA-256 and verify it after upload.
Each image gets a disposable writable instance, loopback ports and owned process
trees. Readiness comes from service events and is followed by actual requests,
shutdown and, where supported, restart/readback. `--NAME-instance` reuses an
explicit disposable instance; PostgreSQL/MariaDB initialization checks require
a fresh instance. Control overrides are never default-entrypoint passes.

The development check installs packages only with explicit `--install`, in its
selected disposable Debian store. Compiler and live-debugger results are separate;
reading symbols is not a debugger pass. Reports preserve failures, image digests
and exact commands. Current coverage and unsupported requirements are in
[image checks](../guest-runtime/images.md#checks).

`test_proot_debugger.py REPORT.json --distribution ubuntu` runs the same GDB
sources, commands and assertions in an installed Termux PRoot distribution with
GCC, libc headers and GDB already available. It checks both default PRoot and
`PROOT_NO_SECCOMP=1`, without root or package installation. Each case has a bounded
process lifetime; temporary sources and binaries are removed afterward. The
report records versions, output, exit status and timeout failures separately.

`test_debugger_runtime.py OUTPUT --instance STORE [--build BUILD]` runs raw
debugger lifecycle checks and real GDB attach, LLDB, gdbserver, strace, gprof,
Callgrind and perf-event checks. `--proot ubuntu` selects the same cases in an
installed Termux PRoot distribution; `--case NAME` restricts a run. The
`gdb-attach-threads` case checks all 41 target threads, backtraces, a memory write,
detach and normal target exit. The prepared
userspace needs GCC, Python 3, GDB, LLDB, gdbserver, binutils, strace and Valgrind.
No package is installed by the test. `--diagnostics` enables supervisor diagnostics
for Shroot only. A denied perf event is recorded as unavailable, never as working
profiling. Device capability and debugger compatibility are separate results.

`--gdbserver-binary FILE` explicitly stages a standalone Linux ARM64 binary under
`/tmp`, retaining its hash in the report without replacing the installed package.
This permits a current gdbserver control when an older distribution package has
an upstream ARM64 register bug. Timeouts fail their cases and the launch owner
cancels remaining descendants. Shroot fixtures persist only in the explicitly
selected disposable store. See the [debugger contract and measured coverage](../guest-runtime/debugging.md).

`test_kernel_contract.py OUTPUT --fixture BUILD/libmagicdesk_guest_kernel_contract.so
--instance STORE [--build BUILD]` compares normalized observations from the same
static executable directly under shell and inside Shroot. Cases cover wait and
ptrace contracts, signal delivery, descriptor lifetime, openat2 and concurrent
rename confinement. Native capability gaps are recorded separately from guest
assertions. Build the `kernel_contract` and `test_elf` CMake fixture targets;
the latter runs malformed ELF, page-size preflight and actual BSS mapping checks.

`test_sysv_runtime.py OUTPUT --instance STORE [--build BUILD]` compiles the focused
SysV fixture with the prepared distribution's GCC and checks semaphore/message
semantics, protected copying, independent launch traffic and owner-death recovery.
`test_sysv_apps.py` accepts the same arguments and tests stock fakeroot-sysv,
PHP with sysvsem/sysvmsg/pcntl, Symfony Lock and Apache prefork. These packages
must already be present; the tests do not install software. `--case NAME` selects
individual workflows. See the [SysV contract](../guest-runtime/sysv-ipc.md).

Distribution and emulator workflows use the same runner and per-instance policy:

```sh
python native/guest-exec-lab/test_distribution_runtime.py BUILD --build BUILD ubuntu --layout UBUNTU_LAYOUT --install
python native/guest-exec-lab/test_distribution_runtime.py BUILD --build BUILD centos --layout CENTOS_LAYOUT --install
python native/guest-exec-lab/rootfs_oci_fixture.py --help
python native/guest-exec-lab/test_qemu_runtime.py --help
```

The rootfs wrapper retains an unchanged official Arch Linux ARM tarball as one
OCI layer. Verify its detached signature before importing; wrapping alone is not
authentication. Arch package commands retain pacman's configured sandbox and
signature checks. A kernel without Landlock cannot pass that default download
workflow; `test_isolation.py` measures availability directly outside guest execution.
`--arch-without-landlock` explicitly selects only pacman's filesystem-sandbox
opt-out for package checks; signatures and syscall filtering remain enabled.
XZ rootfs archives are transcoded without changing tar contents, including device
entries. The production importer owns omission/reporting for native `/dev` and
rejection of unsupported objects elsewhere. Verify source signatures before
conversion; wrapping or transcoding alone does not authenticate a rootfs.
The QEMU fixture checks an x86-64 program's file IO and fork/wait through the
distribution's user-mode emulator, not KVM or full-system virtualization.

`test_qemu_docker.py` separately boots Alpine with QEMU TCG and exercises actual
Docker Engine inside the VM, including container/volume reuse and shutdown.
`prepare_astra_userspace.py` prepares official ARM64 packages for
`test_astra_userspace.py`; its repository checksums over HTTPS are not a pinned-GPG
authentication claim. `test_gentoo_runtime.py` checks stage3 userspace and GCC;
`--packages` adds repository synchronization and a GNU hello source-build,
installation and removal without disabling Portage's sandbox FEATURES.
`test_rootfs_oci_fixture.py` checks conversion digests, exact unfiltered tar content
and hardlinks without running an image. `test_oci.py` checks native-device omission,
lower-layer replacement, guest SHM, strict path/metadata handling and concurrent
inspection while another participant writes the same store.

## Guest Credential Checks

CMake's `MAGICDESK_GUEST_FIXTURES` builds `test_credentials` for the identity,
account resolution, registry and inode contracts, plus `credentials_guest` for
intercepted calls. The device runner accepts a prepared device-side OCI layout:

```sh
python native/guest-exec-lab/test_credentials_runtime.py BUILD DEVICE_LAYOUT --packages debian
python native/guest-exec-lab/test_credentials_runtime.py BUILD DEVICE_LAYOUT --packages alpine
```

The runner requires actual UID 2000 and creates its own preserved-ownership image
and instance. It checks independent root/non-root launches, chmod/chown/access,
hardlinks, group copying and EFAULT, nondumpable processes, per-thread raw IDs,
libc set-ID coordination, fork/exec, auxv and irreversible credential drops.
Access checks include real/effective IDs across the whole path, empty-path
descriptor/current-directory access, fchdir and inotify registration permissions.
`--fixture ELF` additionally runs a glibc/musl build of `test_credentials_guest.c`
against the matching image. Bionic's raw set-ID wrappers are intentionally
thread-local; glibc/musl coordinate the same calls between threads.
`--packages` exercises ordinary package install/reinstall/remove scripts without
force-not-root flags. Kernel privilege remains separate from virtual guest root.
Reports retain every command and exact image/build.

`test_ipc_credentials_runtime.py BUILD DEVICE_STORE` stages the native helpers
and exercises connection/group snapshots, queued SCM_CREDENTIALS, credential
drops, forgery rejection, MSG_PEEK, recvmmsg, truncation, FD passing and an external
native endpoint. The selected prepared store supplies stock D-Bus and a root NSS
entry. Independent root clients share its bus; a different guest user is rejected.
`--installed` uses the APK's CLI/bundle instead of staging execution helpers;
only the test programs are uploaded. `--gdbus` adds a stock GIO client.
For Debian's activation/FD workflow:

```sh
sh native/guest-exec-lab/build-ipc-dbus.sh PREPARED_SYSROOT BUILD/ipc-dbus
python native/guest-exec-lab/test_ipc_credentials_runtime.py BUILD DEVICE_STORE --gdbus --activation BUILD/ipc-dbus
```

The activation fixture installs/removes only its own service file in the disposable
store. It checks a stock daemon's GetConnectionUnixUser, service activation and
usable descriptor delivery in both directions, under guest UID 0 and actual
UID 2000. No Android Desktop, real elevation or authentication override is used.

`test_guest_accounts_runtime.py BUILD DEVICE_STORE [--installed]` uses a disposable
Debian store with the stock passwd and D-Bus packages. It creates, modifies and
removes fixture-owned users/groups, verifies named-launch NSS/group resolution
and home ownership, and checks that a denied non-root groupadd leaves group files
unchanged. Pending package configuration must finish with both D-Bus packages
installed, a messagebus account and a clean dpkg audit. Package service-start
policy is not replaced. The runner's native/guest controls compare Unix, IP and
other netlink results while audit is explicitly unavailable in the guest.
CMake builds `guest_interfaces`; it checks raw calls, protected tasks and
application ERRNO/TRAP/KILL precedence under root, nobody and shell guest IDs.

## Watch Transport Controls

```sh
sh native/guest-exec-lab/build-watch-fixture.sh build/watch-fixture
python native/guest-exec-lab/test_watch.py build/watch-fixture
```

These standalone Bionic controls require the already selected UID 2000. They do
not install an APK or enable directory watching in the guest runtime. CMake's
`MAGICDESK_GUEST_FIXTURES` also builds `watch_queue`, `watch_read` and
`watch_activation`; none is installed into the APK.

The single-owner queue retains whole inotify records, adjacent coalescing,
bounded overflow and rejected-delivery state. Its stream socket carries readiness only.
Tests cover poll/edge-triggered epoll, short buffers, descriptor flags, dup,
SCM_RIGHTS lifetime, and a stolen marker failing without blocking the owner.
The owner does not retain a guest read end between operations. Record storage
is recycled up to its high-water mark; reads use caller-owned scratch memory.

The tracer fixture compares lazy process-wide and FD-selected read interception.
It checks zero ordinary-read stops before activation, short-record errors,
notification cancellation, EINTR, SA_RESTART and the application's signal context.
The FD-selected control additionally checks zero stops on unrelated reads after
activation, and validates the retained kernel-object identity after descriptor-number
reuse. Its microtimings describe this native fixture only, not GUI latency or
production-runtime overhead. Output memory is fixture-owned shared memory;
protected memory transfer and concurrent buffer mutation are not certified.

Two blocking transports have separate limits. A predeclared notification gate
uses the existing listener, but a filter admitting only the original read site
rejects it. The native same-site read control preserves that filter and signal
context, but is single-reader only: it restores a consumed readiness marker before
reading the queue. It does not implement production concurrent-reader ownership
or empty zero-length reads. Native inotify controls establish that an empty
nonblocking zero-length read returns EAGAIN, and that a record cannot span two
short readv vectors on the tested kernel. The mediated readv fixture exercises
only error/short-result completion within the first vector.

Activation controls explicitly observe EBUSY for a second USER_NOTIF listener,
ENOSYS rather than listener inheritance for a listenerless notification filter,
and TSYNC rejection after a peer diverges its filter chain. These are verified
limitations, not successful runtime workarounds. Production integration and
its ownership limits are described in the [watch contract](../guest-runtime/watches.md).

## Production Watch Checks

Configure `native/guest-runtime` with `-DMAGICDESK_GUEST_FIXTURES=ON`, then build
`bootstrap supervisor run service watch_guest watch_launch watch_store`.
`test_watch_runtime.py BUILD_DIRECTORY` stages immutable production binaries
through the configured MCP server, requires actual shell UID 2000, imports a
fresh disposable store and checks real guest libc inotify calls.
It neither installs an APK nor changes Desktop or access settings.

The suite covers directory/name events, rename cookies, file-data events,
read/readv/FIONREAD boundaries, concurrent readers, dup/fcntl, queued SCM_RIGHTS
through recvmsg/recvmmsg, descriptor reuse, EINTR/SA_RESTART and 64 execs with
constant filter count. The complete glibc/musl suite runs again with dumpability
disabled. Churn checks 7,680 distinct duplicate targets without unbounded filter
growth. An aggregate interception counter detects accidental ordinary-read tracing
in the unsaturated case. Two independent observers receive a third launch's
mutations. Store controls verify rollback after SIGKILL, last-reader retirement
and journal-overflow notification. Reports retain exact uploads, identities,
commands and results; a timeout is a failure, not completion evidence.

`build-watch-libc-fixture.sh glibc|musl SYSROOT OUTPUT` builds the same guest
fixture with that userspace's libc. Pass its `rootfs.tar.gz` as `--rootfs`.
Use `--contracts` with the additional CMake targets `test_inodes test_import
test_rpc` for namespace/import/RPC regressions under the same shell identity.
Host-policy exclusions in the import fixture remain reported as LIMIT.

The GIO fixture links the stock Linux library and requires its inotify backend,
not a fallback poller. Its readiness follows successful registration; a separate
writer creates, renames and deletes the file. The D-Bus check registers ordinary
session-config watches and completes a bus request. Build-only prepared inputs:

```sh
node native/guest-exec-lab/prepare.mjs build/watch-gio-sysroot --package libglib2.0-dev
sh native/guest-exec-lab/build-watch-gio.sh build/watch-gio-sysroot/sysroot build/watch-gio
python native/guest-exec-lab/prepare-watch-userspace.py PREPARED_DEBIAN_ROOTFS build/watch-userspace.tar.gz
python native/guest-exec-lab/test_watch_runtime.py BUILD_DIRECTORY \
    --userspace build/watch-userspace.tar.gz --gio build/watch-gio --contracts
```

Native fixtures and Linux libraries are not shipped in the APK. These focused
checks do not certify arbitrary application filters, cross-supervisor FD
transfer, all GUI workflows or performance against PRoot.

`test_watch_apps.py BUILD --store STORE --runtime FIXTURE_DIRECTORY
--keyboard-directory HOST_XKB --recipes RECIPE_CLASSES` uses the installed APK
and a prepared Debian/Alpine system containing Mousepad, Thunar, fonts and a
session bus. The fixture directory supplies `md-await-exit`; the recipe classes
are the existing app-side `GraphicalRecipe` fixture. On its own virtual display,
the test changes a document from another launch, accepts Mousepad's reload and
checks actual clipboard text. It creates a file externally in an open Thunar
folder, renames the visible selection, verifies the file from another process,
then moves the current folder and checks Thunar's parent navigation. Both clients
must exit with status zero. It never starts Desktop.

The guest fixture's `aliases DIRECTORY` command prints data-event attribution
before/after rename, unlink and last close with two hardlink names. It is a
characterization, not an assertion of exact Linux dentry history. Native controls
must report host hardlink permission denial rather than substituting copies.

## Developer Workflows

`prepare_vscode.py` stages official ARM64 VS Code and the Microsoft C/C++ VSIX
in an explicitly selected disposable Ubuntu store. `test_vscode_runtime.py`
opens the sandbox-enabled editor on an owned virtual display and drives stock
GDB through the extension's Debug Adapter Protocol. Its default transport is
the integrated terminal with named FIFOs; `--debug-transport pipe` separately
checks stdio transport. It verifies a breakpoint, stack/locals, step, value
change, inferior exit, editor exit and graphics/display cleanup. It does not
claim a complete Linux security sandbox.

`test_developer_workflows.py BUILD --store STORE` checks authenticated loopback
Git/SSH clone and push, CMake/Ninja/CTest with install and incremental rebuild,
npm install/ci plus worker/child/watch/HTTP behavior, and pip venv/PEP517 C-extension
wheel build/install/use/removal. Ubuntu needs Git, OpenSSH client/server, CMake,
Ninja, GCC, Node/npm and Python venv/pip/development packages and the fixture's
`mdcode` user. `--case` selects an individual workflow. Each uses a unique private
directory; SSH retains key authentication and strict ownership checks.

`test_fifo_runtime.py BUILD --store STORE` checks independent launches sharing
FIFO streams and repeated ownership lifetimes. The service fixture covers
single-launch FIFO semantics; the [FIFO contract](../guest-runtime/fifos.md)
lists readiness and watch limitations rather than treating these as certified.

`prepare_blender_gpu.py` stages explicitly supplied, hash-recorded Zink/Turnip
assets in a disposable Blender store. `test_distribution_app.py` with
`--application blender --protocol x11 --x11-manager --gpu-prefix /opt/md-gpu`
checks renderer identity, viewport drawing, captured pixels and normal closure.
The result concerns viewport acceleration, not Cycles GPU support.

## Runtime Benchmarks

`benchmark_watches.py BUILD --store STORE --baseline BUNDLE --current BUNDLE`
uses the `runtime-workloads` glibc fixture in `BUILD`. It compares metadata
traversal and process spawning with no watches, a same-process observer, and a
concurrent child observer. Warmup is excluded, sample order alternates, and
separate counter-enabled launches do not contribute timing samples. Monotonic
workload timing excludes watch registration/draining and launch preparation;
the report retains thermal/power snapshots and verifies events without overflow.
It measures neither compilation nor PRoot.

`benchmark_compile.py` compares the same Debian GCC toolchain and SQLite
amalgamation under native chroot, Termux PRoot and the installed guest runtime.
It builds the standalone CLI and PIC shared library with `-O3 -flto`, serial
`make -B -j1`, no compiler cache and a fixed compiler random seed. GNU time
runs inside each environment around make, excluding rootfs preparation,
transport, environment startup and subsequent SQL validation. Both generated
program/library paths must execute successfully; output hashes are recorded.

`--workload metadata` checks 4,096 stat/open/fstat/read operations over a prepared
tree, including actual inode identity, link count and bytes. `--workload spawn`
checks 128 fork/exec/wait cycles of the same `/bin/true`. Fixture compilation and
tree preparation remain outside timing. The native tree is prepared as the Termux
owner so sequential chroot and PRoot runs can reuse it without ownership changes.
Use `--modes guest-baseline guest --baseline-runtime REFERENCE_BUNDLE` for an
interleaved comparison of two immutable guest bundles under the same shell UID.
The reference bundle must support the same store and command contract.

Prepare a new build-only sysroot with the authenticated package helper:

```sh
node native/guest-exec-lab/prepare.mjs build/guest-compile \
    --suite trixie --package gcc --package make --package time --package coreutils
python native/guest-exec-lab/benchmark_compile.py build/guest-compile \
    --sqlite PATH_TO_SQLITE_AMALGAMATION --runtime INSTALLED_GUEST_BUNDLE
```

The default is one excluded warmup and three measured builds per mode, with
rotating order. Root is explicitly used only for chroot; its bind mounts live
in a private mount namespace and disappear on exit. PRoot uses the current
Termux UID with seccomp acceleration enabled and no fake-root extension. Guest
requires the existing MCP command service to be UID 2000 and imports its own
store from identical source bytes. Neither the APK nor its access setting is
changed. The phone must remain awake throughout the comparison.

The JSON report retains tool/package/source/runtime identities, process CPU
affinity, wall/CPU times, thermal and CPU-frequency samples, power source,
all outputs and artifact hashes. Keep charging state unchanged; a detected
change leaves the report incomplete rather than mixing power conditions.
Caches are warmed, not globally dropped; governors and CPU affinity are not
changed. Wall time is the comparable end-to-end build metric. GNU time's child
CPU accounting does not include every external supervisor/filesystem service.
The compile workload does not establish metadata-intensive performance; use
the separate workloads rather than generalizing one result. None establishes
GUI latency or cross-device speed claims. Very short native runs can reach
GNU time's output resolution; do not infer precise ratios from those values.

Use `--guest-statistics --modes guest` for a separately labelled diagnostic run
with aggregate syscall/ptrace/stop counters, filesystem/SQLite elapsed costs,
broker handling/delegation counts and supervisor/namespace-worker CPU usage.
The worker's thread CPU is included in the supervisor process total; do not add
them together. Clock sampling is enabled only in that diagnostic mode.
Keep it separate from ordinary timing series. Frequency caps
and scheduling groups can differ between these executor
identities even without changing the power source; retain the individual samples
and ranges rather than attributing every wall-time difference to interception.

Configure the native CMake build with `-DMAGICDESK_GUEST_FIXTURES=ON` to build
`libmagicdesk_guest_benchmark_namespace.so`. Run it under the same shell identity
with the prepared benchmark store as its sole argument. It executes the metadata
workload directly through the namespace engine, retaining inode/content checks
but excluding syscall interception and RPC. Its first sample warms the store;
the next three isolate engine cost. This diagnostic is not another guest runtime
or a replacement for end-to-end timings, and is not packaged in the APK.
