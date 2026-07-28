# Deferred Validation Backlog

This checklist records validation that requires hardware, a clean device, or a
future release cycle. Do not treat an item as complete based only on a
successful build.

## Completed Locally

- [x] Install an isolated Basic-mode build beside the signed production app.
  Verify first-run setup, delayed `BOOT_COMPLETED`, diagnostics, Primary and
  Current targets, phone/desktop layout changes across rotation, and the
  absence of a child `su` process or foreground watcher service. Verify that a
  162-app catalog retains one bounded icon bitmap per app after startup cleanup
  and is not reloaded on `onResume`.

## External Display

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
- [ ] Run Shizuku shell mode on a physical external display.
  Verify existing-task reuse, freeform/fullscreen transitions, display-density
  controls, screenshots, and the absence of root-only input services.
- [ ] Exercise the Primary, Current, External, and Auto targets with display
  connection and disconnection between launches.
  Auto was verified not to target REDMAGIC's physical presentation display in
  Mirror Mode, where the firmware leaves the pointer on the phone. Primary,
  Current, and active-Console transitions were also verified without duplicate
  shell tasks. Explicit External presentation and disconnect/relaunch cases
  remain.

## Lifecycle

- [ ] Reboot with each saved privilege profile and verify that BootReceiver
  starts the root watcher only for an acknowledged Root/Auto-root session.
- [ ] Reboot with each saved display target and verify that no stale display id
  is reused.
- [ ] Switch Root to Basic and Root to Shizuku repeatedly while MagicDesk is
  active and confirm that no foreground-service startup race returns.
  One Root -> Basic -> Root cycle on the external display is complete: Basic
  stopped the foreground watcher and both root helpers without removing the
  desktop HOME task, and confirming Root restarted all three without relaunching
  MagicDesk. Shizuku and repeated-cycle coverage remain.

## Clean Environment

- [ ] Test Basic mode on a ZTE/nubia Android 16 device whose desktop flags have
  not previously been provisioned with root.
- [ ] Test Shizuku shell mode on a device without working `su`.
- [ ] Verify first-run permission denial and later recovery for overlay,
  notifications, and Shizuku authorization.

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
