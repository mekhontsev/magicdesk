# MagicDesk Architecture

This document describes MagicDesk's shared tools, automation, display resources
and optional managed Desktop. The core uses Android services; focused firmware
and SoC adapters extend individual capabilities. It is intended for contributors,
reviewers and users diagnosing compatibility problems.

- [Runtime Layers](#runtime-layers)
- [Design Principles](#design-principles)
- [Modules](#modules)
- [Main Application Boundaries](#main-application-boundaries)
- [Implementation Constraints](#implementation-constraints)
- [Build And Release Boundaries](#build-and-release-boundaries)

## Runtime Layers

`MagicDeskApplication` initializes shared runtime state only in the primary app
process. Auxiliary Activity processes retain their application context but do not
start privilege transports, recover HOME or URI grants, or open the shared
diagnostics journal. The structural backstop uses its explicit input-policy
Binder; self-test windows communicate through their fixture protocol.

The APK requires Android 14 (API 34); managed Desktop requires Android 15
(API 35). Shared services and ordinary built-in Activity windows do not require
Desktop. The [API-level contract](runtime-api-levels.md) records OS-dependent
behavior, static verification and remaining device coverage.

- Shared services own files, profiles, content, shell execution, Termux PTYs
  and embedded X11/Wayland sessions. [Linux audio](audio.md) has independent
  launch leases, executor-side socket admission and an app-UID PipeWire/AAudio
  service; it is not owned by a renderer or Desktop.
  `LinuxLaunchServices` also retains [Shroot gamepad input](gamepads.md): Android
  hosts supply focus and events, while native guest providers own Linux device
  semantics. Neither input-device discovery nor force feedback belongs to X11
  or Wayland protocol code.
  MCP is an authorized adapter to these services, not their lifetime owner.
  `hosted-runtime` contains the shared process-context adapter and retained-server
  lifecycle used by X11 and the [Wayland runtime](wayland.md).
  Its [graphics backend](graphics.md) supplies Vulkan/software composition,
  HardwareBuffer ownership, synchronization and Android presentation to both
  protocols. Renderer selection does not change executor identity or placement.
  Neither module owns Android tasks, Desktop or privilege startup. Wayland's
  compositor, frame transport and Android presentation have separate owners;
  `GraphicalSessions` supplies shared manager and automation controls. Protocol
  owners retain their catalogs; `HostedWindowPresentation` owns Android placement
  reservations and replacement hosts for both protocols.
- `ToolApplications` and `ToolLaunchTarget` select ordinary Activity
  placement or the existing managed Desktop launch path. Phone control-panel
  tools do not acquire HOME or require Desktop provisioning. Background launches
  and privileged cross-display placement use the shell service; ordinary phone
  launches and interactive app-only external launches use public Activity options.
  `InteractiveActivityLaunch` owns that UI choice. Without shell, a live Activity
  can request any accessible display, subject to Android's Intent-specific
  launch check and secondary-Activity feature. Pinned destinations retain identity
  validation through the shared catalog, including the app-only inventory;
  launches alongside an active Desktop retain its ownership handoff. A rejected
  local launch never retries under a more privileged identity. Terminals and Linux graphics
  share the same own-task reactivation through `ActivityManager.getAppTasks()`;
  a missing own task is distinct from one that needs privileged placement.
  The Android integration gateway uses the same placement selection for
  third-party Activities, content, shortcuts and notification actions.
  Ordinary launches default to fullscreen. Explicit freeform uses the same
  privileged Activity transport, with bounds and STANDARD activity type, without
  acquiring HOME or Desktop. `PlatformWindowingDriver` owns firmware admission;
  Nubia requires managed Desktop for its task/Recents recovery. The launcher
  and automation share this policy, including retained-window placement.
- `DisplayOperations` creates and lists display resources without starting
  Desktop. A viewer, an owned virtual display and a Desktop session have separate
  lifetimes. Removal still observes the existing session cleanup boundary.
  `DisplayRemovalRequests` joins identical in-flight removal requests and keeps
  successful release receipts only while their exact displays remain visible
  in the next catalog snapshot. Missing displays need no cleanup; reused IDs
  cannot select replacements. Completion reconciles Desktop ownership only if
  that optional runtime has already been initialized.
- `MagicDeskRuntimeService` hosts tools and automation independently. Its Desktop
  task observer and session coordinators initialize only for Desktop.
  Display input initializes on explicit control or Desktop preparation. Merely
  opening an application or creating a display does not claim input.
  Ordinary Activity launch primitives live in `FrameworkActivityLaunchApi`;
  virtual display access does not eagerly initialize the window organizer.
- `RuntimeCapabilities` publishes service prerequisites separately from MCP
  grants. A met prerequisite is not a successful device capability probe.
  Desktop entry points reject unsupported SDKs before display preparation or
  service promotion. A direct service Intent cannot promote Desktop or crash
  independent tools and automation on an unsupported SDK.

## Design Principles

Command execution selects **User**, **Shell** or **Termux** independently of
terminal/graphics presentation and Linux storage. `CommandExecution` captures
the actual UID, home and endpoint authority; retained sessions never switch
executor on reconnect or failure. User and Shell share the native PTY/pipe
protocol and managed Shroot storage. Shell remains named Shell when running
with UID 0; root authority is not a request for a native-root guest engine.

MagicDesk follows these constraints:

1. Android applications remain real Android tasks.
2. The firmware's `ShellTaskOrganizer` and native window decorations remain in
   control of move, resize, snap, maximize, minimize, and close.
3. Runtime system access uses one authorized command service. Shizuku and
   optional direct root differ only at its startup/connection boundary.
4. Device-specific operations are narrow, reversible, and checked before use.
5. Background work is event-driven where Android exposes an event source.
6. Optional kernel code stays outside the main APK.
7. Display transport, firmware integration, SoC services, and shell execution
   remain independent boundaries.
8. Interfaces represent external boundaries or multiple real implementations;
   they are not introduced only to move code between files.
9. User actions, tests, MCP, and Android system agents share one typed
   automation gateway instead of duplicating task or session policy.

MagicDesk does not register a competing task organizer, host applications in
surrogate activities, draw replacement captions, patch SystemUI,
or require a Magisk module.

Dependencies point in one direction:

```text
activities, desktop UI, and automation adapters
        |
shared automation gateway + controllers and session orchestration
        |
task, display, input, storage, and capture contracts
        |
Android shell adapters + selected platform and SoC backends
```

UI code does not select firmware implementations. Platform and SoC adapters
do not own desktop UI or session state. Runtime composition occurs only in the
[platform registries](architecture-framework.md#platform-services).

## Modules

| Component | Path or package | Responsibility |
| --- | --- | --- |
| Main application | `io.github.mekhontsev.magicdesk` | Phone control, desktop shell, taskbar, setup, diagnostics, and runtime service |
| Task transfer boundary | `DesktopTaskTransfer` | Applies the freeform or fullscreen cross-display protocol for a running task |
| Phone desktop wallpaper policy | `ShellPhoneDesktopWallpaperPolicy` | Keeps the MagicDesk HOME surface visible below standard freeform tasks |
| Fullscreen topology | `ShellFullscreenTaskArea` | Owns per-task fullscreen planes on every desktop target |
| Hidden API stubs | `hidden-api-stubs/` | Compile-time signatures only; never packaged |
| Terminal emulator | `terminal-emulator/` | Local byte-stream parser, screen buffers and terminal graphics |
| Shared graphical runtime | `hosted-runtime/` | Server lifecycle, Vulkan/software composition, buffer transport and Android frame presentation |
| Linux audio runtime | `audio-runtime/` | PipeWire protocols, mixing and AAudio output in the private audio process |
| X11 Android runtime | `x11-runtime/` | Server entry point, Binder connection, JNI and Android renderer adapters |
| X11 native engine | `vendor/magicdesk-x11/lorie/src/main/cpp/` | X server, protocol and adapters to shared multi-output graphics |
| Wayland runtime | `wayland-runtime/` | wlroots compositor, protocol state, client admission and Android adapters |
| Shroot native runtime | `native/shroot/` | Optional ARM64 Linux execution, supervision, inode namespace and OCI image tools under the selected command identity |
| Linux environment manager | `GuestEnvironmentLibrary`, `GuestOciRegistry` | Named stores, verified public OCI acquisition and shared image dependencies; existing PTY/graphics services own launch presentation |
| Launcher template | `launcher-template/` | Build-time unsigned Activity APK used to export launcher entries; not a standalone product |
| Mouse helper | `native/magicdesk_uinput_bridge.c` | Binder-owned relative phone pointer |
| Kernel Fixes add-on | `io.github.mekhontsev.magicdesk.kernel` | Independent, manually launched, firmware-specific root fixes |

The main APK contains no `.ko`, kernel loader, or reference
to the add-on package. The two applications share a repository but have no
runtime integration and are not distributed through the same release path.

## Main Application Boundaries

Use one focused reference for each ownership boundary:

| Boundary | Reference |
| --- | --- |
| Architecture Guardrails | [architecture-guardrails.md](architecture-guardrails.md) |
| Application And Shell UI | [architecture-applications.md](architecture-applications.md) |
| Automation And Diagnostics | [architecture-automation.md](architecture-automation.md) |
| Framework And Platform Adapters | [architecture-framework.md](architecture-framework.md) |
| Shared Service Implementation | [architecture-services.md](architecture-services.md) |
| Displays And Desktop Sessions | [architecture-displays.md](architecture-displays.md) |
| Application Windows And Transitions | [architecture-windows.md](architecture-windows.md) |

## Implementation Constraints

These constraints define the supported implementation paths:

- A custom caption overlay cannot stay atomically attached to a task leash.
- Public freeform launch from an ordinary app UID is normalized to fullscreen
  on the verified firmware.
- Every configured desktop uses standard-workspace freeform tasks and
  independent per-task fullscreen planes. Session cleanup drains every owned
  plane and its structural anchor.
- Every desktop target hosts the taskbar in one root-level, always-on-top
  organizer area beside the standard workspace. Its chrome task accepts focus
  only for a requested focusable panel or dialog; application tasks never enter
  that area.
- Moving a running task through display 0 can kill or recreate the application.
- Fixed sleeps around task transitions are both visible and race-prone.
- Generic configuration changes cannot reliably refresh stale insets: some
  applications recreate while others handle the change in place. Refresh the
  exact task-local caption source instead.
- Asynchronous add/remove of the replacement inset source can be coalesced by
  Nubia before the client observes it; both stages require sync callbacks.
- Physical devices keep their original Android event streams and identities.
  The key-only shortcut filter is restricted to confirmed routed keyboards;
  outside Desktop it handles the display-switch gesture and forwards modifier
  chords/system keys to a focused whole-Linux-desktop host. Ordinary typing and
  pointer motion retain Android dispatch. Power and volume keys remain Android's.
  Phone pointer injection is a separate virtual device, not physical relay.
- Physical phone-panel control uses shared Android commands and a scoped CPU
  wake lock. Optional process protection uses only transient vendor
  service-working hints; no persistent freezer whitelist is installed.
- ZTE audio source `80` is a `MediaRecorder` path. Replacing it with
  `AudioRecord` fails in AudioFlinger even for a privileged UserService.

Additional vendor-level evidence is preserved in
[Nubia vendor interface audit](nubia-vendor-audit.md).

Maintenance follows the same ownership rules. New external implementations
belong in dedicated `platform/` or `soc/` packages; the broad root package is
split only when a new independently owned subsystem provides a real boundary.
Large shell, input, and Activity orchestration classes are divided by resource
ownership rather than file size. Private Android APIs remain isolated behind
capability-checked adapters and fail closed. Changes to task-display-area
launching, shell task observation, input bridges, or the UserService require
phone, simulated, and relevant physical-display self-tests because host-only
tests cannot prove firmware behavior.

## Build And Release Boundaries

The production build includes these Gradle modules:

- `app`: main MagicDesk APK;
- `hidden-api-stubs`: compile-only framework signatures;
- `kernel-fixes`: independent optional APK;
- `terminal-emulator`: locally maintained terminal parser and screen model;
- `hosted-runtime`: shared graphical-process context, retained-server lifecycle,
  Vulkan/software graphics, frame transport and Android presentation;
- `x11-runtime`: MagicDesk's Android X11 runtime and JNI adapter, linking the
  fork's native engine. No upstream Java or compile-only X11 stubs are used;
- `wayland-runtime`: the embedded wlroots compositor, immutable frame
  transport, Android presenter and authenticated client-FD handoff. wlroots and
  its non-system dependencies are pinned source builds, not a fork;
- `audio-runtime`: private-process PipeWire/Pulse protocol endpoints and
  Android AAudio playback;
- `launcher-template`: unsigned build-time Activity APK packaged as an asset
  for launcher export, not a separately distributed application.

The opt-in `commandClientFixture` Gradle property adds `run-command-client`,
the external caller fixture from `tests/run-command-client`. It does not become
a production dependency.

Every main-app build compiles the shared native helpers from source: virtual mouse,
PTY transport, one-shot privileged service launcher and identity-checked process
signal helper, and the static guest-file bridge for Linux environments. It also
builds the command client/bridge, Linux settings adapter with pinned libdbus,
and Shroot's runner, supervisor, bootstrap, filesystem service and image tool.
These executables are packaged through `gradle/native-helpers.gradle`; they
are not downloaded at runtime or loaded into ART. The settings adapter does
not package a D-Bus daemon.

The guest bridge runs inside the selected guest, after user selection; a session's
authenticated Unix socket owns its lifetime. It passes read-only regular-file
descriptors, not commands, and never asks for privilege elevation. The X11 module
also builds its server/renderer library. CI verifies
that the main APK contains the required helpers and no `.ko`, and that the Kernel
Fixes APK contains exactly the reviewed module and no main-app native helper.

The APK, main-app helpers and graphical runtimes cover ARM64 only. Linux
and Windows CI both target Android ARM64; package checks reject other native ABIs.
Both helper compiler paths and graphical runtimes target the APK's API 34 minimum.
Wayland dependencies are pinned source builds through the Android NDK on Linux
and the Android toolchain in Termux. Linux CI exports the verified dependency
prefix for Windows CI; Windows consumes it through `magicDeskWaylandRuntime`.
The build does not require an installed Termux runtime on the target device.
See [Wayland build instructions](wayland.md#build).
Compilation does not establish native compatibility. Device coverage is documented in
[Runtime API levels](runtime-api-levels.md).

Host regression support under `app/src/testSupport/java` uses the JDK compiler
to execute selected production method bodies against controlled dependencies.
It is compiled separately against the JDK and added only to the unit-test
classpath, never an APK. These deterministic fixtures complement, but cannot
replace, the Android device self-tests.

`scripts/verify-native.sh` builds and runs Linux host fixtures against the real
native sources. PTY fixtures exercise bidirectional backpressure, partial
frames, metadata and shutdown, including stopped jobs and HUP-ignoring jobs in
separate groups while preserving an independent UNIX session. Virtual-pointer
fixtures replace only device I/O to exercise motion, buttons, scrolling,
protocol validation and write errors.
They use bounded subprocess lifetimes and a temporary directory, without
physical input access. The same script checks process incarnation signaling and
guest-file descriptor exchange, shared graphical key mapping and X11 icon/density wire formats. Linux CI runs
it in addition to Gradle verification. `scripts/tests/test_guest_files.py` also
tests the real helper's authorization and process lifetime; setting
`MAGICDESK_GUEST_FILE_HELPER` to the static binary enables a prepared Ubuntu
PRoot fixture without root.

The kernel module itself is not compiled in normal Android CI. Rebuilding it
requires the exact upstream kernel source, config, symbol versions, and guarded
script documented in [VITURE XR resolution fix](xr-resolution-fix.md).

Push CI builds signed development APKs with a unique version suffix and
publishes the main-app artifact. Pull requests and manual CI runs build unsigned
release variants without signing secrets. Both routes run
`scripts/verify-apks.sh` to enforce the main and Kernel Fixes package boundaries.

For a `v*` tag, the release workflow loads signing credentials through
`gradle/release-signing.gradle`, signs only the main MagicDesk APK, verifies its
certificate and package boundary, emits checksums, and publishes the APK with its
corresponding source archive as the tagged release. Source packaging runs before
native compilation and includes recursively pinned submodules and their build
patches; see [Licensing](licensing.md). Development APKs also carry matching source.
Publish fork commits before a main-repository revision that references them, so
recursive CI checkout and source reproduction can resolve the pinned revision.
The firmware-specific Kernel Fixes APK is not a tagged
release artifact. Local debug builds never require release secrets.
