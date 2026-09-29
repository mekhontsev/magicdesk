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
guest file saving, Wayland file-dialog mapping and dismissal, concurrent applications in
one store, recipe reuse and actual process exit after protocol close. Catalog
events identify Wayland dependent windows without guessed IDs. X11 transient
dialogs require native-family observation and are not covered by this fixture;
they are not entries in the observed toplevel catalog. Inotify receipts identify
the exact command's start and exit. Screenshots are point-in-time observations,
not synchronization barriers. The test restores an existing plain-text clipboard
and refuses to replace non-text contents. Launchers and their receipts remain in
the prepared test directory for inspection; their launches participate in Recent.

The default calculator workflow checks `7+8=15`. An additional
`--repeat-digit` check sends `2+2` without a pacing delay and requires `4`.
It currently reads `2` on the tested Wayland stack and retains a separate failing
`*-shortcuts-repeat-digit-results.json`; it must not be treated as passing
input coverage or hidden by a sleep. Whether GTK activation, application handling
or input delivery owns this failure is not yet established.

Device checks use the configured MagicDesk MCP connection and require selected
shell UID 2000. They do not change identity, install an APK or start Desktop.
Graphical checks use a private virtual display and clean up their own sessions.
Results retain exact device/build identities, commands, failures and known limits.
Prepared shell-owned test stores remain at the recorded path for inspection.

Capability fault injection checks that missing kernel calls reject only guest
execution. Process fixtures cover concurrent distinct stores, cancellation and
orphan ownership. These checks do not emulate an old kernel or prove compatibility
with every distribution. See the native component's coverage and limits.
