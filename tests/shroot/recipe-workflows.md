# Distribution And Installer Workflows

Use these checks for the installer, catalog, graphics or Android file exchange.
Run the commands from the MagicDesk checkout, with the [integration adapter](README.md)
and the Shroot build matching the APK. Set these paths before the examples:

```sh
export APP_TESTS="$PWD/tests/shroot"
export SHROOT_SOURCE="${SHROOT_SOURCE:-$PWD/native/shroot}"
export PYTHONPATH="$APP_TESTS${PYTHONPATH:+:$PYTHONPATH}"
export SHROOT_TEST_ADAPTER=magicdesk_executor
export SHROOT_TEST_EXECUTOR=shell
```

The examples use Shell. Select User only for drivers that implement that route
and use the matching fixture APK. Local native tests need none of these settings.

## Installer Acceptance

A disposable-library recipe test is not end-to-end installer acceptance. Before
a release, also exercise the ordinary user path with the packaged APK:

1. Open [MagicDesk Console](../../docs/getting-started.md#open-the-android-shell-console)
   and download the maintained installer.
2. Use the default environment library, without a test-only
   `MAGICDESK_GUEST_HOME` override or staged helper bundle.
3. Exercise interactive configuration, DNS repair/cancellation before image
   work, and package-setup retry in the same environment after a resolver
   failure. Use a new independent environment name and retain an incompatible
   cached image to verify format-aware import without deleting existing stores.
4. Refresh Apps, find the installed application and whole-desktop entries in
   Start, and launch them without manually creating shortcuts.
5. Verify the selected protocol, usable content/input and applicable audio/GPU
   services. Close and relaunch through the same discovered entry.
6. Exercise `--resume` without replacing user data or silently changing DNS.

Custom-library tests remain useful for isolation. Their entries are not
automatically registered in the Android default-library catalog. Test
`--gui apps` and a whole-desktop profile separately; installing application
packages does not verify a desktop-session launcher.

`test_installer.py --executor app|shell` runs the public recipe in a console
of the selected Android identity. Use `--library` for a disposable catalog and
`--output` for separate receipts. The test checks that a normal guest user's
file survives `--resume`. It records its explicit `--locale` and `--timezone` selections
(defaults: `en_US.UTF-8` and `UTC`). These controlled fixture defaults are not
the installer's Android-locale default. Test that default separately through
interactive setup, including the keep-on-resume behavior.

Alpine fresh `--gui apps --gpu keep` installation and resume were exercised
in independent empty catalogs on Android 16. Receipts under
`build/alpine-regression-523f33b/` are `app/installer-fixed/fresh.json`
(User) and `shell/installer/receipt.json` (Shell). Neither builds Turnip.

### Recipe Setup And Graphics

`test_installer_setup.py BUILD STORE [--dns ADDRESS]` runs the actual guest
payload emitted by `install_linux.sh --print-system-setup` in a disposable store.
It records the payload hash, uses the production offline resolver owner when DNS
is explicitly selected, and retains the recipe's package/keyring cleanup.
It defaults to `--gui none`; `--gui xfce|weston|apps` and `--fonts basic`
exercise package setup for GUI profiles, not rendered GUI acceptance or an init boot.
`--extra-packages` exercises the installer's extra-package argument. The GUI
fixture requires `pidof` and `timeout`; minimal Fedora needs
`--extra-packages procps-ng` for its component-readiness check.
`test_recipe_graphics.py BUILD STORE` then reads those installed desktop entries
and executes their commands through the production routed graphical recipe using
the selected native build. It checks X11/Wayland Mousepad input and saved-file
readback, Xfce/Weston pixels, and retained whole-desktop viewers on an isolated
display. Use `--entry` to select a case and `--lease` to retain an existing phone
wake lease. Plasma entries are `plasma-x11` and `plasma-wayland`; use
`--user steamos --home /home/steamos` for the SteamOS recipe. The store must be idle and disposable; the fixture stops its own
launches and removes its display. This does not verify named-catalog registration,
audio output or Turnip. Keep at most one long foreground package-setup request
running beside GUI observation so MCP's request workers remain available.
`test_arch_runtime.py BUILD STORE --install-package` separately checks pacman's
unmodified sandbox, Landlock denials, synchronization, package installation,
reinstallation, file verification and removal of a fixture-installed package.

### Retained Sessions And Booted Workloads

Retained recipe-installed sessions can run `test_booted_containers.py --session
build/installed-NAME/session.json --engine podman --internet` (or `--engine
docker`). Start a non-graphical session with `installed_desktop.py start --name
NAME --headless --build BUILD --network-profile routed`; add `--init openrc`
for Alpine. The container fixture's external port observer needs Android
loopback access; containers still create their own private bridges. This uses the same
init/control owner without acquiring a display, GPU, HOME role or user desktop;
it requires the guest's Python, D-Bus and init packages, not Weston. Use `run
--root` for commands and always `stop --name NAME` afterward. Default-target
boot remains a separate `test_os_boot.py` assertion. Docker defaults to its stock
service; alternate daemon profiles are
explicit diagnostic cases. Docker's `--image-archive /guest/path/image.tar`
loads a prepared image when it is absent, instead of pulling from a registry;
the requested image must be present after loading. This separates local container
acceptance from download availability without weakening the network checks.
`--trace-runtime` traces the fixture-owned rootless Docker runtime for diagnosis;
`--trace-network` attaches to its daemon after set-ID startup and image
preparation, and observes network, namespace and capability calls. Neither
is a performance run. `test_booted_nspawn.py --session SESSION --machine
MACHINE` uses an existing `/var/lib/machines/MACHINE`, while
`test_booted_lxc.py --session SESSION --archive ROOTFS.tar.gz` creates and removes
its own container from a systemd/D-Bus/iproute2/Python rootfs. The latter two
share readiness and TCP/UDP assertions and run three boot/service/network/stop
cycles, including forced termination. Retain original application sandbox
policy; successful command-only startup is not boot acceptance.
Add `--command` to the nspawn fixture to check command mode with an existing
machine image: private users/PID/mount/network namespaces, read-only and writable
binds, outbound and published TCP/UDP, normal exit, killed leader, and removal
of nftables publications and veth interfaces across three launches.

Graphical `installed_desktop.py` sessions normally hold a 30-minute awake lease on their
own virtual display, renewed by `run`, `attach` and `capture`. A phone-screen
lease does not keep that display active. Release a separately owned lease before
starting the fixture; it never replaces another owner. After lease expiry,
stop/restart the fixture before qualifying GUI results. `stop` removes the owned
display and its lease; headless sessions acquire neither. `start --offscreen`
retains a graphical output without an Android display or viewer and never
acquires or renews an awake lease. The caller must keep the device awake when
qualifying offscreen graphics or timing results.
`start --statistics` enables the native operation-cost and entry counters for
either session type; `stop` retains their shutdown output in `boot.log`.
Leave it off for ordinary timing comparisons and use `--diagnostics` separately
when a full transition trace is needed.

Retained and recipe graphics fixtures wait for the launcher exit receipt before
collecting logs or releasing their outputs. An empty launch registry alone is
not process-exit evidence. The same completion owner covers User and Shell,
headless and graphical launches, including cancellation and failed exits.
The authenticated command controller supplies `/dev/null` as stdin for its
noninteractive commands, independently of the init/compositor's inherited
descriptors. Interactive programs use the PTY fixtures instead.

## Graphical Acceptance

The GUI extension fixture `fixtures/vscode-gui.py` drives
`fixtures/vscode-debug.js` through an existing recipe desktop: editor/save and
watcher changes, window reload, integrated terminal, GDB variable update and
normal close. Browser GUI acceptance additionally requires rendered pixels,
actual keyboard/pointer input, download and portal selection, not only a
healthy browser process. The current desktop/container receipts and explicit
limits are in [SVC integration evidence](../../native/shroot/tests/svc-rewriting.md#desktop-and-container-acceptance).

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

## Prepared Rootfs Fixtures

The prepared-rootfs builder is part of Shroot. Graphical launch recipes and Android
presentation belong to the integrating application's test adapter.
The preparation commands below call the selected checkout explicitly.

`prepare.mjs` authenticates Debian archive metadata and packages, then extracts
an isolated test rootfs without maintainer scripts or host installation.
`build_prepared.py` reuses an existing native CMake build and cross-compiles the
glibc probes with Clang/LLD, then packages them with the verified userspace.
Use `SHROOT_BUILD_TESTS=ON` and `SHROOT_STATIC_RUNTIME=ON`: component fixtures
embedded in the guest must not depend on the host's libc. `--graphics` additionally
compiles available native graphical probes; it needs a host `wayland-scanner`.
The application integration builds its own launch recipe. Installed guest
launches require none of these tools.
Exact inputs remain in the build's `manifest.json`.

```sh
node "$SHROOT_SOURCE/tests/prepare.mjs" build/guest-fixtures --gtk
python "$SHROOT_SOURCE/tests/build_prepared.py" build/guest-fixtures --native-build build/native --graphics
python "$SHROOT_SOURCE/tests/test_device.py" build/guest-fixtures --packages
python "$APP_TESTS/test_graphics.py" build/guest-fixtures
python "$APP_TESTS/test_graphics.py" build/guest-fixtures --gtk
```

The Android prepared-userspace runner also accepts `--executor app
--native-build /path/to/exact/apk/native/build`. Install a debug fixture APK
containing that build's prepared drivers first. It uses the same assertions and
records native pathname-socket and hardlink controls for the selected identity;
Shell-only denials are not required of an application UID. Direct execution uses
immutable APK executables, while the imported userspace remains private test data.
`--output` separates each run's complete logs and identity receipts.

For real application workflows, use a separate prepared build:

```sh
node "$SHROOT_SOURCE/tests/prepare.mjs" build/guest-applications --applications
python "$SHROOT_SOURCE/tests/build_prepared.py" build/guest-applications --native-build build/native --graphics
python "$SHROOT_SOURCE/tests/test_device.py" build/guest-applications --packages
python "$APP_TESTS/test_graphics.py" build/guest-applications --application mousepad --network
python "$APP_TESTS/test_graphics.py" build/guest-applications --application galculator
python "$APP_TESTS/test_graphics.py" build/guest-applications --application mousepad --routed
python "$APP_TESTS/test_graphics.py" build/guest-applications --application mousepad --routed --protocol x11
python "$APP_TESTS/test_shortcuts.py" build/guest-applications --protocol wayland
python "$APP_TESTS/test_shortcuts.py" build/guest-applications --protocol x11
```

For Qt text-input-v3, prepare the newer Debian userspace separately:

```sh
node "$SHROOT_SOURCE/tests/prepare.mjs" build/guest-qt --qt --suite trixie
python "$SHROOT_SOURCE/tests/build_prepared.py" build/guest-qt --native-build build/native --graphics
python "$SHROOT_SOURCE/tests/test_device.py" build/guest-qt --packages
python "$APP_TESTS/test_qt.py" build/guest-qt
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

`test_graphical_session.py --helper APK_BUILD/libmagicdesk_guest_files.so --store STORE`
checks the packaged session launcher without a viewer or Desktop. Repeat `--store`
for multiple distributions; `--user` selects an existing guest account (default
`root`, never Android root). It uses real session D-Bus, verifies literal arguments,
X11 authority setup before bus startup, private-directory permissions, exit status
and cleanup for direct X11, routed X11 and Wayland. Temporary helper files are
removed. Host tests additionally cover the authenticated file worker and failed
bus/unmount paths. This is session-launch coverage, not rendered GUI acceptance.

Capability fault injection checks that missing kernel calls reject only guest
execution. Process fixtures cover concurrent distinct stores, cancellation and
orphan ownership. These checks do not emulate an old kernel or prove compatibility
with every distribution. See the native component's coverage and limits.
The native prepared-command fixture runs under the selected shell identity and
checks interpreter descriptor pinning across name replacement, borrowed/unlinked
executables, argument identity and descriptor cleanup. It is not run in the
Termux app domain, whose syscall restrictions differ from the guest executor.
