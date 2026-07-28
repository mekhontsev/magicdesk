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
- [x] Run Shizuku shell mode on a physical external display.
  Verify existing-task reuse, freeform/fullscreen transitions, display-density
  controls, screenshots, and the absence of root-only input services.
  Existing-task reuse, native freeform launch, landscape correction, native
  caption visibility, fullscreen/freeform transitions, notification-driven
  HOME task focus, and absence of the root watcher were verified on a REDMAGIC
  11 Pro Console display. Display-density controls and a 1920x1080 screenshot
  captured from the Tools panel were also verified.
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

- [x] Reboot with a saved Root profile and verify that MagicDesk, privileged
  helpers, and its foreground service remain stopped until the user explicitly
  launches the application. The application has no boot receiver or boot
  permission, so this behavior is independent of the saved privilege profile.
  A manual launch then started the runtime foreground service as expected.
- [ ] Reboot with each saved display target and verify that no stale display id
  is reused.
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
