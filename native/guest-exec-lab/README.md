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
```

Application checks require a current debug APK with the guest CLI installed.
They use its runtime and the production Java graphical-session wrapper.
The fixture-only `md-prepare-applications` configures real shell NSS identity,
toolkit caches, CA certificates and an explicit public DNS resolver in the owned
test store. `--network` opts into DNS and certificate-verified HTTPS checks.
Mousepad coverage includes editing, saving, fresh-process file readback and
reopening; both applications must exit normally, not merely lose their window.
Settings checks exercise real D-Bus activation and persistence, not a substituted
keyfile backend. Application logs and hashed screenshots accompany the JSON report.

Device checks use the configured MagicDesk MCP connection and require selected
shell UID 2000. They do not change identity, install an APK or start Desktop.
Graphical checks use a private virtual display and clean up their own sessions.
Results retain exact device/build identities, commands, failures and known limits.
Prepared shell-owned test stores remain at the recorded path for inspection.

Capability fault injection checks that missing kernel calls reject only guest
execution. Process fixtures cover concurrent distinct stores, cancellation and
orphan ownership. These checks do not emulate an old kernel or prove compatibility
with every distribution. See the native component's coverage and limits.
