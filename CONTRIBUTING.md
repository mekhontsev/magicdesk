# Contributing

MagicDesk uses Gradle as its project definition. Open the repository root in
Android Studio, IntelliJ IDEA, or another Gradle-aware editor; do not open the
`app` directory as a standalone project.

## Compatibility Direction

The APK minimum is Android 14 / API 34; managed Desktop requires Android 15 /
API 35. Preserve independent tools and automation without Desktop setup or
HOME ownership. Read [Runtime API levels](docs/runtime-api-levels.md) before
changing shared prerequisites or using newer APIs.

The project aims to support as many compatible devices and firmware versions
as practical through one MagicDesk APK and one codebase. Prefer runtime
capability probing, shared Android behavior, and focused platform drivers over
model checks, product flavors, or device-specific forks. A missing optional
vendor feature should disable only that feature and remain visible in
Diagnostics.

## Build Environment

- JDK 17 or newer
- Android SDK platform and build tools 37
- Android NDK 27.3.13750724, installed as **NDK (Side by side)**

Android Studio can install the SDK and NDK components from SDK Manager. Gradle
finds a side-by-side NDK through the configured Android SDK. An explicit
`ANDROID_NDK_HOME` remains available for command-line and CI environments.

If Gradle cannot locate the Android SDK, create an untracked
`local.properties` file:

```properties
sdk.dir=/absolute/path/to/android-sdk
```

Termux builds use `$PREFIX/bin/clang` and do not require the desktop NDK
toolchain.

The current native helpers are ARM64-only. The host NDK target still uses API
35 and must be aligned/validated for API 34 before claiming native support at
the APK floor. Windows build smoke is not emulator coverage; an x86_64 emulator
matrix needs matching helper binaries. See the API-level document for the
remaining validation contract.

## Verification

Build the debug APK:

```sh
./gradlew :app:assembleDebug
```

The optional kernel add-on is a separate application:

```sh
./gradlew :kernel-fixes:assembleDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

Run the complete local verification before submitting a change:

```sh
./gradlew verifyDevelopment
```

Source checkout uses LF on every host. Runtime fixtures normalize javac's
host-specific method rendering before compiling extracted production bodies.
Identity-sensitive filesystem tests use Jimfs for inode and symlink semantics;
transfer journals still use real temporary files. These are test-only
dependencies and do not change Android storage behavior. Tests that execute
POSIX shell commands or resolve live process directories require a Unix shell
or procfs respectively; their pure validation cases also run on Windows.
CI retains unit-test XML and HTML reports even when verification fails.

Python client and SDK-audit fixtures use the standard library:

```sh
python -m unittest discover -s scripts/tests
```

To audit an explicit SDK floor without changing the APK, install Android SDK
Command-line Tools (latest) and run:

```sh
python scripts/audit-android-api.py --min-sdk 34
```

The command compiles the current debug sources, generates Lint's Gradle model,
and changes only an isolated copy of its SDK floor and merged manifest. Reports
are under `build/reports/api-audit/`. Findings do not make this audit command
fail; compiler, model and Lint execution errors do. This does not replace the
normal production-baseline Lint run or older-device testing. See
[runtime API levels](docs/runtime-api-levels.md) for scope and interpretation.

On Linux or Termux, also run the native PTY and virtual-pointer protocol fixtures:

```sh
sh scripts/verify-native.sh
```

They require a host C compiler and coreutils `timeout`, use only their own
PTYs and controlled input fixtures, and do not access physical input devices.
Linux CI runs the same script. The Windows build still compiles the Android
native helpers through the NDK; it does not execute Linux host fixtures.

Debug and pull-request builds do not require release-signing credentials.

Choose device verification by the changed layer. Shared tools and automation
need their actual workflows without Desktop; Android 14 cannot run Desktop
self-tests. Window/focus work needs phone and simulated self-tests, and shared
area/display ownership also needs wired coverage. A simulated display on newer
Android does not emulate an older OS. Documentation-only changes need link,
contract and diff checks rather than an APK installation.

For privileged device testing, start an installed Shizuku manager with the
canonical shell launcher. From an already authorized ADB connection:

```sh
adb -s SERIAL push scripts/start-shizuku-shell.sh /data/local/tmp/magicdesk-start-shizuku-shell.sh
adb -s SERIAL shell sh /data/local/tmp/magicdesk-start-shizuku-shell.sh
```

The launcher preserves a real ADB shell's identity, or uses local Magisk to
establish it when invoked from Termux. It verifies UID 2000, the shell SELinux
domain, and supplementary groups. An existing server is verified without being
restarted. Keep the explicit ADB serial when more than one device is in use.

To test the Standard Android platform driver on ZTE/nubia hardware, build:

```sh
./gradlew :app:assembleDebug -PMAGICDESK_PLATFORM_OVERRIDE=android
```

This debug-only override changes platform-driver selection without introducing
a product flavor or a separate device build. Compatibility Diagnostics records
the active override. Release builds always use automatic platform selection.

To exercise Android 15 windowing semantics on a newer device, build:

```sh
./gradlew :app:assembleDebug -PMAGICDESK_FRAMEWORK_OVERRIDE=android15
```

The framework profile masks newer task observations and selects the Android 15
caption-source strategy while retaining the host device's actual Binder ABI.
It is independent from platform selection, so both axes can be tested together:

```sh
./gradlew :app:assembleDebug \
  -PMAGICDESK_FRAMEWORK_OVERRIDE=android15 \
  -PMAGICDESK_PLATFORM_OVERRIDE=android
```

Framework and platform overrides are debug-only and are recorded in
Compatibility Diagnostics. A future vendor driver belongs in the existing
platform composition root; its test profile must not pretend that unavailable
firmware services exist.

Framework compatibility code has enforced ownership boundaries. Add hidden
window-container primitives only to `FrameworkWindowingApi`, release-specific
semantics only to `FrameworkWindowingCompat`, raw task members only to
`HiddenTaskApi`, and normalized task/input state only through their snapshot
sources. Timing is similarly explicit: use `BoundedStateAwaiter` for bounded
state polling, `EventDrivenWaits` for monitor signals, and `RuntimeDelays` only
for intentional non-state delays. The isolation unit tests reject direct
reflection, task/input text dumps, and raw sleep/wait calls elsewhere.

## Adding Platform Support

Start from a complete compatibility report and the manual checklist in
[Compatibility](docs/compatibility.md). Contributors using a coding agent
should follow [AI-Assisted Device Support](docs/ai-assisted-device-porting.md),
which defines the evidence, MCP observation, architecture classification, and
device verification workflow. Add or update a deterministic fixture under
`app/src/test/resources/compatibility` before changing selection logic. Exact
tested fingerprints and confirmed scope belong in
`app/src/main/assets/compatibility/firmware-profiles.json`; they must not select
a driver or contain executable configuration.

When firmware behavior is genuinely required, implement a focused
`PlatformExtension` under its own `platform.<vendor>` package and declare only
the `PlatformComponent` values it replaces. Keep display lifecycle in the four
shared display drivers, SoC services behind `SocDisplayModeBackend`, semantic
window policy behind `DesktopWindowTransitionGateway`, and shell/WMShell
transactions in their existing executors. Extend `PlatformSourceIsolationTest`
when adding a new vendor package so vendor identifiers cannot leak into shared
runtime code.

## Repository Hygiene

Do not commit IDE metadata, `local.properties`, generated build output,
keystores, device captures, or diagnostic reports containing local device
information. Before submitting a change, check `git status` and run the
verification commands above.
