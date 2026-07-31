# Deferred Validation Backlog

This checklist records validation that requires hardware, a clean device, or a
future release cycle. Do not treat an item as complete based only on a
successful build.

## Completed Locally

- [x] Run the debug-only Nubia vendor probe as MagicDesk's real
  `u:r:untrusted_app:s0` UID. Confirm read access to mirror state,
  `scenedecision`, and `zte_backlight`; confirm the exported no-permission
  Mirror Input service; confirm `Settings.Global` remains denied; and verify a
  temporary allowlisted REDMAGIC property write restores its original value.
- [x] Register Nubia's `scenedecision` task callback from the untrusted app UID.
  It delivered package, activity, stack, display, process, and window-mode
  fields. It also retained stale entries for removed external displays, so it
  is documented as a filtered Basic-mode hint rather than an authoritative
  task source.
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

- [ ] On a clean external-display session, test Basic app-UID Console
  activation through the vendor DisplayManager command, then exit through the
  same path. Verify physical disconnect and process death restore normal
  projection state.
- [ ] With a known Nubia wired-privacy preference, verify whether app-UID
  SurfaceFlinger option 1102 can be owned and restored without Root. Do not
  automate this until the pre-test value can be determined reliably.
- [ ] Compare the Basic `scenedecision` callback against live tasks across
  launch, move, minimize, display removal, and reconnect. Confirm filtering
  removed display ids is sufficient before using it in the Basic taskbar.
- [ ] Test whether a live Nubia input-panel token changes physical-key routing
  on the external display. Source inspection indicates it suppresses
  reinjection during text input but does not replace input-port association.
- [x] Validate the DeX-style task controls with overlapping windows and move
  one task external -> phone -> external without Activity recreation. Golly
  moved from Console display 5 to display 0 and back as task 1832 with the same
  ActivityRecord, process, and freeform bounds.
- [ ] Validate the Tools audio section with phone, HDMI, USB, and Bluetooth
  output where available.
- [ ] Validate REDMAGIC monitoring values against sysfs and test every direct
  Root profile, including **System**, **Exit MagicDesk**, and interrupted
  process recovery.
- [x] Test the stock Shizuku cooling policy on hardware. Intelligent/extreme
  fan and low/mid/fast pump requests were stable. **System** restored an absent
  pump-flow value and its original `main=100`; forced process death retained
  the fan baseline and the next manual MagicDesk start restored `-100/0` and
  cleared ownership. UID 2000 cannot read fan RPM, so the Shizuku UI reports
  the effective on/off state instead of a synthetic value.

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
  controls, screenshots, and the absence of root-only input services. This
  baseline preceded lower-privilege desktop-property provisioning: after a
  clean reboot, the two global windowing settings were `1` while both
  persistent desktop properties remained `true`.
  The UID-2000 Shizuku backend activated Console Mode, launched Touch Panel,
  corrected the display to 1920x1080 at 160 DPI, reused fullscreen Chrome as
  freeform, cold launched Gmail as freeform, and promoted Gmail to true
  fullscreen. The bidirectional task watcher remained alive for focus
  commands. Native WMShell captions were correctly absent; no Root helper was
  used.
- [x] Verify the allowlisted Shizuku WMShell provisioning end to end on an
  external display. A real UID-2000 service applied `1`, `1`, `false`, and
  `false`, owned only the rounded-corner property it changed, and cleared the
  pending boot marker after reboot. Golly received a native WMShell caption;
  restoring SurfaceFlinger option `1102` hid its drawing without breaking hit
  testing, and restarting the MagicDesk runtime restored the caption without
  recreating the task. **Restore previous values** returned only the owned
  property to `true`, survived reboot, and provisioning restored it to `false`
  after a second reboot. With the WMShell capability probe deliberately
  rejected in a temporary build, the direct task path registered fullscreen
  Golly in `DesktopRepository` and produced a native caption. Follow-up testing
  found that `applySyncTransaction()` could leave the task leash at the origin
  on the rotated Console surface. The final `startNewTransition()` path restored
  Golly from fullscreen directly to its exact `360,142-1560,874` bounds with a
  correctly positioned native caption and no intermediate 75-percent window.
  The temporary probe override was removed after the test.
- [x] Probe native freeform cold launch from Basic mode. A debug-only
  instrumentation force-stopped Golly, launched a genuinely new task from
  MagicDesk UID 10615 with `FLAG_ACTIVITY_MULTIPLE_TASK`, explicit bounds, and
  `android.activity.windowingMode=5`, then inspected ActivityTaskManager. The
  task was normalized to fullscreen and the rectangle survived only as
  `mLastNonFullscreenBounds`. Source audit also confirmed that WMShell gives
  `IDesktopMode` only to the STATUS_BAR_SERVICE-protected Quickstep service and
  enforces `MANAGE_ACTIVITY_TASKS` on every desktop command. The temporary
  launch probe was removed after the test.
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
  access fan/pump nodes. Cooling control therefore uses the stock `NBFan`
  settings policy instead of direct hardware writes.
- [x] Run the safe phone-screen restore path through the real Shizuku
  UserService. `openScreenOffTP(false)` completed as UID 2000 in
  `u:r:shell:s0`; direct Binder inspection confirmed that the REDMAGIC service
  delegates the transition inside `system_server` without a caller permission
  check.
- [x] Read the current system wallpaper through a Shizuku UserService file
  descriptor. The desktop loaded it without Root and picked up a wallpaper
  changed after MagicDesk had already started.
- [x] Lock the phone through WindowManager from shell UID 2000. The same
  `DeviceLockCommand` used by `Win+L` returned `device-locked`, and the phone
  entered its normal lock screen.
- [x] Validate the integrated physical-display Shizuku wake guard with an
  external display attached. The completed 2026-08-01 test kept display 0 at
  committed `OFF` while Termux text input, `Ctrl+Space`, key repeat, right
  click, and the external desktop remained active. MagicDesk stayed unfrozen
  through REDMAGIC `cfreezer`'s transient service-working state without a
  persistent whitelist entry. The physical power button plus unlock, explicit
  Wake action, switch to screen mirroring, forced guard termination, package
  force-stop, raw Shizuku UserService `SIGKILL`, and physical display-cable
  removal all restored display 0 to `ON`.
  Package force-stop initially exposed an ordering bug: UserService closed
  helper stdin and immediately terminated the process before it could restore
  display power. Heartbeat-owned streams now receive a bounded graceful EOF
  phase before termination. The repeated force-stop restored display 0 during
  its first one-second sample; raw UserService death also restored display 0,
  rebound a new UserService, and recreated keyboard and mouse bridges without
  restarting the APK.
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
  previous layout. The bridge remained heartbeat-bound and fail-open. The
  mirror-mode read-only watcher also handles `Win+D` before the full-shortcut
  gate, allowing External Desktop to start without first opening MagicDesk on
  the phone; this was verified after switching back to mirroring.
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
  persistent properties restored, but this device still used local ADB started
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
