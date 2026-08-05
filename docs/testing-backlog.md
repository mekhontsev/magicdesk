# Validation Backlog

This file records hardware validation and release work that cannot be inferred
from a successful build. Completed entries preserve non-obvious device results;
retired prototypes are listed separately and are not current product modes.

## Verified Platform

- REDMAGIC 11 Pro (`NX809J`)
- Android 16 / API 36
- Firmware build `20260204.221845`
- Shizuku UserService running as `uid=2000`, `u:r:shell:s0`

## Completed: Setup And Runtime

- [x] Verify strict Shizuku admission. A stopped server, denied permission,
  UserService failure, UID 0, or any UID other than 2000 leaves runtime access
  unavailable and never invokes `su` or an app-UID fallback.
- [x] Provision both global windowing settings through shell UID 2000 and the
  two allowlisted persistent properties through `redmagic.app.manager`. Verify
  read-back and the reboot marker.
- [ ] After clearing MagicDesk app data, verify **Restore defaults** removes the
  desktop settings/properties, resets display 0 overrides, recovers stale phone
  desktop tasks, and requests one reboot.
- [x] Reboot with a saved profile and verify MagicDesk remains stopped. The app
  has no boot receiver or boot permission; manual launch starts one runtime
  service only.
- [x] Deny and later grant Shizuku access. Runtime remains stopped while denied
  and starts after a successful UID-2000 audit without a fallback path.
- [x] Verify Device Setup can complete after a clean reboot without flashing
  setup UI when every audit item already passes.
- [x] Exercise phone control, **Open desktop here**, and external desktop from
  the same build. Local desktop uses the shared desktop implementation, respects
  phone system bars, and returns to one control-panel Recents card.

## Completed: Shizuku Capability Boundary

- [x] Run the non-destructive capability probe from a real ADB-style Shizuku
  UserService. Shell can inspect/listen to tasks, use WindowOrganizer, read raw
  input, acquire `EVIOCGRAB` on read-only cursor descriptors, create
  `/dev/uinput` devices, write keyboard layouts, use display commands, and read
  the system wallpaper. It cannot write raw input devices, acquire
  `MONITOR_INPUT`, read InputManager private XML, or access fan/pump sysfs nodes.
- [x] Verify the main APK contains no `su` path, root helper, kernel module,
  kernel loader, or Kernel Fixes integration. Verify the standalone add-on
  contains exactly the reviewed `.ko` and no input helper.
- [x] Read the current static wallpaper through a UserService file descriptor.
  The desktop also picked up a wallpaper changed after MagicDesk started.
- [x] Lock the device through WindowManager from shell UID 2000.
- [x] Capture the active display to `Pictures/Screenshots` while excluding
  transient MagicDesk panels based on actual detach/frame completion rather
  than a fixed timeout.

## Completed: External Desktop And Windows

- [x] Activate REDMAGIC desktop mode from Mirror Mode, including the Home-only
    state that needs a transient seed. Apply geometry/DPI before desktop launch,
  create one display-sized desktop host, and remove the seed.
- [x] Resolve changing physical and virtual display IDs after disconnect,
  reconnect, and reboot. No numeric display ID is persisted.
- [x] Verify EDID-specific profiles and recommended density, including
  1920x1080 at 160 DPI.
- [x] Reuse an exact running task across phone -> external -> phone without
  Activity recreation. Golly retained one task/process and its freeform bounds.
- [x] Verify overlapping windows, click-to-front, taskbar focus, Show Desktop,
  task restoration, snap, maximize, minimize, true fullscreen, and exact close.
- [x] Verify native WMShell captions after one-time provisioning. Temporarily
  reveal caption layers through SurfaceFlinger option `1102`, then restore
  Nubia's latest wired-privacy value on mirror, exit, and interrupted-session
  recovery.
- [x] Validate client-preserving freeform/fullscreen transitions by
  synchronously clearing the exact stale task-local caption source. No density
  pulse, display-0 trampoline, Activity recreation, or task restart is used.
- [x] Enter and leave application-requested immersive fullscreen from YouTube
  in Firefox, restoring the previous freeform geometry.
- [x] Handle Android chooser/dialog tasks without permanently demoting a
  maximized or snapped owner and without losing the taskbar.
- [x] Keep the external taskbar above freeform tasks and hide it for unrelated
  true-fullscreen tasks. Restore it after unlock, chooser dismissal, and task
  focus changes.
- [x] Normalize a MagicDesk host into one force-translucent fullscreen standard
  task without a visible native caption. Verify that tasks minimized below it
  remain resumed while the desktop stays visually opaque.

## Completed: Physical Input

- [x] Forward a physical keyboard through a virtual external keyboard while
  preserving scan codes, modifiers, repeat, ordinary Tab/Ctrl+Tab, and dynamic
  device hot-plug.
- [x] Verify global `Win+D`, window arrows, `Alt+Tab`, `Alt+Shift+Tab`,
  `Alt+F4`, `Win+Backspace`, `Win+L`, `Win+N`, and screenshot shortcuts.
- [x] Cycle English -> Russian -> English through both `Ctrl+Space` and the
  taskbar using Android's enabled IME subtype order. Confirm switching remains
  independent of a project-specific IME.
- [x] Grab external cursor sources read-only and forward them through one
  virtual mouse. Right click reaches MagicDesk, Chrome, and Firefox instead of
  becoming Android Back; movement, wheel, left click, and multiple devices
  remain intact.
- [x] Force-stop the APK and kill the UserService. Binder ownership or pipe EOF
  releases input grabs and removes virtual devices; rebinding recreates both
  bridges.
- [x] Connect, use, and disconnect a sleeping secondary keyboard while an old
  SDL game remains focused. Confirm stable virtual device IDs, complete key
  release state, native repeat, and no Activity relaunch or process crash.

## Completed: Phone Display And Touch Panel

- [x] Keep display 0 committed OFF while external Termux receives text input,
  layout switching, key repeat, and right click.
- [x] Restore display 0 through physical power/unlock, explicit Wake, mirror
  transition, helper termination, package force-stop, UserService death, and
  physical cable removal.
- [x] Protect the desktop process through REDMAGIC `cfreezer`'s transient
  service-working API while display 0 is off. Verify no persistent whitelist
  entry remains.
- [x] Launch and reopen REDMAGIC Touch Panel from the phone notification and
  correct its pointer viewport after display geometry changes.

## Completed: Hardware And Notifications

- [x] Verify stock NBFan intelligent/extreme fan policy and low/mid/fast pump
  requests. Restore an absent or automatic sentinel through the required
  off-before-restore transition and recover interrupted ownership on next start.
- [x] Verify bypass charging through the stock setting and vendor service,
  including disconnect handling.
- [x] Confirm shell UID 2000 cannot read reliable fan RPM. The UI reports only
  states supported by the vendor policy instead of synthesizing a value.
- [x] Grant, deny, rebind, and persist notification-listener access. Public
  requestUnbind/requestRebind recovers the listener without Shizuku; diagnostics
  reports `[NOTIFICATIONS-005]` when firmware refuses to reconnect it.
- [x] Open, act on, and dismiss notifications from the desktop. Verify transient
  notification popups and state restoration after process restart.

## Historical Experiments: Retired Paths

The following experiments informed the current Shizuku-only design. They are
not supported runtime modes:

- An ordinary app-UID build could render MagicDesk UI and publicly launch
  applications, but Android normalized freeform launches to fullscreen and it
  could not reuse exact tasks, own input routing, or correct display geometry.
- Public cross-display launch of an already-running Termux task caused REDMAGIC
  to force-stop the process. Exact shell task control is required for stateful
  applications.
- `scenedecision` callbacks exposed task-like data but retained stale entries
  for removed displays. They are hints, not an authoritative task model.
- Direct root prototypes provided no essential desktop capability after the
  complete Shizuku input, task, setup, display, and hardware paths were proven.
  Keeping a second backend added security surface and duplicated lifecycle code.
- Accessibility key interception worked only on selected focus paths and could
  not replace the display-associated keyboard bridge.
- Custom captions looked correct at rest but lagged task leashes and had wrong
  cross-window Z-order. Native WMShell captions are the only production path.

## Pending Hardware Validation

- [ ] Test first-run Shizuku onboarding on a compatible device that has never
  had root and whose desktop properties retain stock values.
- [ ] Test another REDMAGIC/ZTE Android 16 firmware and record every changed
  Binder service, component, setting, and diagnostic code.
- [ ] Validate Tools audio output across HDMI, USB, Bluetooth, and phone speaker.
- [ ] On display 0, leave multiple freeform tasks open and verify the system
  Home and Recents gestures are unavailable while MagicDesk task switching
  still works. Exit the local desktop and verify both gestures return only
  after task cleanup, including after terminating the Shizuku UserService.
- [x] Verify phone-desktop recovery shares the task-command queue, revives a
  missing Recent task before transitioning it, and cancels before mutation
  when a newer local desktop supersedes cleanup.
- [ ] Validate VITURE Beast's 1200-line 3D EDID transition with the independent
  Kernel Fixes APK.
- [ ] Repeat display-off failure tests after a REDMAGIC OTA because both
  DisplayManager shell commands and `cfreezer` are vendor implementation details.

## Automated Coverage

- [x] Unit tests cover session-profile parsing, strict Shizuku readiness,
  display-density policy, parsers, and controller policies.
- [x] Task observation and exact-stack focus use one Binder-owned observer
  inside the existing shell UserService; no separate task-watcher process or
  textual event protocol remains.
- [x] Android lint, debug assembly, unit tests, APK boundary verification, and
  certificate checks run in the release workflow.
- [ ] Add an instrumentation regression that opens one freeform task, performs
  fullscreen/restore through the shell task observer, and verifies the same
  task ID and Activity instance when CI device coverage becomes available.

## Release 1.2

- [x] Set the MagicDesk release to `1.2` (`versionCode` 120).
- [x] Inspect the exact signed CI artifact before publishing.
- [x] Push only after explicit maintainer approval.
