# Validation Matrix

This is the current coverage and remaining validation plan, not a log of test
runs. Build success does not prove firmware behavior. Exact run IDs, results,
fingerprints and reproduction details belong in compatibility reports.

## Device Coverage

| Device | Runtime | Exercised scope |
| --- | --- | --- |
| RedMagic 11 Pro NX809J EEA | Android 16 / API 36, build `20260204.221845` | Direct phone/simulated/HDMI/Miracast testing, physical and phone input, recording, HOME lifecycle, task cleanup |
| OnePlus 5 | LineageOS 22.2 / Android 15 / API 35 | Direct phone/simulated and Miracast testing on the Standard Android provider, physical mouse/keyboard, phone HOME cleanup, remote MCP |
| RedMagic 11 Pro NX809J-UN | Android 16, build `20260625.022314` | Community desktop startup, external sizing, task recovery, output modes, recording and optional launch targets |
| nubia Z80 Ultra NX741J | Android 16, build `20251229.234747` | Community wired/freeform, `2560x1080@75`, focus, keyboard, phone-screen-off, recovery and simulated cleanup |

This is coverage of workflows, not a zero-failure claim for every current build.
OnePlus immersive-request publication remains a known limitation; its exact
fingerprint profile has not been added to the declarative catalog.
The Nubia HDMI node is not shell-readable on the EEA/Z80 profiles, but the
confirmed physical modes remain usable through Android. Hardware controls vary.

See [Compatibility](compatibility.md) for profile confidence and limitations.

## Automated Coverage

- JVM tests cover state models, parsers, resource ownership, task/display policy,
  shell quoting, files, content, profile identities and platform isolation.
- Lint and assembly validate the APK's API 34 minimum and module boundaries.
  Native helpers still need API 34 alignment/validation; see
  [Runtime API levels](runtime-api-levels.md).
- Linux and Windows CI build artifacts. Linux also runs native protocol/PTY
  fixtures. These jobs do not run Android emulators.
- Desktop self-tests run production lifecycle, launch, focus, fullscreen,
  caption/resize, mixed-window, taskbar, wallpaper and cleanup paths.
- Native caption-menu snap is a provider-defined test scenario, currently Nubia
  only. Other devices still exercise the common geometry and focus assertions.
- The stack guard checks only during an explicit test. Display-0 freeform
  isolation applies during external sessions and cleanup, not to legitimate
  phone Desktop windows.
- Fixture pixel checks recognize their colors under neutral system shadows.
  Wallpaper continuity and panel visibility keep separate assertions.
- MCP reports exact test run identity, current stage, last completed check,
  terminal result and cleanup. An accepted request or expired wait is not a pass.

## Independent-Service Matrix

Run these without Desktop; managed Desktop self-tests cannot prove isolation:

- [ ] On actual API 34, verify cold app/MCP startup and early Desktop/self-test
  rejection without HOME or display-policy changes.
- [ ] Verify Shizuku loss/reconnect while ordinary UI and authorized Termux
  remain independent; unavailable shell operations must fail explicitly.
- [ ] Verify Files operations, transfers and URI grants, including private drag
  boundaries on API 34 versus same-application cross-window drag on API 35+.
- [ ] Verify retained shell/Termux detach/reattach, explicit End session,
  transport failure and process replacement. Test with and without tmux.
- [ ] Create a virtual display, launch/capture fullscreen tools there and remove
  it without Desktop; verify viewer/display/session lifetimes separately.
- [ ] Verify APK update, exact installer receipt and reconnect on API 34/35/36,
  without retrying an accepted installation after transport loss.
- [ ] Validate local/network grant changes, interface loss, wrong tokens,
  transfer retries and digest checks independently of Desktop.

## Remaining Desktop Validation

- [ ] Compare ordinary Close followed by explicit virtual-display removal with
  self-test removal, with keyboard associations present and absent. Check for
  newly stale WindowManager transition-performance entries after each boundary.
  Close alone must keep the display; a passed isolated run does not prove the
  manual removal path.
- [ ] Recheck application-driven immersive entry/restore when request
  publication is unavailable, preserving the missing-observation result.
- [ ] Exercise a cold app redirect plus runtime permission dialog; verify initial
  mode/configuration, Activity survival and subsequent native resize.
- [ ] Verify original mouse delivery to application-owned custom caption regions
  on each target without replayed clicks or replacement captions.
- [ ] Verify HOME, phone Recent and return-to-workspace with compatibility
  redirection both enabled and disabled, including UserService loss.
- [ ] After clearing app data, verify Restore defaults and stale task cleanup
  without selecting a replacement HOME on the user's behalf.
- [ ] Verify file/folder move and Ctrl-drag copy between Files and Desktop.
- [ ] Verify per-app density release on cross-display return and Close.
- [ ] Verify proportional files, shortcuts and widgets across differently sized
  displays, and multiple owned displays with only one managed Desktop.
- [ ] Verify microphone synchronization, video-only cleanup and standard-provider
  recording without optional vendor internal audio.

## Additional Hardware And Release Coverage

- [ ] Align native helpers to the API 34 floor and provide matching ABIs before
  an Android Studio x86_64 API 34/35/36 emulator matrix.
- [ ] Test supported stock Pixel, Samsung and Xiaomi Android 15+ firmware;
  shared architecture is not a substitute for this coverage.
- [ ] Complete more Android 15 physical-output/capture scenarios and onboarding
  on devices with no previous Desktop configuration.
- [ ] Validate secondary built-in screens before enabling them as Desktop targets.
- [ ] Validate HDMI/USB/Bluetooth/phone audio routing.
- [ ] Validate VITURE Beast's 1200-line 3D EDID transition with the independent
  Kernel Fixes APK on its exact supported kernel.
- [ ] Repeat optional phone-power and hardware restoration after relevant OTAs.
