# Deferred Validation Backlog

This checklist records validation that requires hardware, a clean device, or a
future release cycle. Do not treat an item as complete based only on a
successful build.

## Completed Locally

- [x] Install an isolated Basic-mode build beside the signed production app.
  Verify first-run setup, diagnostics, Primary and Current targets,
  phone/desktop layout changes across rotation, and the absence of a child `su`
  process or foreground watcher service. Verify that a 162-app catalog retains
  one bounded icon bitmap per app after startup cleanup and is not reloaded on
  `onResume`.
- [x] Verify the compact taskbar and scrollable Tools layout at phone density.
  Read-only REDMAGIC monitoring matched the fan/pump nodes and did not create
  a hardware ownership baseline or alter any node value.

## External Display

- [x] Validate the DeX-style task controls with overlapping windows and move
  one task external -> phone -> external without Activity recreation. Golly
  moved from Console display 5 to display 0 and back as task 1832 with the same
  ActivityRecord, process, and freeform bounds.
- [ ] Validate the Tools audio section with phone, HDMI, USB, and Bluetooth
  output where available.
- [ ] Validate REDMAGIC monitoring values against sysfs, then test each fan and
  pump profile. Confirm **System**, **Exit MagicDesk**, and a simulated process
  interruption restore the exact pre-control baseline.

- [x] Run the post-refactor Root Console regression pass. Verify Console Mode
  startup, Start and Tools overlays, windowed/fullscreen transitions, taskbar
  focus, `Alt+Tab`, `Ctrl+Space`, key repeat, right click in browsers, touchpad
  recovery, mirror transition, and complete exit after the July 2026
  controller and window-transition splits. The final cold-start pass also
  covered Mirror Mode with only Android Home visible: a temporary opaque seed
  let Nubia accept the transition, the saved EDID profile was applied before
  desktop launch, and one fullscreen HOME task appeared without a DPI-driven
  Activity recreation. Repeated activation reused that task and left no seed
  task behind.
- [x] Validate the controller refactor on the current device before pushing.
  Exercise phone layout, Console Mode startup, Start and Tools panels, taskbar
  task actions, floating/fullscreen transitions, hardware layout switching,
  right click, touchpad restore, and complete MagicDesk exit.
  The external-display pass also covered ordinary Tab, `Ctrl+Tab`, `Alt+Tab`,
  `Alt+Shift+Tab`, repeated task advancement while Alt remained held, launcher
  process stability, and restoration of the keyboard/mouse keymaps and input
  port association during Exit. A deliberately launched `standard/freeform`
  MagicDesk task was also recovered by `Win+D` into a new fullscreen HOME task
  without relaunching the existing Termux or Golly processes.
- [x] Run Basic mode on a physical external display.
  Verify desktop launch, public application launch, overlays, taskbar, and
  graceful handling of unavailable task and DPI controls.
  On the REDMAGIC 11 Pro, desktop persistence, public cold launch, Start search
  focus, and immediate shutdown of all root helpers were verified. A public
  cross-display launch of an already-running Termux process caused the vendor
  framework to force-stop it; this confirmed and documented the Basic-mode
  task-identity limitation. The final UI pass verified that privileged DPI,
  Console Mode, phone-screen, and shortcut controls remain present but are
  disabled and visually distinct.
- [x] Run Shizuku shell mode on a physical external display.
  Verify existing-task reuse, freeform/fullscreen transitions, display-density
  controls, screenshots, and the absence of root-only input services. After a
  clean reboot, the two global windowing settings were `1` while both
  Root-only persistent desktop properties remained `true`. The UID-2000
  Shizuku backend activated Console Mode, launched Touch Panel, corrected the
  display to 1920x1080 at 160 DPI, reused fullscreen Chrome as freeform, cold
  launched Gmail as freeform, and promoted Gmail to true fullscreen. The
  bidirectional task watcher remained alive for focus commands. Native WMShell
  captions were correctly absent; no Root helper was used.
- [x] Restore physical right click in Shizuku shell mode.
  A ProtoArc external mouse was grabbed read-only as UID 2000 and forwarded
  unchanged through a `BUS_VIRTUAL` `/dev/uinput` pointer. Movement remained
  routed to the Console display, Nubia no longer emitted Back, and native
  context menus opened in MagicDesk and Chrome. Forced APK shutdown removed
  the helper and virtual device within the six-second heartbeat window.
- [x] Probe a real ADB-style Shizuku UserService (`uid=2000`,
  `u:r:shell:s0`, no capabilities). On the verified firmware it can read raw
  input, acquire `EVIOCGRAB` on a read-only cursor descriptor, create a
  `/dev/uinput` pointer, inject events, write physical-keyboard layouts,
  inspect/listen to tasks, and use display commands. It cannot open raw input
  for writing, acquire `MONITOR_INPUT`, read the private InputManager XML, or
  access fan/pump nodes.
- [x] Run the safe phone-screen restore path through the real Shizuku
  UserService. `openScreenOffTP(false)` completed as UID 2000 in
  `u:r:shell:s0`; direct Binder inspection confirmed that the REDMAGIC service
  delegates the transition inside `system_server` without a caller permission
  check.
- [ ] Verify Shizuku phone-screen dimming with an external display attached,
  including the documented limitation that Nubia's text-input panel may wake
  the phone. Android 16 rejects component-state changes for
  `MirrorInputService` specifically from UID 2000, so the Root-mode anti-wake
  guard is intentionally not retried in Shizuku mode.
- [x] Cycle physical-keyboard layouts through the Shizuku shell backend.
  Binder-only discovery reproduced Android's all-enabled-IME mapping and found
  the configured English and Russian layouts while Unexpected Keyboard was
  active. Both direct Binder execution and the lifecycle-bound Shizuku
  `Ctrl+Space` watcher applied EN -> RU -> EN to the connected ProtoArc
  keyboard without reading private InputManager state.
- [x] Provide global Console shortcuts through the Shizuku shell backend.
  A source-identity `/dev/uinput` keyboard associated with the Console display
  preserved ordinary typing and repeat, consumed MagicDesk shortcuts, and
  switched EN -> RU -> EN without leaking the first post-switch key through the
  previous layout. The bridge remained heartbeat-bound and fail-open.
- [x] Exercise the Primary, Current, External, and Auto targets with display
  connection and disconnection between launches.
  Auto was verified not to target REDMAGIC's physical presentation display in
  Mirror Mode, where the firmware leaves the pointer on the phone. Primary,
  Current, and active-Console transitions were verified without duplicate
  shell tasks. Explicit External selected the physical presentation display in
  Mirror Mode and the virtual Console display while Console Mode was active.
  Physical disconnect restored the phone launcher, and reconnect selected new
  physical and Console display IDs without reusing stale IDs.

## Lifecycle

- [x] With an external display attached, verify the split Activity model:
  the launcher opens `ControlActivity` on display 0, **Start external desktop**
  opens `DesktopActivity` externally, both tasks coexist, and **Phone control
  panel** from desktop Tools returns to the phone control without moving either
  task. On the REDMAGIC 11 Pro, the ordinary cross-display
  `Activity.startActivity()` path removed the external HOME task when Nubia
  Touch Panel was on top. An explicit privileged launch on display 0 now
  performs the transition while preserving control task 1847 and desktop task
  1848 on display 5.
- [x] On display 0 without external hardware, verify **Open desktop here** and
  Android Back return to the control panel. The local desktop stays in the
  control task so Nubia Recents exposes one MagicDesk card; a cross-display
  desktop retains a separate task. No crash or ANR was logged.
- [x] On display 0, verify the unified viewport and taskbar lifecycle: the
  desktop remains below Android system bars, freeform applications keep the
  taskbar visible, fullscreen applications hide it, and returning to the
  desktop restores it without an Activity-lifecycle workaround. Also enter
  Android's native desktop mode first and verify that **Open desktop here**
  normalizes the MagicDesk host task from inherited freeform bounds to
  fullscreen. Verified with Chrome in freeform, Golly in fullscreen, and a
  same-task Back transition to the phone control panel.
- [ ] On display 0, leave two applications in freeform mode, return from the
  local desktop with Android Back, and verify that both tasks become fullscreen
  before opening HOME or Recents. Repeat with **Exit MagicDesk**, confirm that
  Nubia Quickstep does not crash in `DesktopTaskView`, and verify that launcher
  shortcuts remain unchanged.

- [x] Reboot with a saved Root profile and verify that MagicDesk, privileged
  helpers, and its foreground service remain stopped until the user explicitly
  launches the application. The application has no boot receiver or boot
  permission, so this behavior is independent of the saved privilege profile.
  A manual launch then started the runtime foreground service as expected.
- [x] Reboot with a saved Current target and verify that no stale display id is
  reused. The previous Console display `19` disappeared, the reconnected
  physical display was assigned `2`, and launching Device Setup on `2` kept
  `DesktopActivity` on that current display. Display targets persist only their
  selection policy; runtime display ids are always resolved again.
- [x] Switch Root to Basic and Root to Shizuku repeatedly while MagicDesk is
  active and confirm that no foreground-service startup race returns.
  One Root -> Basic -> Root cycle on the external display is complete: Basic
  stopped the foreground watcher and both root helpers without removing the
  desktop HOME task, and returning to Root restarted all three without
  relaunching MagicDesk. A Root -> Shizuku -> Root -> Shizuku cycle retained
  one runtime service and one desktop task while stopping and restarting the
  root input bridge exactly once per transition.

## Clean Environment

- [ ] Test Basic mode on a ZTE/nubia Android 16 device whose desktop flags have
  not previously been provisioned with root.
- [ ] Test Shizuku onboarding on a device without working `su`. Runtime
  behavior has been exercised as the real UID-2000 shell service with
  Root-only properties restored, but this device still used local ADB started
  through Root to bootstrap Shizuku.
- [x] Verify permission denial and later recovery for overlays, notifications,
  and Shizuku authorization. Overlay denial left the manual setup available
  with a clear limited-mode explanation. Denying `POST_NOTIFICATIONS` did not
  stop the desktop runtime, and granting it later restored the foreground
  notification on the next activity resume without restarting the service.
  Notification-listener denial was reported by diagnostics and granting access
  produced a live listener binding. Shizuku denial kept the strict Shizuku
  profile while stopping the runtime service and all root helpers; granting it
  later started the Shizuku UserService and runtime without a Root fallback.

## Automated Coverage

- [x] Add unit tests for SessionProfile parsing, persistence, and launch
  overrides.
- [x] Add unit tests for the RuntimeAccess capability matrix.
- [x] Add tests for strict backend selection: explicit Basic and Shizuku must
  never fall back to Root.

## Next Release

- [ ] Choose the next version and increment both `versionCode` and
  `versionName`; GitHub already contains `v1.0`.
- [ ] Push the completed privilege/display work only after explicit maintainer
  approval.
- [ ] Build the signed APK in GitHub Actions and install that exact artifact on
  the device before publishing the release.
