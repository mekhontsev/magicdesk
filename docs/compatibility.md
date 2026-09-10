# Compatibility and issue reports

The APK minimum is Android 14 / API 34. Managed Desktop requires Android 15 /
API 35. Independent tools, automation and display resources have separate
runtime prerequisites. Android 14 device validation is pending; see the
[API-level contract](runtime-api-levels.md).

The core is vendor-independent: shared Android adapters own tasks, input routing,
IME policy, HOME, displays, files and automation. Vendor extensions are not
required for that architecture, and their presence is not proof of a working
device. Actual release/firmware coverage must be evaluated per subsystem.

MagicDesk's managed Desktop targets capable Android 15+ firmware through one APK and one common
desktop runtime. The standard Android driver supports phone, simulated, and
already connected secondary-display sessions. A platform-driver boundary
separates that baseline from optional Nubia/REDMAGIC integration. A device
branded ZTE is not automatically treated as Nubia-compatible; until its
firmware interfaces are verified, it uses the standard Android driver.
Likewise, Nubia hardware running an AOSP-derived custom ROM uses the standard
Android provider for every component whose firmware API is absent. Passive
runtime probes may retain an independently available Nubia projection,
pointer, phone UI, internal-audio, diagnostics, or hardware-control
component. Vendor branding alone is not a baseline requirement and never
enables the complete Nubia integration.

The selected driver owns firmware-specific windowing properties, projection
state and output modes, phone UI recovery, cursor observation, optional
application entry points, and compatibility probes. Missing vendor interfaces
therefore appear as unavailable capabilities in Diagnostics instead of sending
the common runtime through an unrelated Nubia code path.

The platform baseline is not a guarantee that every hook exists on every
model. The firmware must expose working freeform task support and, for an
external session, a secondary display that accepts application tasks. Managed
projection, optional cursor observation, external-display input routing,
WMShell desktop commands, and several task transitions can still depend on
firmware behavior.

Device Setup enables freeform support and forced activity resizing on every
platform through shell UID 2000. Changing these required provisioning values
requires a reboot through the setup flow. They survive Close Desktop;
Restore defaults removes the overrides.

**Settings > Android system** exposes Android's optional
`force_desktop_mode_on_external_displays` setting. The switch reads the actual
Android value and changes it only with shell access and no desktop session.
Opening Settings or preparing the device does not enable it. Reconnecting the
external display can apply the change; some firmware may require a restart.
This advisory does not block MagicDesk startup. Diagnostics reports informational state,
not a required capability. The setting can add system navigation bars to
secondary displays and does not guarantee physical-input routing. It survives
Close Desktop; Restore defaults removes this override too.

Secondary sessions prepare a freeform display default through Android's
WindowManager on every platform. Explicit fullscreen tasks remain supported;
the phone display default is never changed. Diagnostics distinguishes this
shared policy from optional focus repair and reports pending display
mode restoration. Close restores a changed effective default; disconnected
physical displays are reconciled by stable identity when they return.

**Compatibility (next session)** groups seven
optional shared mechanisms: stalled-focus repair, stale fullscreen caption
refresh, Activity handoff mode/bounds repair, wired/wireless phone-task
isolation, retained phone-task recovery, stale phone freeform Recents cleanup,
and phone Recents redirection to HOME.
For external sessions, routed HOME selects phone Start's Recent page; for phone
Desktop, it presents the workspace.
Every platform can override these individually. The Android baseline recommends
focus repair enabled and the other six disabled. Stock Nubia firmware recommends
all seven; hybrid firmware adds the recommendations of its selected components
to the baseline. Explicit user choices take precedence. A session retains its selection
through Close and display-loss cleanup; edits affect the next session.

**Reset to platform defaults** in this section removes all seven user overrides
and the optional Android external desktop-mode override (default: off).
It requires Shizuku access and a closed Desktop. The selected platform supplies
the compatibility defaults again; application DPI profiles and other settings
are unchanged. This is separate from Device Setup's provisioning reset.

Reports distinguish defaults, saved overrides, next-session selection, active
selection. Physical input uses shared Android location-to-display associations,
not a compatibility preference. Diagnostics report actual routing and key-filter
readiness. The phone touchpad uses its own relative virtual mouse; neither it
nor physical right clicks require an absolute-position API. Software keyboard
input is independent. Optional pointer
observation and coordinate injection remain separate. Coordinate automation
uses Android display-targeted mouse events; it does not move a global vendor
cursor. A position query without a display identity is reported separately as
an unscoped observation, never as a confirmed position on the desktop.

Phone desktop availability is independent from external-display support.
MagicDesk reports Android's live
`config_canInternalDisplayHostDesktops` framework resource in Diagnostics, but
does not reject a local session from this value alone. A false value can disable
the framework's standard display-0 desktop path on some ROMs, while vendor or
shell windowing paths can remain usable. The phone self-test is the behavioral
source of truth; simulated and secondary-display support is unaffected.

## Support levels

- **Maintainer-verified** means the complete build fingerprint is in the tested
  profile list and the core desktop, window, input, and external-display paths were
  tested directly by the maintainer.
- **Community-tested** means a user supplied a complete diagnostics report and
  confirmed the relevant fixes and desktop workflows on that exact firmware.
  It is known compatible, but has not received the complete maintainer test
  matrix.
- **Compatible baseline, unverified** means an Android 15+ platform driver can
  provide the selected session type. MagicDesk allows startup, probes
  capabilities, and reports unavailable features individually. On the
  standard Android profile, external sessions use a secondary display that is
  already connected and reported by Android. A wireless connection button is
  shown when Android resolves `Settings.ACTION_CAST_SETTINGS`. It opens the
  system cast settings with ordinary app permissions. This checks the UI entry
  point, not Miracast support: desktop startup still requires Android to report
  a connected secondary display.
- **Unsupported platform** means the Android-version baseline or selected
  session requirements are not met. Device Setup does not apply unsupported
  platform-specific properties. An unsupported Desktop does not mean the APK's
  independent tools or automation are unavailable.

An OTA changes the fingerprint. A previously tested model therefore becomes
unverified until that firmware has been tested. This is intentional: private
Binder methods, component names, shell commands, and framework behavior can
change without an Android API-level change.

Android 15 is the managed-Desktop compatibility baseline, not by itself a verified
firmware profile. Its WMShell uses the older `desktopmode moveToDesktop`
command when that backend is enabled; MagicDesk detects either command name
and retains its direct transaction fallback. Its older window-container API is
handled by the central framework compatibility adapter. Frameworks without
application-requested visible inset types in `TaskInfo` report that specific
immersive-state observation as unavailable while the task
observer continues to provide lifecycle, focus, visibility, mode, and bounds.
MagicDesk does not infer a negative immersive request from that absence, so an
activity-mode guard will not override ambiguous application fullscreen. ROMs
that backport the field can use it on Android 15, but only if the framework also
enables publication of client requests. A declared field filled with the default
visible types is not evidence that the application declined immersive mode.
Diagnostics distinguishes an absent field, disabled publication, and an
unavailable feature-flag probe. Explicit MagicDesk fullscreen commands do not
depend on this observation.
Diagnostics reports the selected framework profile, immersive observation,
caption strategy, and InsetsSource signature separately from the vendor
platform composition. It also records the hybrid task-observation strategy,
150 ms active-session fallback interval, 16-task bound, and per-facet
provenance. These values identify behavior reconstructed from a bounded
typed Binder snapshot instead of a framework callback. Separate diagnostics
lines expose bounded polling, event-driven waits, and intentional runtime
delays, including their latest classified reason. Direct unclassified sleeps
and monitor waits are rejected by repository tests.

## Tested firmware

| Device | Firmware build | Support | Confirmed scope | Known limitations |
| --- | --- | --- | --- | --- |
| RedMagic 11 Pro (`NX809J`, EEA) | `20260204.221845` | Maintainer-verified | Wired and Miracast desktops, windows, physical and phone-side input, display modes, recording, hardware controls, and task recovery | The optional XR hot-plug kernel fix remains device and kernel specific |
| OnePlus 5 (`ONEPLUS A5000`) | LineageOS 22.2, Android 15 / API 35, `2ed70c6518` | Maintainer-verified | Phone and simulated desktops, Miracast, freeform and explicit fullscreen, focus, Alt+Tab, physical mouse/keyboard and layout switching, HOME/task/display cleanup, and remote MCP including APK updates | Application-requested immersive fullscreen remains unavailable (`WINDOW-015`); native caption snap is not part of this firmware's self-test scenario |
| RedMagic 11 Pro (`NX809J-UN`) | `20260625.022314` | Community-tested | Desktop startup, external sizing, task recovery, Mora discovery, output modes, and external-display recording | Not run through the complete maintainer hardware matrix |
| nubia Z80 Ultra (`NX741J`) | `20251229.234747` | Community-tested | Wired desktop, `2560x1080@75` output and wide external sizing on the physical display, multiple freeform windows, focus and keyboard input, phone-screen-off operation, task recovery, and simulated self-test cleanup | The vendor HDMI timing node is unavailable to shell UID 2000; Android's reported physical-display mode is sufficient for the confirmed wide output |

Exact tested fingerprints:

- `REDMAGIC/NX809J-EEA/NX809J:16/BQ2A.250705.001-BP2A.250605.031.A3/20260204.221845:user/release-keys`
- OnePlus 5 LineageOS build `2ed70c6518` reports `OnePlus/OnePlus5/OnePlus5:10/QKQ1.191014.012/2010292059:user/release-keys`. This reused stock fingerprint does not identify the installed ROM by itself: the verified target is LineageOS 22.2 / API 35 with that incremental build, not stock Android 10 or other Lineage builds.
- `REDMAGIC/NX809J-UN/NX809J:16/BQ2A.250705.001-BP2A.250605.031.A3/20260625.022314:user/release-keys`
- `nubia/PQ85A01-UN/PQ85A01:16/BQ2A.250705.001-BP2A.250605.031.A3/20251229.234747:user/release-keys`

Unverified reports and partially completed test matrices remain in
[`testing-backlog.md`](testing-backlog.md). They are promoted here only after a
user confirms the relevant desktop, window, input, and cleanup workflows on the
exact fingerprint.

The OnePlus profile uses the Standard Android provider without a firmware
extension. Its maintainer-verified scope documents direct testing, not a claim
that every self-test passes: `WINDOW-015` remains a known failure. Automatic
in-APK catalog recognition must not use its reused stock fingerprint alone.

## Known Limitations

- Android 14 installation is the chosen APK baseline, but device and native
  helper validation remain pending. The current helpers are ARM64-only; see
  [Runtime API levels](runtime-api-levels.md).
- Application-requested immersive state may be unavailable on Android 15
  firmware, including the tested Lineage path. Explicit fullscreen remains a
  separate operation; unsupported observation is not a negative app request.
- Native caption controls vary by firmware. Production does not infer their
  meaning from coordinates. The native caption snap self-test scenario is
  currently supplied only by the Nubia diagnostics provider.
- Custom-caption mouse handling depends on WMShell preserving the application's
  display-specific gesture-exclusion regions. MagicDesk does not replace
  native captions or replay intercepted clicks.
- Abrupt display removal can leave framework transition-performance state.
  Cleanup detects new residue; it cannot safely repair an orphaned system
  transition by issuing another application transaction.
- Full work-profile/Private Space support and additional built-in screens on
  dual-screen devices are not implemented/verified by the current identity
  and display infrastructure.

Native system shadows are expected. Self-test fixture-color comparisons account
for their dimming; a literal source RGB match is not required on the composed
screen.

## Error behavior

Failures that can be isolated should not terminate the desktop. MagicDesk keeps
the rest of the UI running, shows a short user-facing message with a stable
error code such as `[SHELL-CONSOLE-002]`, and records technical context for the
diagnostics report. A bounded set of recent error signatures suppresses
repetitions, exact duplicates from earlier process runs are collapsed when the
report is built, and the local event log is size-bounded.

Static environment states such as an unverified firmware profile, missing
Shizuku permission, or a stopped Shizuku server remain visible in **Capability
checks** but are not appended to the event history. Audits are read-only; the
event history is reserved for failed user or runtime operations.

Fatal uncaught exceptions are stored as `[CRASH-001]` before Android terminates
the process. Open Diagnostics after restarting MagicDesk to include that crash
in the next report.

## Creating a report

1. Reproduce the problem once.
2. Open **Tools > Diagnostics**. Device Setup also has a **Diagnostics** button
   when the desktop cannot start.
3. Press **Refresh** after the failing operation has completed.
4. Review the report, then use **Copy report** or **Share report**.
5. Send the complete report through [Telegram support](telegram-support.md)
   or paste it into the GitHub issue template. Add exact reproduction steps,
   expected behavior, and observed behavior. For the bot, follow its private-chat
   setup and use `/submit` once the report is ready.

The report includes:

- MagicDesk version and Android build fingerprint;
- manufacturer, model, API level, security patch, and supported ABIs;
- Shizuku installation, permission, UserService UID, and required
  desktop-windowing values;
- for active shell access, a non-destructive UserService capability
  probe covering its actual UID, SELinux domain, relevant Binder permissions,
  raw-input read/write access, `/dev/uinput` open access, and task APIs;
- on a selected vendor platform, additional non-destructive checks for its
  projection, input, hardware, launcher, and output-mode integrations;
- notification-listener and WMShell desktopmode probes;
- current displays and external input-device descriptors;
- a one-shot input snapshot with aggregate virtual-pointer activity, MagicDesk
  virtual-device presence, current routing associations, and observed pointer
  position;
- a read-only vendor cooling-settings snapshot that distinguishes the selected
  system-controls provider, discovered fan/pump control keys, and readable
  effective state without changing cooling policy;
- bounded structured MagicDesk error events;
- failed secondary HOME launch evidence under `DESKTOP-LAUNCH-002`: launch
  stage, process/Binder caller UID, Android start result, requested display,
  returned task/type and a bounded pre-cleanup task sample across displays;
- recent logcat entries from MagicDesk tags only;
- a schema-versioned JSON summary with platform composition, per-component
  providers, typed capability observations, window-transition routing, and the
  manual compatibility checklist.

The report excludes notification title/body text, user files, account data,
clipboard contents, and the installed-app list. MagicDesk-only logs can still
contain package names, task ids, display ids, and filenames involved in a
failed operation. Review the text before publishing it.

The shell capability probe does not read input events, inject a real event,
change a keyboard layout, alter display state, or write a hardware node.
Permissioned write paths are tested with rejected null arguments after Android
performs its permission check.

For an unknown firmware, **Extended vendor probe** is a separate, confirmed
action. It records only a bounded list of selected read-only system properties,
Binder/cmd service names, and framework filenames. It does not list installed
applications or inspect user storage. The saved section contains the build
fingerprint and selected platform composition so stale evidence remains
identifiable.

## Onboarding a new firmware

New vendor support is evidence-driven and keeps one APK:

Developers investigating the device with a coding agent should also follow the
canonical [AI-assisted device support workflow](ai-assisted-device-porting.md).
It covers MCP tracing, architecture classification, regression evidence, and
the handoff format without requiring the agent to inherit prior chat history.

1. Install the current development APK. Validate independent tools first;
   complete normal Device Setup only for managed Desktop on Android 15+.
2. Open **Tools > Diagnostics**, refresh the normal report, then explicitly run
   **Extended vendor probe** if the standard driver lacks a firmware feature.
3. On Android 15+, run the self-test for each available phone, simulated, wired,
   and wireless target while the device is awake and unlocked. On Android 14,
   use the independent-service matrix instead.
4. Open **Compatibility checklist** and record manual results for startup,
   window geometry, fullscreen restore, physical input, capture, task restore,
   HOME-role lifecycle, and output configuration on each target.
5. Attach the complete refreshed report and concise reproduction steps to one
   issue. The JSON section lets a maintainer turn the report into a regression
   fixture without copying vendor behavior into shared code.

An exact tested fingerprint can be added to the declarative profile catalog
after the relevant workflows are confirmed. A new platform extension is added
only when the report identifies a useful firmware interface that cannot be
expressed by the Standard Android baseline or an existing SoC backend. The
extension declares only the components it owns; all other behavior continues
through the baseline. Display drivers remain independent, so vendor support is
not multiplied into phone/wired/wireless/simulated driver combinations.

`raw_input.write` reports whether an event node can be opened with `O_RDWR`.
This is diagnostic permission evidence only. MagicDesk routes physical devices
through Android; it does not open or write their event nodes.

After required Desktop setup and any requested reboot, the report should show
the common freeform/resizable settings enabled. The two reviewed
`persist.wm.debug.desktop_*` properties are requirements only for the selected
Nubia extension. WMShell command availability is probed independently; its
absence can use the central framework transaction path. A rejected required
setup operation still blocks Desktop, not independent tools.

On some Nubia firmware, Android keeps notification-listener access enabled
after an app process or package restart but does not bind the service again.
MagicDesk first requests a rebind through the public Android API. If the
listener is still disconnected two seconds later, it performs a public
`requestUnbind(ComponentName)` / `requestRebind(ComponentName)` cycle. The
cycle preserves the user's notification-access grant while forcing Android to
recreate the listener connection; it does not require root or Shizuku. A failed
recovery is reported as `[NOTIFICATIONS-005]`.

## Useful issue boundaries

Use one issue per reproducible failure. Do not combine an input-routing problem
with an unrelated window-decoration or XR-resolution problem. Include whether
the same operation works in the device's stock desktop or projection UI; that
distinguishes a MagicDesk integration failure from a firmware limitation.

For a display issue, include the monitor/glasses model, selected **Output
mode** when that control is available, and whether
the same timing works in system projection settings. For an input issue,
include the keyboard or
pointing-device model. For a window issue, include the affected Android package
and whether the task was windowed, maximized, snapped, or true fullscreen.

If the vendor HDMI-mode node is unavailable to shell UID 2000, MagicDesk tries
the selected SoC display backend before falling back to Android's public mode
list. When neither source exposes alternate timings, the current physical mode
is read-only and timing selection remains with the system projection UI. This
does not disable the desktop.
Desktop wallpaper comes from the bundled MagicDesk artwork or a user-selected
image. An unreadable custom image falls back to its last valid cache or the
bundled background without failing the desktop session.
