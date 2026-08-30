# Validation Matrix

This file records current device coverage and validation that still requires
specific hardware or user interaction. Build success alone does not prove
firmware task, display, input, or capture behavior.

## Maintainer Platform

- RedMagic 11 Pro (`NX809J`)
- Android 16 / API 36
- Firmware build `20260204.221845`
- Authorized shell UserService running as `uid=2000`, `u:r:shell:s0`
- Phone, simulated, HDMI, Miracast, physical keyboard/mouse/touchpad, phone
  touchpad, recording, and launcher cleanup are available for direct testing

## Community Platforms

### RedMagic 11 Pro (`NX809J-UN`)

- Android 16 / API 36, firmware build `20260625.022314`
- Diagnostics and user validation cover desktop startup, external sizing,
  launcher recovery, Mora discovery, output modes, and display recording.

### nubia Z80 Ultra (`NX741J`, `PQ85A01-UN`)

- Android 16 / API 36, MyOS build `MyOS16.0.16_NX741J_NEEA`, firmware
  `20251229.234747`
- Diagnostics and user validation cover wired desktop operation, multiple
  freeform windows, `2560x1080@75` output and wide external sizing through the
  physical display, focus transfer, keyboard input, phone-screen-off behavior,
  launcher protection, and simulated self-test cleanup.
- Shell UID 2000 cannot read `/sys/kernel/lcd_enhance/edid_modes`; root can.
  This no longer blocks the confirmed wide mode: Android reports the active
  physical-display timing and MagicDesk retains it without the legacy mirror
  display path.
- Fan and pump nodes expected on RedMagic gaming phones are absent.

## Automated Coverage

- JVM tests cover state models, parsers, lifecycle ownership, task and display
  policies, shell quoting, filesystem operations, and platform isolation.
- Android lint and debug assembly cover the main application and independent
  Display Fixes and Kernel Fixes APKs. Package-boundary checks reject input
  helpers or kernel artifacts in the wrong APK.
- CI builds on Linux and Windows. Non-documentation pushes to `main` also build,
  sign, verify, and publish the rolling development APK.
- The desktop self-test runs the production session and task paths on phone,
  simulated, wired, or wireless targets. It verifies initial window mode,
  native caption and resize geometry, focus with injected text, snap,
  fullscreen restore, true-fullscreen Alt+Tab, display removal, phone-task
  isolation, window-launch wallpaper continuity, and owned cleanup.
- A task-stack invariant guard rejects visible intermediate display or
  windowing-mode detours, freeform tasks on display 0, and wallpaper-only gaps.
  It records snapshots only during an explicit self-test.
- Debug lifecycle instrumentation exposes the same simulated-display test to a
  host with ADB. Physical-display runs remain manual.

## Pending Functional Validation

- [ ] After clearing application data, verify **Restore defaults** removes
  desktop settings and properties, resets display 0 overrides, normalizes
  phone tasks, and requests one reboot.
- [ ] Verify file and recursive-folder move between Files and Desktop, drop
  onto a Files folder, and `Ctrl`-drag copy in both directions.
- [ ] Record with **Microphone** and verify synchronized audio in the final MP4.
- [ ] Record with **No audio** and verify a playable video with no temporary
  audio or mux files.
- [ ] Under the Standard Android platform, verify video-only recording and
  cleanup on firmware without the Nubia internal-audio source.
- [ ] On Nubia firmware without source `80`, verify diagnostics reports it as
  unavailable and recording remains video-only without constructing the
  vendor `MediaRecorder` path.

## Pending Hardware Validation

- [ ] Verify proportional file, shortcut, and widget placement across two
  differently sized external desktops.
- [ ] Test first-run onboarding on a compatible device that has never used
  root and retains stock desktop properties.
- [ ] Run onboarding, Diagnostics, phone/simulated/HDMI/Miracast self-tests,
  capture, input, and launcher cleanup on Android 15 hardware.
- [ ] Validate the Standard Android platform on non-ZTE hardware with native
  secondary-display freeform support.
- [ ] Validate system audio routing across HDMI, USB, Bluetooth, and the phone
  speaker.
- [ ] On display 0, verify Home and Recents remain unavailable while local
  freeform tasks are active and return after cleanup and UserService death.
- [ ] Validate VITURE Beast's 1200-line 3D EDID transition with the independent
  Kernel Fixes APK.
- [ ] Repeat phone-screen-off and freezer recovery after a RedMagic OTA.
