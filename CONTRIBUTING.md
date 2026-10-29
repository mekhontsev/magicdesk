# Contributing

MagicDesk uses Gradle as its project definition. Open the repository root in
Android Studio, IntelliJ IDEA, or another Gradle-aware editor; do not open the
`app` directory as a standalone project.

- [Branches And Releases](#branches-and-releases)
- [Compatibility Direction](#compatibility-direction)
- [Build Environment](#build-environment)
- [Verification](#verification)
- [Adding Platform Support](#adding-platform-support)
- [Translations](#translations)
- [Licensing](#licensing)
- [Repository Hygiene](#repository-hygiene)

## Branches And Releases

The repository uses two permanent branches:

- `main` is the verified, release-ready line and remains the default branch.
- `develop` is the integration line for everyday changes and external pull
  requests. Base contributions on `develop` and select it as the PR target.

Maintainers can commit directly to `develop`; per-task branches are not required.
Keep changes reviewable and separate no-functional-change (NFC) refactoring from
new behavior. CI verifies both branches and their pull requests, including
documentation-only changes so required checks always complete. Linux `build`
and `windows-smoke` checks are required before updating `main`. Both branches
reject force pushes and deletion; no second reviewer is required.

Only a successful `develop` push updates the release-signed development APK.
The rolling prerelease keeps its existing `development` tag and download URL;
it is not a third branch. PR builds do not receive signing credentials. Changes
must pass both Linux and Windows jobs before the development APK is published.

To release, merge `develop` into `main` with an ordinary merge, verify that exact
`main` commit in CI and complete the device checks required by the changed
subsystems. Tag it `vVERSION`, matching `magicDeskVersionName`. The release
workflow checks tag/version identity, ancestry in `main`, versionCode and a
successful main CI run for that exact commit before signing. Manual signed
release builds must also run on `main`.

After publishing, merge `main` back into `develop`, then run
`scripts/start-next-version.sh VERSION VERSION_CODE` there and publish the
version-bump commit. `main` keeps the released version; `develop` must have a
newer version and versionCode. Ordinary pushes do not bump either value.
CI adds a unique suffix to signed branch artifacts.

Urgent fixes may start on `main`, but must be merged back into `develop`.
Preserve common ancestry between permanent branches: do not squash their merges,
rewrite published history, or move numbered release tags. The stable installer
URL stays on `main`; use the script from `develop` when testing unreleased changes.

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
- CMake 3.22+, Ninja, Python 3, Bison, patch and a host C compiler for embedded X11
- CMake 3.24+, Meson 1.9.1, make, pkg-config, Flex and host Expat development
  files for Wayland

Initialize source dependencies with `git submodule update --init --recursive`.
On Windows, the X11 host generators use MSYS2 Bison/patch and UCRT64 GCC;
put their `usr/bin` and `ucrt64/bin` directories on `PATH`. The bundled mksh
build also needs CMake 3.24+ and POSIX `sh`, `sed`, `sort` and `tr` on the host.
Set `MAGICDESK_BUILD_SHELL` to the absolute MSYS2 `usr/bin/sh.exe` path;
its generator wrapper puts the shell's companion tools first on `PATH`.
Windows CI explicitly selects MSYS2 and builds mksh in a directory containing
spaces before the complete Gradle build. Android native libraries still use
the NDK, not the host compiler. Termux uses its packaged
CMake, Ninja, Python, Bison, patch and Clang. The guest file helper is statically
linked so it can run inside glibc or musl environments without their libraries;
Termux builds also need `ndk-multilib-native-static` for the NDK's libc archive.

Android Studio can install the SDK and NDK components from SDK Manager. Gradle
finds a side-by-side NDK through the configured Android SDK. An explicit
`ANDROID_NDK_HOME` remains available for command-line and CI environments.

If Gradle cannot locate the Android SDK, create an untracked
`local.properties` file:

```properties
sdk.dir=/absolute/path/to/android-sdk
```

Termux builds use `$PREFIX/bin/clang` and `$PREFIX/bin/llvm-strip` and do not
require the desktop NDK toolchain. APK packaging strips debug sections and
unneeded symbols on every build host; compiler outputs remain available in
build directories for diagnostics. Dynamic loader symbols and unwind data are
retained. `scripts/verify-apks.sh` also checks the PTY helper's transfer size.
On Linux, Wayland's pinned native dependencies are cross-compiled
with the NDK as part of the Gradle build. The host protocol scanner is built
separately; no system Wayland development libraries are required. On Windows,
provide an exported ARM64 runtime from a Linux/Termux build through
`-PmagicDeskWaylandRuntime=/path/to/runtime`. CI transfers that artifact between
its Linux and Windows jobs. See [Wayland build details](docs/wayland.md#build).

The current native helpers are ARM64-only. Both helper compiler paths target the
APK's minimum SDK, API 34. Device coverage is per helper: the virtual mouse/uinput
path is verified on Samsung SM-A528B/API 34; PTY and graphical runtime validation
at that floor remains pending.
Windows build smoke is not emulator coverage; an x86_64 emulator
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

On Linux or Termux, also run the native PTY, virtual-pointer, process-signal and
X11 icon/density fixtures:

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
For a compatible alternate manager such as Shevery, pass its configured package
as the launcher's first argument:

```sh
adb -s SERIAL shell sh /data/local/tmp/magicdesk-start-shizuku-shell.sh com.hamondev.shevery
```

This selects the installed manager, not another service identity. Use the same
package in MagicDesk's integration settings; see [setup](docs/getting-started.md#install).

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

## Translations

English UI resources in `app/src/main/res/values` are the source and fallback.
Keep translations in Android `values-<locale>` resource directories and declare
supported language tags in `app/src/main/res/xml/locales_config.xml`. Keep
resource names, format argument positions/types and array order unchanged;
provide the locale's plural forms. Use resource labels for visible enum choices,
not translated protocol keys. Logs, diagnostic reports and automation identifiers
remain in English. Linux application languages are independent of the Android UI.

Run `LocalizationResourcesTest`, `:app:lintDebug` and `:app:assembleDebug` after
translation changes. Check long labels and language switching in native windows;
changing the app language must not end a retained terminal session.

## Licensing

MagicDesk is distributed under GNU GPL version 3 (`GPL-3.0-only`).
Contributions to MagicDesk use that license unless a file explicitly retains
another compatible third-party license. Preserve upstream copyright and
license notices. See [licensing and source availability](docs/licensing.md).

## Repository Hygiene

Documentation describes the current contract, prerequisites and limitations.
Keep module READMEs focused on purpose, ownership, verification and source/license
information; link to the relevant guide for details. Put release differences in
release notes, not feature descriptions. Keep current verification evidence and
the reasons for safety constraints. Fixed-bug stories, replaced mechanisms and
step-by-step experiments belong in Git and run artifacts, not maintained guides.
Remove resolved backlog items and duplicated explanations.

Do not commit IDE metadata, `local.properties`, generated build output,
keystores, device captures, or diagnostic reports containing local device
information. Before submitting a change, check `git status` and run the
verification commands above.
