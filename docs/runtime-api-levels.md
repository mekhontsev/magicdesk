# Runtime API Levels

## Supported Contract

The MagicDesk APK minimum is Android 14 / API 34. Managed Desktop requires
Android 15 / API 35. These are separate contracts within one APK, not separate
builds or platform forks. Android 14 is the selected installation baseline;
device validation on that release is still pending.

| Subsystem | Baseline and prerequisites |
| --- | --- |
| MCP and ordinary built-in UI | API 34; explicit client grants for automation. UI startup does not require Desktop provisioning. |
| Files, shell commands and transfers | API 34 plus authorized Shizuku for shell-backed operations. |
| Termux sessions and viewers | API 34 plus installed Termux, external-command configuration and `RUN_COMMAND` permission. The PTY and its window have separate lifetimes. |
| APK replacement | API 34 plus authorized Shizuku and the update grant. Android's PackageInstaller and its shell callback own replacement; the update worker survives replacement and reconnect is observed by update ID. |
| Display resources and ordinary tool placement | API 34 plus authorized Shizuku and working framework capabilities. Creating a display or placing a fullscreen tool there does not acquire HOME or initialize WMShell Desktop. |
| Managed Desktop and its self-tests | API 35 plus Desktop provisioning and the required task/window/input APIs. Ordinary tool availability does not imply Desktop availability. |

## Boundary Enforcement

`RuntimeCapabilities` owns the Desktop SDK floor. Session launch checks it before
display-profile preparation, and runtime launch checks before starting the
service. MCP rejects Desktop requests before resolving or creating a target.
A direct service Intent on an unsupported SDK retains only requested independent
services. Reconnecting Shizuku cannot promote Desktop on that SDK.

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

## Release-Specific Behavior

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
  hidden touch, trusted, decoration and own-display-group flags. A display's
  public API availability does not guarantee those privileges or firmware
  behavior. Failures stay local to that operation, not the entire tool runtime.

## Verification

Normal `verifyDevelopment` compiles, merges the installable manifest and runs
Lint against the actual API 34 minimum. `RuntimeLayerSdkTest` verifies early
Desktop rejection and independent service retention; file-drag, control-panel
and automation-readiness tests exercise the API 34/35 boundary. These fixtures
do not emulate an Android 14 device or its class loader.

`scripts/audit-android-api.py` can inspect an explicitly requested SDK through
an isolated copy of Lint's Gradle model and merged manifest. Source sets,
dependencies, target SDK and desugaring metadata are retained; the production
manifest and normal Lint partial results are untouched. This diagnostic utility
does not change the supported baseline. Public static analysis does not prove
hidden Binder ABI compatibility, reflective members, dependency/native behavior,
SELinux grants or firmware policy.

The remaining API 34 device matrix is: cold app/MCP startup, Shizuku reconnect,
file operations and transfers, retained shell/Termux sessions, private/shared
drag boundaries, APK replacement with reconnect, virtual-display creation,
fullscreen tool launch/capture/removal, and Desktop rejection without HOME or
display-policy changes. Run Desktop regression tests on API 35+ separately.
