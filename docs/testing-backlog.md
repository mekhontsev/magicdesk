# Validation Matrix

This is the current coverage and remaining validation plan, not a log of test
runs. Build success does not prove firmware behavior. Exact run IDs, results,
fingerprints and reproduction details belong in compatibility reports.

## Device Coverage

| Device | Runtime | Exercised scope |
| --- | --- | --- |
| RedMagic 11 Pro NX809J EEA | Android 16 / API 36, build `20260204.221845` | Direct phone/simulated/HDMI/Miracast testing, physical and phone input, recording, HOME lifecycle, task cleanup |
| OnePlus 5 (`ONEPLUS A5000`) | LineageOS 22.2 / Android 15 / API 35, build `2ed70c6518` | Maintainer-verified phone/simulated and Miracast testing on the Standard Android provider, physical mouse/keyboard, focus and Alt+Tab, HOME/task/display cleanup, remote MCP and APK updates |
| Samsung Galaxy A52s (`SM-A528B`) | Android 14 / API 34, build `A528BXXSBGYI3` | Maintainer-verified independent shared services under Shizuku UID 2000: network MCP, headless shell, UI inspection/actions, display screenshots, debug APK updates/reconnect, Miracast input and phone touchpad |
| RedMagic 11 Pro NX809J-UN | Android 16, build `20260625.022314` | Community desktop startup, external sizing, task recovery, output modes, recording and optional launch targets |
| nubia Z80 Ultra NX741J | Android 16, build `20251229.234747` | Community wired/freeform, `2560x1080@75`, focus, keyboard, phone-screen-off, recovery and simulated cleanup |

This is coverage of workflows, not a zero-failure claim for every current build.
OnePlus immersive-request publication remains a known limitation (`WINDOW-015`);
its support record is scoped to the tested Lineage build, not every ROM sharing
its reported stock fingerprint.
The Nubia HDMI node is not shell-readable on the EEA/Z80 profiles, but the
confirmed physical modes remain usable through Android. Hardware controls vary.

See [Compatibility](compatibility.md) for profile confidence and limitations.

### Android 14 Shared Services

On Samsung SM-A528B, the verified workflows run without Desktop or a HOME lease:

- Network MCP state, diagnostics and headless shell commands.
- Same-signature debug APK upload/replacement, exact installer receipt and
  connection to the new process without resetting app data.
- Android UI inspection by display and task, semantic actions and phone-display
  screenshots.
- Miracast input acquisition, release and reacquisition; the phone touchpad's
  native uinput helper emits events with the system pointer targeting that output.
- Live screen-keyboard placement changes without restarting the mouse. A rejected
  fallback policy is a separate warning and leaves input ready.

These checks do not cover Termux, PTY, X11/Wayland, physical keyboard/mouse
hotplug, or the complete virtual-display and ordinary task-operation lifecycle.
The tested Google Cast output exposes no input viewport; it is not covered by
the successful Miracast direct-pointer path.

## Automated Coverage

- JVM tests cover state models, parsers, resource ownership, task/display policy,
  shell quoting, files, content, profile identities and platform isolation.
- Appearance fixtures cover schema and bundle validation, scoped preview rollback,
  component layout, native motion/backdrops, media lifetime, shader bindings,
  shared signal subscriptions, stale callbacks and CPU counter resets. Native
  graphics/driver behavior still requires device checks.
- Lint and assembly validate the APK's API 34 minimum and module boundaries.
  Native artifacts target API 34; device coverage remains per helper, not implied
  by the build. See
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

Shroot has its own [device coverage and limits](guest-runtime.md#coverage-and-limits)
and [fixture commands](../native/guest-exec-lab/README.md). On RM11/API 36 under
UID 2000 these exercise glibc/musl userspaces, packages and OCI layers, concurrent
launches, watches/IPC, debuggers, browser filters and X11/Wayland applications.
Those results do not cover every distribution or kernel and are not Desktop
self-test results. Remaining Shroot device gates include actual API 34/older
kernels, 16 KiB pages and actual root execution. Installer GUI/Turnip recipes
also need separate validation for each distribution/toolchain; a recipe's
presence is not a passed desktop or GPU test.

Run these without Desktop; managed Desktop self-tests cannot prove isolation:

- [ ] On actual API 34, verify cold app/MCP startup and early Desktop/self-test
  rejection without HOME or display-policy changes.
- [ ] Verify Shizuku loss/reconnect while ordinary UI and authorized Termux
  remain independent; unavailable shell operations must fail explicitly.
- [ ] Verify Files operations, transfers and URI grants, including private drag
  boundaries on API 34 versus same-application cross-window drag on API 35+.
- [ ] Verify retained shell/Termux detach/reattach, explicit End session,
  transport failure and process replacement. Closing a managed tmux window must
  release only its client; an ordinary shell remains retained.
- [ ] On actual API 34, validate X11 and Wayland startup, individual/whole-desktop
  views, input, live DPI and shutdown with no Desktop or privileged service.
- [ ] Validate X11 direct touch and real pen/eraser hardware: simultaneous contacts,
  pressure/tilt, proximity, barrel buttons, tool/device changes, cancellation and
  drag handoff. Compare XI2-aware and legacy clients; synthetic MotionEvents and
  native fixtures do not establish physical device compatibility.
- [ ] Exercise clipboard and copy drag-and-drop with Android and same-/cross-protocol
  Linux sessions: text, HTML, PNG, files, large transfers (including X11 INCR), denied URI grants,
  cancellation and owner loss. Include container paths accessible and inaccessible
  to the selected server. Shell-hosted chroot file exchange must stay inside its
  explicit shared directory; never resolve a failure by escalating identity.
- [ ] Exercise Shell/root chroot terminals, X11 and Wayland with Termux disabled, including
  guest users, shared-file limits, concurrent sessions, Stop and process loss.
  Basic Alpine workflows were exercised on RM11/API 36; other firmware and
  Android 14 remain unverified.
- [ ] Exercise Linux menus, popups and transient windows through the common
  dependent host on API 35+, including unavailable-capability fallback, parent
  movement, clipping, grabs, density changes and parent/display closure. Verify
  independent hosts and whole-desktop viewers without external presentation.
- [ ] Exercise X11 dock/desktop reservations and Wayland layer-shell/foreign-
  toplevel panels alongside Android windows, including binding loss and release.
  Use the focused [shell-layout fixtures](shell-layout.md#verification); Desktop
  self-tests do not replace protocol-client coverage.
- [ ] Validate Vulkan and software composition, DMA-BUF format rejection, frame
  fences, resize and output loss across graphics drivers. Use the focused
  [graphics fixtures](graphics.md) and [Wayland tests](wayland.md#verification).
- [ ] Create a virtual display, launch/capture fullscreen tools there and remove
  it without Desktop; verify viewer/display/session lifetimes separately.
- [ ] On API 34, verify ordinary task transfer/closure, task-surface capture and
  physical input hotplug. Investigate `task moved or closed before placement`
  responses for live Samsung tasks; input-route success does not validate these
  separate operations.
- [ ] Exercise failed/interrupted APK updates and reconnect on API 34/35/36,
  without retrying an accepted installation after transport loss. Successful
  replacement and exact-receipt coverage on Samsung/API 34 is recorded above.
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
  displays. Exercise simultaneous phone/external/virtual workspaces, independent
  widget hosts and closing only one workspace.
- [ ] Exercise portable workspace parking, output reconnect, Viewer source
  switching and input handoff. Distinguish source removal from output loss;
  clients must remain on the same logical display while its Viewer is absent.
- [ ] Verify direct and portable startup on public untrusted outputs, ordinary
  mirror viewers and optional protected sources without making root a prerequisite
  for the ordinary path.
- [ ] Verify microphone synchronization, video-only cleanup and standard-provider
  recording without optional vendor internal audio.

## Appearance Validation

On RM11/API 36, two simultaneous virtual workspaces exercised AGSL CPU, RAM and
battery values with pixel checks, shared source reference counts, selective
unsubscription, static fallback after animation was disabled, and preview
rollback without changing global appearance. This does not establish shader or
decoder compatibility on other devices. Desktop self-tests cover basic wallpaper
and chrome invariants, not every theme or media format.

- [ ] Exercise native tool styling, document-picker imports and preview
  cancellation without Desktop or shell on actual API 34.
- [ ] Verify wallpaper decoding, shader compilation and blur availability across
  Android 15+ graphics drivers, including unavailable blur and playback failure.
- [ ] Exercise all panel edges, floating gaps, edge reveal and live composition
  changes alongside Linux panels and fullscreen applications on phone and
  external outputs; confirm input, popup placement and reservations stay aligned.
- [ ] Verify media and signal release on display-off, power saving, reduced
  motion, workspace closure and shell loss/reconnect. In particular, no-signal
  and battery-only themes must not start CPU/RAM sampling.

## Additional Hardware And Release Coverage

- [ ] Complete API 34 native-helper coverage beyond the verified phone
  mouse/uinput path, including PTY and graphical runtimes.
  Any future x86_64 emulator matrix requires matching native artifacts first.
- [ ] Test supported stock Pixel, Samsung and Xiaomi Android 15+ firmware;
  shared architecture is not a substitute for this coverage.
- [ ] Complete more Android 15 physical-output/capture scenarios and onboarding
  on devices with no previous Desktop configuration.
- [ ] Validate secondary built-in screens before enabling them as Desktop targets.
- [ ] Validate HDMI/USB/Bluetooth/phone audio routing.
- [ ] Validate VITURE Beast's 1200-line 3D EDID transition with the independent
  Kernel Fixes APK on its exact supported kernel.
- [ ] Repeat optional phone-power and hardware restoration after relevant OTAs.
