# Compatibility and issue reports

MagicDesk targets capable Android 15+ firmware through one APK and one common
desktop runtime. The standard Android driver supports phone, simulated, and
already connected secondary-display sessions. A platform-driver boundary
separates that baseline from optional Nubia/REDMAGIC integration. A device
branded ZTE is not automatically treated as Nubia-compatible; until its
firmware interfaces are verified, it uses the standard Android driver.

The selected driver owns firmware-specific windowing properties, projection
state and output modes, phone UI recovery, absolute-pointer access, optional
application entry points, and compatibility probes. Missing vendor interfaces
therefore appear as unavailable capabilities in Diagnostics instead of sending
the common runtime through an unrelated Nubia code path.

The platform baseline is not a guarantee that every hook exists on every
model. The firmware must expose working freeform task support and, for an
external session, a secondary display that accepts application tasks. Managed
projection, absolute touchpad positioning, external-display input routing,
WMShell desktop commands, and several task transitions can still depend on
firmware behavior.

## Support levels

- **Maintainer-verified** means the complete build fingerprint is in the tested
  profile list and the core desktop, window, input, and Console Mode paths were
  tested directly by the maintainer.
- **Community-tested** means a user supplied a complete diagnostics report and
  confirmed the relevant fixes and desktop workflows on that exact firmware.
  It is known compatible, but has not received the complete maintainer test
  matrix.
- **Compatible baseline, unverified** means an Android 15+ platform driver can
  provide the selected session type. MagicDesk allows startup, probes
  capabilities, and reports unavailable features individually. On the
  standard Android profile, external sessions use a secondary display that is
  already connected and reported by Android.
- **Unsupported platform** means the Android-version baseline or selected
  session requirements are not met. Device Setup does not apply unsupported
  platform-specific properties.

An OTA changes the fingerprint. A previously tested model therefore becomes
unverified until that firmware has been tested. This is intentional: private
Binder methods, component names, shell commands, and framework behavior can
change without an Android API-level change.

Android 15 is an installable compatibility baseline, not yet a verified
firmware profile. Its WMShell uses the older `desktopmode moveToDesktop`
command when that backend is enabled; MagicDesk detects either command name
and retains its direct transaction fallback.

## Tested firmware

| Device | Firmware build | Support | Confirmed scope | Known limitations |
| --- | --- | --- | --- | --- |
| RedMagic 11 Pro (`NX809J`, EEA) | `20260204.221845` | Maintainer-verified | Wired and Miracast desktops, windows, physical and phone-side input, display modes, recording, hardware controls, and launcher recovery | The optional XR hot-plug kernel fix remains device and kernel specific |
| RedMagic 11 Pro (`NX809J-UN`) | `20260625.022314` | Community-tested | Desktop startup, external sizing, launcher recovery, Mora discovery, output modes, and external-display recording | Not run through the complete maintainer hardware matrix |
| nubia Z80 Ultra (`NX741J`) | `20251229.234747` | Community-tested | Wired desktop, multiple freeform windows, window manipulation, and the v1.6 simulated desktop self-test with 54 checks passed and no failures | Intermittent text-input focus transfer and occasional desktop latency reported; vendor HDMI timing node is unavailable to shell |

Exact tested fingerprints:

- `REDMAGIC/NX809J-EEA/NX809J:16/BQ2A.250705.001-BP2A.250605.031.A3/20260204.221845:user/release-keys`
- `REDMAGIC/NX809J-UN/NX809J:16/BQ2A.250705.001-BP2A.250605.031.A3/20260625.022314:user/release-keys`
- `nubia/PQ85A01-UN/PQ85A01:16/BQ2A.250705.001-BP2A.250605.031.A3/20251229.234747:user/release-keys`

Unverified reports and partially completed test matrices remain in
[`testing-backlog.md`](testing-backlog.md). They are promoted here only after a
user confirms the relevant desktop, window, input, and cleanup workflows on the
exact fingerprint.

## Error behavior

Failures that can be isolated should not terminate the desktop. MagicDesk keeps
the rest of the UI running, shows a short user-facing message with a stable
error code such as `[SHELL-CONSOLE-002]`, and records technical context for the
diagnostics report. An identical error is recorded only once during a process
lifetime, exact duplicates from earlier process runs are collapsed when the
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
5. Paste the complete report into the GitHub issue template and add exact
   reproduction steps, expected behavior, and observed behavior.

The report includes:

- MagicDesk version and Android build fingerprint;
- manufacturer, model, API level, security patch, and supported ABIs;
- Shizuku installation, permission, UserService UID, and required
  desktop-windowing values;
- for active Shizuku shell access, a non-destructive UserService capability
  probe covering its actual UID, SELinux domain, relevant Binder permissions,
  raw-input read/write access, `/dev/uinput` open access, and task APIs;
- on a selected vendor platform, additional non-destructive checks for its
  projection, input, hardware, launcher, and output-mode integrations;
- a read-only check for the current static system wallpaper image;
- overlay, notification-listener, and WMShell desktopmode probes;
- current displays and external input-device descriptors;
- bounded structured MagicDesk error events;
- recent logcat entries from MagicDesk tags only.

The report excludes notification title/body text, user files, account data,
clipboard contents, and the installed-app list. MagicDesk-only logs can still
contain package names, task ids, display ids, and filenames involved in a
failed operation. Review the text before publishing it.

The Shizuku probe does not read input events, inject a real event, change a
keyboard layout, alter display state, or write a hardware node. Permissioned
write paths are tested with rejected null arguments after Android performs its
permission check.

`raw_input.write` reports whether an event node can be opened with `O_RDWR`.
It does not test exclusive capture: the input bridge opens physical devices
read-only and applies `EVIOCGRAB` only when a real external input session
starts. Direct writes to raw input devices are not required by MagicDesk.

After confirmed Shizuku Device Setup and reboot, the issue report should show
global freeform and resizable-activity settings enabled, both reviewed
`persist.wm.debug.desktop_*` properties disabled, and WMShell desktopmode
available. If provisioning is rejected on an unverified firmware, MagicDesk
reports the failed property or WMShell check and retains direct
ActivityTaskManager and WindowOrganizer transactions as the bounded task
fallback.

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
mode**, **Fill display** state when those controls are available, and whether
the same timing works in system projection settings. For an input issue,
include the keyboard or
pointing-device model. For a window issue, include the affected Android package
and whether the task was windowed, maximized, snapped, or true fullscreen.

If the vendor HDMI-mode node is unavailable to shell UID 2000, MagicDesk shows
Android's current physical mode as read-only and leaves timing selection to the
system projection UI. This limits MagicDesk's output-mode selector but does not
disable the desktop. If Android does not expose a static wallpaper image,
MagicDesk can use a custom desktop wallpaper, its cached system wallpaper, or
the built-in background without failing the desktop session.
