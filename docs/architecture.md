# MagicDesk Architecture

This document describes the implementation boundaries behind MagicDesk's
DeX-style desktop experience on REDMAGIC firmware. It is intended for
contributors, reviewers, and users diagnosing compatibility problems.

## Design Principles

MagicDesk follows five constraints:

1. Android applications remain real Android tasks.
2. The firmware's `ShellTaskOrganizer` and native window decorations remain in
   control of move, resize, snap, maximize, minimize, and close.
3. Device-specific operations are narrow, reversible, and checked before use.
4. Background work is event-driven where Android exposes an event source.
5. Optional kernel code is kept outside the main APK.

MagicDesk does not register a competing task organizer, host applications in
surrogate activities, draw replacement captions, patch SystemUI, or require a
Magisk module.

## Architecture Guardrails

Several plausible implementations conflict with REDMAGIC's secondary-display
stack. Keep these constraints when changing the architecture.

### Keep Physical Input Independent Of The IME

Do not select an IME with root, hardcode Gboard or a project-specific keyboard,
or enable shortcuts only while a particular IME is active. An
`AccessibilityService` with `FLAG_REQUEST_FILTER_KEY_EVENTS` can observe some
key paths, but it is not a reliable display-wide contract for physical events
routed through Nubia's Console display. Tying that service to a selected IME
also makes shortcut availability and ordinary phone typing depend on each
other.

Physical layout switching, repeat, and global shortcuts belong in the input
bridge. The user's on-screen IME remains an independent Android setting.

### Use WMShell Instead Of WindowReply

Nubia exposes private `ActivityClient` methods named
`toggleSwitchNormaltoHangWr`, `toggleSwitchHangtoNormalWr`,
`toggleSwitchFromFreeformWrtoFullScreen`, and
`toggleSwitchFromFullScreenToFreeformWr`. These calls participate in the
vendor WindowReply policy and do not provide a general desktop contract for
arbitrary installed applications. Do not use them as a fallback.

The WMShell `DesktopTasksController` path accepts real task ids, preserves
Android's task ownership, and supports applications outside Nubia's WindowReply
allowlist.

### Do Not Proxy The Whole Mouse

Do not grab the physical pointer with `EVIOCGRAB` and forward its complete event
stream through a `uinput` device. That makes movement and every button depend
on a long-running userspace proxy, creates duplicate-device and reconnect
failure modes, and changing `BTN_RIGHT` into a forward/extra button does not
deliver Android's standard secondary-click semantics.

MagicDesk changes only the physical keymap entry that Nubia consumes, reads
only that button transition, and injects one secondary-button sequence at the
current system pointer coordinates.

### Do Not Draw Replacement Application Captions

An application overlay cannot share a task's SurfaceControl leash or transition
atomically with WMShell. A separately drawn caption therefore trails live
movement, maintains a different Z-order from its task, and can leave controls
above the wrong overlapping window.

Keep native WMShell captions visible by controlling Nubia's SurfaceFlinger
filter. MagicDesk overlays are appropriate for shell-owned UI such as the
taskbar and Start, not application window decoration.

### Do Not Recreate Tasks Through Display 0

Do not use the phone display as a window-mode trampoline, force-stop the target
application, or add guessed sleeps to refresh fullscreen geometry. Those paths
can destroy the Activity and user session. Setting a task to its already
effective density is also not a configuration change and cannot refresh stale
caption insets.

Use the same-display transactions documented in
[Fullscreen transitions](fullscreen-transitions.md). Application-requested
immersive mode must preserve the current Activity instance.

## Components

| Component | Package or path | Responsibility |
| --- | --- | --- |
| Main application | `io.github.mekhontsev.magicdesk` | Desktop shell, taskbar, overlays, setup, diagnostics, and services |
| Kernel Fixes add-on | `io.github.mekhontsev.magicdesk.kernel` | Optional, explicitly confirmed firmware-specific kernel fixes |
| Hidden API stubs | `hidden-api-stubs/` | Compile-time signatures only; never packaged |
| Mouse remap helper | `native/magicdesk_mouse_remap.c` | Short-lived physical HID keymap adjustment |
| Root Java helpers | Main application classes | Short-lived `app_process` access to framework and vendor Binder APIs |

The main app and add-on are independent APKs. The main app resolves the add-on
by exact package and action, then requires `PackageManager.checkSignatures()` to
return `SIGNATURE_MATCH`. The main APK contains neither `.ko` files nor
kernel-loading code.

### Main APK Source Boundaries

`MainActivity` is the Android lifecycle host and compatibility facade used by
the foreground services. Feature state belongs to focused collaborators:

- `StartMenuController`, `TaskbarController`, `TaskOverviewController`,
  `NotificationCenterController`, and `DesktopItemsController` own desktop UI.
- `PhoneLauncherController` owns the independent phone-layout launcher.
- `AppTaskController`, `WorkspaceController`, and `AltTabController` coordinate
  application tasks and persisted workspace state.
- `DisplayProfileController`, `DisplayDensityController`, and
  `ConsoleControlsController` own display-specific preferences and controls.
- `MagicDeskSessionController` owns shortcut-service restart and complete
  MagicDesk teardown.
- `DesktopTaskController` owns the native task transition state machine, while
  `DesktopTaskWatcher` owns its root helper process and event protocol.
- `ConsoleModeSwitcher` coordinates Console Mode. Keyboard-layout policy,
  Nubia touchpad integration, and raw mouse-button decoding live in
  `HardwareKeyboardLayoutController`, `NubiaTouchpadController`, and
  `RawMouseButtonWatcher`.

Repositories perform package, task, and document queries. View controllers do
not run privileged commands directly; platform coordinators do not construct
desktop panels. Keep this split when adding device-specific behavior.

`LauncherAppRepository` enumerates applications through Android's public
`LauncherApps` API and requests density-bounded icons. `LauncherIconRenderer`
then converts each icon to one bounded bitmap shared by its desktop, taskbar,
and menu views. Do not return to `ResolveInfo.loadIcon()` for the whole catalog:
some vendor icon resources decode at their maximum size and can retain hundreds
of megabytes of temporary bitmap data. App catalogs are not reloaded on every
`onResume`; explicit Refresh remains the user-controlled rescan path.

## Display And Session Model

Every launch resolves a session profile containing an independent privilege
mode and display target. MagicDesk can render its desktop shell on Android's
primary display, remain on the current display, or become the fullscreen HOME
activity on Nubia's virtual Console display. Using HOME only on that external
display keeps the desktop surface behind native freeform application tasks
without replacing the phone launcher.

Runtime audits report the backend available for a requested profile but do not
change the active process backend. `DeviceSetupActivity` explicitly activates a
successful audit before launching MagicDesk. `BootReceiver` does the same only
after confirming that initial setup was acknowledged and that the saved profile
requests Root or Auto-root operation. Keep this ownership explicit: diagnostics
and concurrent background audits must never promote a Basic or Shizuku session
to Root or trigger a first-run superuser prompt.

Display ids are runtime values and are never persisted as fixed constants.
When Console Mode is activated, MagicDesk waits for Android to publish the real
virtual display before launching or moving tasks. If Nubia creates it with
portrait dimensions, MagicDesk applies a display-scoped width/height override
and waits for the landscape geometry.

The phone and each external monitor have separate workspace profiles. An
external display is identified by the SHA-256 hash of its DisplayPort EDID. A
port/name/resolution identity is available immediately and migrates to the
stable EDID identity when the background root read completes.

Profiles store:

- preferred desktop DPI
- layout mode
- taskbar pins
- desktop shortcut order
- selected desktop-folder URI
- kept workspace application
- last confirmed freeform bounds and Z-order

## Console Mode Activation

The stock GameAssist projection panel enters expanded mode through Nubia's
vendor extension:

```text
DisplayManager.setCmdToDisplay(1, physicalDisplayId, 0, null)
```

MagicDesk invokes the same Binder method from a short-lived root `app_process`
helper. It does not synthesize Console Mode by writing
`app_mirror_status` or `app_mirror_displayid`.

On `Win+D`, MagicDesk:

1. Validates that the display recorded by Nubia still exists.
2. Finds the active physical external display when necessary.
3. Requests expanded mode and waits for a real Console display.
4. Corrects portrait display geometry if required.
5. Audits the existing MagicDesk task.
6. Recreates only a MagicDesk task that Nubia converted to ordinary freeform.
7. Focuses an already-correct desktop task without recreation.
8. Restores the saved visible freeform workspace.

Repeated requests during the transition are ignored. With no external display,
the shortcut does not accidentally launch a second desktop on display 0.

### Native Caption Visibility

REDMAGIC wired privacy mode uses:

```text
SurfaceControl.setSFOption(1102, 1)
```

The firmware then removes external-output layers whose debug names contain
`Task=`. AOSP captions are named `Caption of Task=<id>`, so they exist and
receive input but may be invisible. After Console Mode becomes ready,
MagicDesk calls `setSFOption(1102, 0)` through a short-lived root helper. On
teardown it restores the value represented by NubiaProjectionScreen's
`PRIVATE_MODE_WIRED` preference.

### Teardown

**Switch to mirror**, physical display removal, and explicit exit share one
cleanup path:

- remove display-scoped overlays and task captions
- stop the Console task watcher
- release keyboard and mouse display-port associations
- restore the physical right-button keymap
- restart the input bridge in shortcut-only mode
- restore Nubia components temporarily changed by MagicDesk
- wake the phone screen

A `DisplayManager.DisplayListener` validates the actual display lifecycle
instead of trusting only Nubia's global settings.

## Window And Task Management

Android does not expose `DesktopTasksController` as a public application API.
This firmware provides a root shell entry point:

```sh
cmd statusbar wmshell-passthrough desktopmode moveTaskToDesk <task-id>
```

MagicDesk probes the command before using it. A new application starts on the
Console display through a short-lived transparent dispatcher. The dispatcher
immediately closes; `DesktopTaskController` waits until
ActivityTaskManager reports the target task, then asks WMShell to move that
exact task to the desktop. Existing tasks are moved and reused without
relaunching their activities.

The system `ShellTaskOrganizer` remains the only organizer.
`DesktopTaskRepository` owns active, visible, minimized, and Z-ordered desktop
tasks. `DesktopModeWindowDecorViewModel` owns native captions and resize
behavior.

### Fullscreen And Maximize

Desktop maximize is a freeform layout that reserves taskbar space. True
fullscreen is a separate Android task mode and hides the MagicDesk taskbar.

For an explicit fullscreen transition, MagicDesk applies:

- fullscreen windowing mode
- empty override bounds
- task back-ordering

in one synchronous `WindowContainerTransaction`. It applies the returned BLAST
`SurfaceControl.Transaction`, activates the exact task through
ActivityTaskManager, and uses the completed back/front cycle to make Nubia
commit the new surface geometry. The task stays on the Console display and its
Activity is not relaunched.

Application-requested immersive mode, including fullscreen video in a browser,
uses the same task state rather than a MagicDesk-specific video API. Full
details are in [Fullscreen transitions](fullscreen-transitions.md).

### Workspace And Taskbar

The taskbar is built from real Android tasks plus persisted pins. Multiple
tasks from one package remain independent. Restoring a workspace reuses live
tasks, applies saved bounds, then focuses them bottom-to-top to reconstruct
Z-order. Closed tasks are skipped. A task explicitly promoted to fullscreen is
not forced back into the freeform workspace.

Show Desktop stores exact task ids, bounds, and top-first ordering. It does not
close or force-stop application processes.

## Task Observation

A root `TaskStackListener` helper receives lifecycle events from
ActivityTaskManager. After a short debounce it reads one task snapshot. This
drives:

- taskbar state
- exact-task focus
- workspace persistence
- fullscreen taskbar visibility
- native maximize correction
- global task shortcuts

The helper is tied to the foreground service through stdin and exits when that
service stops.

`TaskStackListener` does not report changes to a task's
`requestedVisibleTypes`. While a non-MagicDesk application is visible, the same
helper checks only that task among the eight upper Console tasks every 150 ms.
It emits data only when the value changes. When the desktop is frontmost, this
bounded immersive-mode monitor waits without a timer. Task removal and general
task snapshots remain event-driven.

## Overlay UI

One display-scoped application-overlay controller owns:

- persistent taskbar
- Start
- Open Tasks
- Tools
- notification center and notification popups
- calendar
- application and desktop context menus
- shortcut reference

These overlays stay above freeform tasks without focusing the MagicDesk HOME
task. The taskbar is hidden when an application owns true fullscreen.

Start remains non-focusable on the Nubia Console display. Making a third-party
application overlay focusable causes this firmware to tear down the virtual
display and move its tasks to display 0. Search can take normal focus when the
desktop is previewed on display 0. Only one auxiliary panel stays open, and
clicking empty desktop/taskbar space or pressing Escape dismisses it.

## Physical Keyboard Architecture

Nubia normally reinjects external-display key events with
`POLICY_FLAG_DISABLE_KEY_REPEAT`. MagicDesk's root input bridge associates
external alphabetic keyboards with the physical external-display port and
keeps Nubia's input-panel Binder token alive. The vendor framework then
preserves original key events and Android's normal repeat behavior.

Physical keyboard layout switching is independent of the selected on-screen
IME. `Ctrl+Space` invokes Android's configured physical-layout cycle, and the
taskbar label is derived from the active layout locale. MagicDesk does not read
or change the selected input method.

The global shortcut watcher handles desktop operations while another
application owns focus. Shortcuts operate on exact task ids and the current
Console display.

## Mouse Architecture

### Firmware Problem

Nubia converts non-hover Console mouse events into synthetic touchscreen events
with `SOURCE_TOUCHSCREEN` and `TOOL_TYPE_FINGER`. WMShell may show a side-resize
cursor but rejects the drag because the down event is not a mouse or stylus
source.

The firmware exposes a reversible runtime routing control:

```sh
su -c 'dumpsys display dmctrl inputSource mouse'
su -c 'dumpsys display dmctrl inputSource none'
```

MagicDesk selects `mouse` while Console Mode is active and restores `none`
during normal cleanup. This setting exists only in `system_server` memory.

### Right Button

`DisplayMirrorCtrl.handleMouseRightButton()` consumes physical
`BUTTON_SECONDARY` and injects `KEYCODE_BACK` before Android selects a target
window. An Activity cannot prevent this while another application owns focus.

The bridge runs `libmagicdesk_mouse_remap.so` once to use `EVIOCSKEYCODE_V2`
and map standard HID Button 2 (`0x00090002`) from Linux `BTN_RIGHT` to
`KEY_UNKNOWN` in the physical device keymap. Android InputReader ignores that
code, so Nubia cannot convert it to Back.

The existing bridge reads those button transitions directly from the physical
`evdev` node. A display-scoped `InputMonitor` supplies current pointer
coordinates. On release, the bridge injects one atomic standard Android
secondary-button sequence into the Console display. Android performs normal
window hit testing, so the application under the pointer receives right click.

The helper then exits. There is no `EVIOCGRAB`, `uinput` device, polling mouse
daemon, or replacement pointer stream. Movement, primary/middle buttons, real
side buttons, and wheels stay on Android's normal input path.

After changing the keymap, the bridge briefly disables and re-enables the
logical `InputDevice` so InputReader refreshes capabilities. On cleanup it maps
Button 2 back to `BTN_RIGHT`. `InputDeviceListener` restarts the bridge after
physical hot-plug so new mouse devices are configured without polling.

## Phone Touch Panel And Screen State

Nubia opens Touch Panel while Console Mode is still transitioning, then may
destroy it when the phone launcher returns because the panel is marked
`android:noHistory="true"`. Once the external desktop is ready, MagicDesk:

1. restores Nubia's mirror-input service and activity
2. sends the service's native `close_touch_panel` reset
3. invokes vendor display command `8`

Command `8` opens the UI and creates Nubia's virtual mouse. Starting
`open_touch_panel` directly does not reliably create the correct cursor
geometry. Bringing forward an existing Console desktop does not reopen the
panel unnecessarily.

The persistent phone notification exposes **Open touchpad**. It is safe to
press repeatedly and is a no-op outside Console Mode.

Nubia's exported `ProjectionPanelService` does not expose the complete
Mirror-to-Extended UI transition. Its `projection_enter` command only performs
the screenshot animation; GameAssist's private `startSwitchMode()` click path
updates the vendor panel layout. `ProjectionIcon` can therefore retain its
Mirror-mode geometry and omit the stock Touch Panel item. MagicDesk's
notification does not depend on that panel.

When the user dims the phone through MagicDesk, the app temporarily disables
only Nubia's exported `MirrorInputService`. This prevents external pointer focus
from waking the phone and opening the on-screen keyboard. MagicDesk observes
`nubia_screen_off_tp` and restores the component immediately when the phone is
woken through Power, Nubia's lock control, MagicDesk, or **Open touchpad**.
Exit and Console teardown also restore its manifest-default state.

## Notifications And System Panels

The notification center uses Android's standard
`NotificationListenerService`, granted explicitly by the user. Notification
clicks launch original `PendingIntent` objects on the current display;
supported actions and dismissal remain system operations.

Important new notifications can appear above the taskbar. Ongoing, progress,
group-summary, and silent updates do not create popups. Diagnostics deliberately
exclude notification contents.

The calendar overlay uses Android's standard month-calendar view and needs no
calendar-data permission. Opening the full calendar uses the standard Android
calendar application category.

## Device Setup

Device Setup audits system provisioning separately from runtime privileges.
Basic mode does not invoke `su` and may enter a degraded desktop when the
windowing configuration is incomplete. Root mode remains strict and does not
fall back when `su` is unavailable. Auto currently resolves to Root when
available and Basic otherwise. Explicit Shizuku mode binds an official
Shizuku UserService and dispatches finite shell commands through AIDL. It does
not fall back when the server or permission is unavailable and never starts
the long-lived root input helpers.

After explicit confirmation it applies only missing values:

```sh
su -c 'settings put global enable_freeform_support 1'
su -c 'settings put global force_resizable_activities 1'
su -c 'setprop persist.wm.debug.desktop_mode_enforce_device_restrictions false'
su -c 'setprop persist.wm.debug.desktop_use_rounded_corners false'
```

The first two correspond to Android's **Enable freeform windows** and **Force
activities to be resizable** developer options. MagicDesk does not enable the
Developer Options master switch.

No other `persist.wm.debug.desktop_*` value is currently required. MagicDesk
does not force desktop mode on every freeform display, enable cross-display
window dragging, override density globally, change maximum task count, or
replace Android's resize animation.

WMShell and ActivityTaskManager cache these values. Device Setup records the
boot id and requires a real reboot after a change. Manual verification:

```sh
su -c 'settings get global enable_freeform_support'
su -c 'settings get global force_resizable_activities'
su -c 'getprop persist.wm.debug.desktop_mode_enforce_device_restrictions'
su -c 'getprop persist.wm.debug.desktop_use_rounded_corners'
```

Expected values are `1`, `1`, `false`, and `false`.

Previous values are stored only for settings MagicDesk changes. **Restore
previous values** restores those owned values and requests another reboot.
Values already configured before MagicDesk first ran remain untouched.

The display-scoped taskbar requires `SYSTEM_ALERT_WINDOW`. The foreground
service grants the corresponding app-op through existing root access when
needed:

```sh
su -c 'appops get io.github.mekhontsev.magicdesk SYSTEM_ALERT_WINDOW'
su -c 'appops set io.github.mekhontsev.magicdesk SYSTEM_ALERT_WINDOW allow'
```

See [Privilege and display modes](privilege-modes.md) for the capability
boundaries, Primary display behavior, and development launch overrides.

## Hidden APIs And Compatibility

`hidden-api-stubs` contains only Java signatures required at compile time. It is
a `compileOnly` dependency and is absent from the APK. Runtime root helpers load
the real framework classes from the device.

Every undocumented vendor or hidden-framework entry point is probed before
use. Compatibility Diagnostics records structured failure codes and capability
results. A baseline-compatible firmware is not represented as verified unless
its complete build fingerprint has been tested.

See [Compatibility and issue reports](compatibility.md) for report contents and
issue requirements.

## Build And Release Boundaries

The checked-in Gradle Wrapper builds three modules:

- `app`: main MagicDesk APK
- `kernel-fixes`: optional add-on APK
- `hidden-api-stubs`: compile-only Java library

On conventional Linux, Gradle compiles the arm64 mouse helper with the Android
NDK. On Termux it uses `$PREFIX/bin/clang`. The output is generated under the
module build directory and packaged only into the main APK. No native helper
binary is checked in.

The reviewed `dp_mode_reset.ko` exists only under the add-on resources. Normal
Android CI packages that reviewed file but never invokes the separate kernel
build script. Kernel compilation requires exact upstream source, config, and
symbol inputs described in [VITURE XR resolution fix](xr-resolution-fix.md).

Release signing is configured through these Gradle properties:

- `MAGICDESK_RELEASE_STORE_FILE`
- `MAGICDESK_RELEASE_STORE_PASSWORD`
- `MAGICDESK_RELEASE_KEY_ALIAS`
- `MAGICDESK_RELEASE_KEY_PASSWORD`

GitHub Actions provides them through encrypted repository secrets using the
same names, with the keystore stored as
`MAGICDESK_RELEASE_KEYSTORE_BASE64`. Pull-request CI receives no signing
secrets and builds unsigned release APKs.

The tagged release workflow:

1. builds the helper from C
2. lints and assembles both APKs
3. verifies both APK signatures and certificate equality
4. rejects a main APK containing `.ko`
5. verifies the add-on's packaged module against the reviewed binary
6. emits `SHA256SUMS`
7. publishes only for a `v*` tag matching the main `versionName`

## Related Documents

- [Fullscreen transitions](fullscreen-transitions.md)
- [Compatibility and issue reports](compatibility.md)
- [VITURE XR resolution fix](xr-resolution-fix.md)
