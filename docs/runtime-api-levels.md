# Runtime API Levels

## Supported Contract

The MagicDesk APK minimum is Android 14 / API 34. Managed Desktop requires
Android 15 / API 35. These are separate contracts within one APK, not separate
builds or platform forks. Android 14 is the selected installation baseline;
device validation on that release is still pending.

| Subsystem | Baseline and prerequisites |
| --- | --- |
| MCP and ordinary built-in UI | API 34; explicit client grants for automation. UI startup does not require Desktop provisioning. |
| Built-in CLI | API 34; an inherited MagicDesk shell channel. Each command retains its own prerequisites; MCP enablement and installed Termux are not required. |
| Script dialogs and notifications | API 34; MCP content grant or inherited CLI channel. Background dialog placement requires the shared privileged launcher; notifications require Android notification permission/channel access. No Desktop or Termux prerequisite. |
| Files, shell commands and transfers | API 34 plus authorized privileged service for shell-backed operations. |
| Termux sessions and viewers | API 34 plus installed Termux, external-command configuration and `RUN_COMMAND` permission. The PTY and its window have separate lifetimes. |
| Embedded X11 | API 34 plus an explicitly selected executor and XKB data. Termux uses its UID and `RUN_COMMAND`; shell uses the authorized command service for clients and MagicDesk's app UID for the X server. Prepared chroot entry requires actual UID 0, not merely a Root setting. The renderer loads only on explicit session startup. Ordinary Android grants handle clipboard/drop content. No Termux:X11 APK, Desktop or HOME prerequisite; Termux remains usable without privileged access. |
| APK replacement | API 34 plus authorized privileged service and the update grant. Android's PackageInstaller and its shell callback own replacement; the update worker survives replacement and reconnect is observed by update ID. |
| Display discovery and interactive tool placement | API 34. Without shell, public DisplayManager inventory and Activity launch options serve accessible displays. Android checks each Intent/destination and secondary-Activity support. No Desktop or HOME prerequisite. |
| Display resource creation and privileged placement | API 34 plus authorized privileged service and working framework capabilities. Trusted virtual displays, background launches, forced fullscreen placement and task transfers retain this boundary. They do not acquire HOME or initialize WMShell Desktop. |
| Display Viewer | API 34 plus authorized privileged service. Owned virtual sources use VirtualDisplay/SurfaceView; existing screens use the framework mirrorDisplay capability and READ_FRAME_BUFFER permission. Shared privileged input adapter. No Desktop, vendor token lookup or root requirement. Virtual-first managed Desktop still requires API 35. |
| Display/task screenshots | API 34 plus authorized privileged service and READ_FRAME_BUFFER. Task capture uses a fresh `takeTaskSnapshot(taskId, false)` request, not Recent cache; hidden tasks may be unavailable. No Desktop, activation or root requirement. API 34 device validation remains pending. |
| Protected virtual display (optional) | Same API-34 display boundary, plus CAPTURE_SECURE_VIDEO_OUTPUT in the current service, protected graphics buffers and a secure Viewer output. No automatic elevation or new prerequisite for ordinary displays. Per-release/device protected playback still needs verification. |
| Ordinary Android Activity automation | API 34 plus authorized privileged service for background/display placement. Intent authorization, content grants and Activity results are independent of Desktop. Dispatch acceptance is verified separately through UI observation. |
| Android UI inspection and actions | API 34 plus authorized privileged service and a free UiAutomation connection. Inspect/wait select a display or task; task ownership uses the hidden AOSP AccessibilityWindowInfo accessor through the framework adapter. Missing ownership remains unknown, without disabling display inspection. No Desktop or root requirement; API 34 device validation is pending. |
| Display input control and task transfer | API 34 plus working privileged framework capabilities. Explicit input control and ordinary fullscreen transfers do not start Desktop. Display switching is shared; Desktop window shortcuts remain API 35+ and require its prepared workspace. |
| Managed Desktop and its self-tests | API 35 plus Desktop provisioning and the required task/window/input APIs. Ordinary tool availability does not imply Desktop availability. |

## Boundary Enforcement

`RuntimeLimits` freezes the user's access ceiling and Termux/Desktop switches at
process startup. These can narrow, never increase, the capabilities below.
App-only startup does not bind or authorize Shizuku/root. Disabling Desktop also
prevents promotion on privileged-service reconnection and rejects direct service
Intents while retaining requested independent services. Termux-only tools remain
available on API 34 with App-only access when Termux integration is enabled.

`RuntimeCapabilities` owns the Desktop SDK floor. Session launch checks it before
display-profile preparation, and runtime launch checks before starting the
service. The shared command executor rejects Desktop requests before resolving or creating a target.
A direct service Intent on an unsupported SDK retains only requested independent
services. Reconnecting the privileged service cannot promote Desktop on that SDK.

Shell binding passes the app's public Settings snapshot without resolving
optional windowing APIs. Their profile is detected once, on first use, in
`FrameworkWindowingCompat`. No additional polling, worker or recurring probe is
involved. Stale-HOME recovery remains an app-startup responsibility; independent
service startup never acquires a new HOME lease.

The control panel disables **Start desktop** on API 34 but retains display
creation, selection and ordinary tools. `get_state.readiness.selfTestReady`
includes the Desktop SDK prerequisite; `selfTestUnavailableReason` distinguishes
an unsupported test from a locked phone. MCP and UI test entry points reject
before session/display preparation. Device readiness and client grants remain
separate from these feature requirements.

Termux-only operation does not need the privileged display catalog. The panel
and Start can select any display exposed to the app. Interactive launches without
shell use ordinary Activity options, including for terminal and X11 windows;
Android can reject destinations such as untrusted outputs. Reopening a live own
terminal or X11 task on its current display uses Android's own-task API.
App-only display addresses are connection-scoped and expire on disconnect or
process restart; public APIs do not expose stable physical identities or transport
types. Unknown metadata and unavailable persistent profiles stay explicit.
Global task queries, moving an existing task, creating our trusted displays,
Viewer capture/input and background automation launches still require the
privileged service. No fallback discards a requested destination or elevates a
failed launch. Privileged placement alongside Desktop retains its ownership checks.

## Release-Specific Behavior

- `FrameworkInputRoutingApi` uses Android 14's unique-ID association methods;
  API 35+ uses their port-specific names. Physical input stays in InputReader.
  The virtual phone mouse additionally requires a working native helper and
  permitted uinput access. These are runtime prerequisites, not vendor checks.
- On API 34, private file drags remain within their source window. API 35 adds
  same-UID cross-window drag. Neither path exposes private payloads through an
  unrestricted global drag. Explicitly shareable content retains read-only URI
  grants on both releases. This follows Android's
  [same-application drag contract](https://developer.android.com/reference/android/view/View#DRAG_FLAG_GLOBAL_SAME_APPLICATION).
- `AndroidPendingIntentOptions` uses the API 34 creator/sender background-start
  setters and chooses explicit API 36 modes only on those releases. Lint does
  not infer the tested SDK branch through its integer parameter and can report
  the two inlined constants. See
  [ActivityOptions](https://developer.android.com/reference/android/app/ActivityOptions).
- File services use Java library APIs available at the API 34 floor, including
  `Path.of` and `Stream.toList`. No older-release backport is required.
- `FrameworkPackageInstallerApi` uses Android's session API and hidden shell
  flags/callbacks. Its separate update process does not remove the requirement
  to verify Binder identity and process restart on each supported release.
- `FrameworkVirtualDisplayApi` uses `DisplayManager.createVirtualDisplay` with
  hidden touch, trusted and own-display-group flags. A display's
  public API availability does not guarantee those privileges or firmware
  behavior. Failures stay local to that operation, not the entire tool runtime.
  Its opt-in `ALWAYS_UNLOCKED` policy and own-display-group requirement already
  exist in AOSP API 34, whose Shell package declares `ADD_ALWAYS_UNLOCKED_DISPLAY`.
  Creation checks the actual service identity and returned flag. This has been
  exercised on RM11/API 36 under UID 2000, not on an API 34 device; OEM process
  liveness and display power remain separate capabilities.

## Verification

### Native Build Boundary

The APK, native helpers and embedded X11 library currently target only
`arm64-v8a`. CI checks the packaged APK for unsupported native ABIs.
Both helper compiler paths in `gradle/native-helpers.gradle` derive their Android
target from the APK's minimum SDK (currently API 34), not the Desktop minimum.
The embedded X11 NDK build also uses its module's API 34 minimum; the Termux X11
build explicitly targets API 34 with the installed toolchain. Compilation does not establish API 34 native
compatibility by itself: that release's shell/Termux/pointer workflows still need
device execution coverage.

The current APK is not an x86_64 emulator build. Linux and Windows build smoke
checks do not run an Android emulator or provide another ABI. An Android Studio
API 34/35/36 test matrix needs matching native artifacts as well as system images.

### Static And Device Checks

Normal `verifyDevelopment` compiles, merges the installable manifest and runs
Lint against the actual API 34 minimum. `RuntimeLayerSdkTest` verifies early
Desktop rejection and independent service retention; file-drag, control-panel
and automation-readiness tests exercise the API 34/35 boundary. These fixtures
do not emulate an Android 14 device or its class loader.

App-only display discovery and interactive Termux/X11 launches were checked on
RM11 / API 36 with a system overlay display: new windows and same-display reuse
worked with privileged startup disabled, no Desktop, no HOME lease and no input
routing acquisition. Display removal/recreation invalidated the connection address;
background MCP placement remained unavailable. This is not API 34 device coverage.

`scripts/audit-android-api.py` can inspect an explicitly requested SDK through
an isolated copy of Lint's Gradle model and merged manifest. Source sets,
dependencies, target SDK and desugaring metadata are retained; the production
manifest and normal Lint partial results are untouched. This diagnostic utility
does not change the supported baseline. Public static analysis does not prove
hidden Binder ABI compatibility, reflective members, dependency/native behavior,
SELinux grants or firmware policy.

The remaining API 34 device matrix is: cold app/MCP startup, privileged-service reconnect,
file operations and transfers, retained shell/Termux and embedded X11 sessions,
X11 input/clipboard/drag-and-drop, CLI commands with
MCP disabled, private/shared
drag boundaries, APK replacement with reconnect, virtual-display creation,
fullscreen tool launch/capture/removal, ordinary task transfer, independent
input acquisition/hotplug/release, and Desktop rejection without HOME or
display-policy changes. Run Desktop regression tests on API 35+ separately.
