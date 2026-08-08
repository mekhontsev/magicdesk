# Compatibility and issue reports

MagicDesk targets ZTE-family firmware on Android 16 and newer. This is a
baseline gate, not a guarantee that every vendor hook exists on every model.
The app uses standard Android APIs where possible, but Console Mode activation,
absolute touchpad positioning, external-display input routing, WMShell desktop
commands, and several task transitions depend on undocumented firmware
behavior.

## Support levels

- **Verified** means the complete build fingerprint is in the tested profile
  list and the core desktop, window, input, and Console Mode paths were tested.
- **Compatible baseline, unverified** means the device identifies as ZTE,
  nubia, or REDMAGIC and runs API 36 or newer. MagicDesk allows startup, probes
  capabilities, and reports unavailable features individually.
- **Unsupported platform** means the vendor-family or Android-version baseline
  is not met. Device Setup does not apply persistent windowing values.

An OTA changes the fingerprint. A previously verified model therefore becomes
unverified until that firmware has been tested. This is intentional: private
Binder methods, component names, shell commands, and framework behavior can
change without an Android API-level change.

## Error behavior

Failures that can be isolated should not terminate the desktop. MagicDesk keeps
the rest of the UI running, shows a short user-facing message with a stable
error code such as `[SHELL-CONSOLE-002]`, and records technical context for the
diagnostics report. Repeated identical errors are coalesced for 30 seconds and
the local event log is size-bounded.

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
  raw-input read/write access, `/dev/uinput` open access, task APIs, and
  REDMAGIC hardware-node access;
- non-destructive presence checks for the REDMAGIC mirror-panel and mirrored
  text-input signatures, plus the last runtime text-input result when tested;
- overlay, notification-listener, WMShell desktopmode, ZTE launcher, and Nubia
  input-package probes;
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
the same operation works in the stock ZTE/Nubia desktop or projection UI; that
distinguishes a MagicDesk integration failure from a firmware limitation.

For a display issue, include the monitor/glasses model, selected **Output
mode**, **Fill display** state, and whether the same timing works in Nubia's
projection settings. For an input issue, include the keyboard or
pointing-device model. For a window issue, include the affected Android package
and whether the task was windowed, maximized, snapped, or true fullscreen.
