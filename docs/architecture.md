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

### Keep The Root Mouse Path Minimal

Root mode does not proxy the whole mouse. It changes only the physical keymap
entry that Nubia consumes, reads only that button transition, and injects one
secondary-button sequence at the current system pointer coordinates. Movement,
scrolling, and the remaining buttons stay on Android's normal input path.

Shizuku shell cannot write that physical keymap or create the display-scoped
input monitor used by the root translator. On the verified firmware it can,
however, open an external cursor read-only, acquire `EVIOCGRAB`, and create a
`BUS_VIRTUAL` mouse through `/dev/uinput`. Nubia applies its Back conversion to
the external physical device but not to the internal virtual pointer, so an
unchanged forwarded `BTN_RIGHT` becomes Android `BUTTON_SECONDARY`.

`ShizukuMouseBridge` discovers only EventHub devices marked `CURSOR | EXTERNAL`.
One native helper unions their key/relative capabilities, creates one virtual
pointer, then grabs and forwards the physical streams. It is started only in
Shizuku Console Mode and is restarted after physical input hot-plug. The app
sends a one-second heartbeat; after six seconds without one, or after stdin
closes, the helper destroys its `uinput` device and releases all grabs. Kernel
file-descriptor cleanup provides the same release after an uncatchable process
termination.

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
| Main application | `io.github.mekhontsev.magicdesk` | Phone control panel, desktop shell, taskbar, overlays, setup, diagnostics, and services |
| Kernel Fixes add-on | `io.github.mekhontsev.magicdesk.kernel` | Optional, explicitly confirmed firmware-specific kernel fixes |
| Hidden API stubs | `hidden-api-stubs/` | Compile-time signatures only; never packaged |
| Input remap helper | `native/magicdesk_mouse_remap.c` | Short-lived physical HID keymap adjustment |
| Shizuku mouse helper | `native/magicdesk_uinput_bridge.c` | Heartbeat-bound physical-to-virtual pointer forwarding |
| Shizuku keyboard helper | `native/magicdesk_keyboard_bridge.c` | Heartbeat-bound keyboard forwarding and shortcut interception |
| Root Java helpers | Main application classes | Short-lived `app_process` access to framework and vendor Binder APIs |

The main app and add-on are independent APKs. The main app resolves the add-on
by exact package and action, then requires `PackageManager.checkSignatures()` to
return `SIGNATURE_MATCH`. The main APK contains neither `.ko` files nor
kernel-loading code.

### Main APK Source Boundaries

The user-facing lifecycle is split into explicit roles:

- `ControlActivity` and `PhoneControlPanelController` provide a compact phone
  control surface. They do not enumerate launcher applications or create
  desktop task, taskbar, wallpaper, or overlay controllers.
- `DesktopActivity` is the only concrete desktop Activity.
  `DesktopShellActivity` composes its controllers and forwards Android
  callbacks instead of owning feature state. It can run on the Console display
  or explicitly on display 0 for a tablet or phone-hosted desktop.
- `DeviceSetupActivity` owns onboarding and selects one of those roles after a
  successful runtime audit.
- `ConsoleSeedActivity` is an internal opaque foreground task used only for
  Nubia's Mirror-to-Console handshake. It is exported behind
  `MANAGE_ACTIVITY_TASKS` so either Root or the Shizuku shell service can launch
  it, never appears in Recents, and is removed as soon as the external desktop
  HOME task is ready.

- `StartMenuController`, `TaskbarController`, `TaskOverviewController`,
  `NotificationCenterController`, and `DesktopItemsController` own desktop UI.
- `DesktopInputController` translates Activity input callbacks into desktop
  secondary-click, panel dismissal, and shortcut actions.
- `DesktopRuntimeBridge` is the narrow process-local facade used by services
  and receivers to reach the currently active shell. It keeps Activity
  references weak and always posts UI work to the Activity thread.
- `AppTaskController`, `WorkspaceController`, and `AltTabController`
  coordinate application tasks, persisted desktop state, and task switching.
- `DesktopLayoutController` owns system-inset policy, desktop viewport changes,
  and persistent taskbar geometry. `DesktopTaskSnapshotController` owns the
  current task snapshot, serialized refresh generations, and taskbar task
  filtering.
- `DisplayProfileController`, `DisplayDensityController`, and
  `ConsoleControlsController` own display-specific preferences and controls.
- `MagicDeskSessionController` owns complete MagicDesk teardown through the
  small `MagicDeskSessionHost` contract shared by the control and desktop
  Activities.
- `DesktopTaskController` orchestrates native task transitions.
  `DesktopTaskStateStore` owns window-layout snapshots,
  `DesktopWindowTransitionController` owns shortcut-driven window state,
  fullscreen/restore transitions, and immersive requests,
  `NativeWindowBoundsController` owns freeform geometry, and
  `DesktopPhoneUiReconciler` repairs displaced phone-side Nubia UI.
  `DesktopTaskWatcher` owns the selected privileged helper and its
  bidirectional event/control protocol.
- `ConsoleModeSwitcher` is the serialized public facade for Console actions.
  `ConsoleSessionController` owns desktop activation,
  `ConsoleDisplayController` owns dynamic display discovery and geometry, and
  `ConsoleRootShell` owns the persistent marker-delimited `su` channel.
- `ConsoleInputBridgeCommand` coordinates physical input setup.
  `ConsoleInputDeviceDiscovery` discovers Event Hub devices,
  `ConsoleKeyboardTabController` handles the Alt-Tab protocol,
  `ConsoleRightButtonTranslator` handles only secondary-button translation,
  and `ConsoleInputEventInjector` contains hidden input injection.
- `HardwareKeyboardLayoutController` and `NubiaTouchpadController` own keyboard
  layout policy and Nubia touchpad integration independently of the raw bridge.
- `RedmagicHardwareController` owns capability probing, monitoring, validated
  fan/pump writes, and baseline restoration. Its UI and parser do not issue
  privileged commands.
- `DesktopNotificationListenerService` owns Android listener connection and
  notification state; `DesktopNotificationMapper` is the isolated conversion
  boundary from framework notifications to desktop entries.
- `DeviceSetupManager` owns provisioning and audits,
  `DeviceSetupView` owns setup rendering, and
  `DeviceSetupRuntimeController` is the only setup component that starts or
  stops MagicDesk runtime services.

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
mode and display target. A launch originating on display 0 always opens the
phone control panel; the saved display target must not replace that entry point
with the desktop shell. The control panel can explicitly launch the desktop on
its own display, which supports tablets without reintroducing screen-width
layout heuristics. Console activation resolves the current external display at
runtime.

On Nubia's virtual Console display, the desktop becomes the fullscreen HOME
activity. Using HOME only there keeps the desktop surface behind native
freeform application tasks without replacing the phone launcher. The phone
control and external desktop use separate Android tasks and may coexist. A
desktop opened on the same display as the control panel stays in the control
task, so Nubia Recents presents one MagicDesk card. Cross-display desktop
launches still use a separate task. Setup and desktop components also request
exclusion from Recents, although this firmware does not reliably honor that
flag for a live standalone task.

The desktop uses one viewport model on every display. `WindowMetrics` supplies
the physical display bounds and current `systemBars()` insets. Desktop content
and overlays occupy the inset content rectangle; native maximize and snap
reserve the MagicDesk taskbar inside that rectangle. A dedicated external
display normally reports zero system-bar insets and therefore fills the whole
screen, while a phone or tablet keeps Android's status and navigation areas.

When Android's own desktop mode causes the MagicDesk host task to inherit a
freeform windowing mode, `DesktopHostWindowController` normalizes that host
task through the same-display recreating fullscreen transition. Nubia forces
the initial freeform caption inset into `DecorView`; preserving that client
would leave the caption-height margin after the task becomes fullscreen. The
controller makes one attempt per multi-window episode and requires the
`TASK_CONTROL` capability. Application-requested immersive transitions still
preserve their existing client and remain owned by
`DesktopWindowTransitionController`.
There is no display-0 layout fork.

The taskbar is a persistent display-scoped overlay so it remains available
above native freeform tasks. The task watcher follows whichever display owns
the live desktop, including display 0. It leaves the taskbar visible for the
desktop and freeform applications, and hides it while an unrelated fullscreen
application owns that display. The same rule replaces Activity-lifecycle
special cases on the primary display.

Nubia Quickstep cannot safely bind a Recents desktop group containing freeform
tasks on display 0. A local desktop session therefore records a pending cleanup
before opening any windows. Closing that desktop or exiting MagicDesk converts
all standard display-0 freeform tasks to fullscreen in one synchronous
`WindowContainerTransaction` before the phone launcher is exposed. The marker
survives a MagicDesk process failure and is reconciled on the next manual
launch. This prevents Quickstep's crash recovery from replacing the user's
launcher workspace with its factory layout.

Basic sessions cannot submit a corrective task transaction, so they never
request native freeform launches. Their application actions resolve to
fullscreen instead. Root and Shizuku sessions expose windowed launches because
both can atomically normalize those tasks during cleanup.

Runtime audits report the backend available for a requested profile but do not
change the active process backend. `DeviceSetupActivity` explicitly activates a
successful audit before launching MagicDesk. A device reboot never starts
MagicDesk or restores its runtime services; the user must launch the application
explicitly. Keep this ownership explicit: diagnostics, boot events, and
concurrent background audits must never activate a backend, promote a Basic or
Shizuku session to Root, or trigger a first-run superuser prompt.

Manual Device Setup runs in a separate Android task pinned to the display where
it was opened. Control and desktop launches also use separate tasks. Do not
place activity records from two displays in the same task: this
REDMAGIC firmware can move the setup activity to display 0 while leaving the
desktop activity visible elsewhere, then report no focused application window
and raise an input-dispatch ANR.

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

MagicDesk invokes the same Binder method from a short-lived privileged
`app_process` helper. Root starts it directly; Shizuku starts the same command
as Android shell UID 2000. MagicDesk does not synthesize Console Mode by
writing `app_mirror_status` or `app_mirror_displayid`.

On `Win+D`, MagicDesk:

1. Validates that the display recorded by Nubia still exists.
2. Finds the active physical external display when necessary.
3. When the firmware explicitly reports Mirror Mode and only Android Home is
   visible on the phone, briefly opens an opaque black seed task. Nubia silently
   ignores the expanded-mode command from that Mirror state without an ordinary
   foreground task. The seed hides the vendor's intermediate portrait layout
   and is removed after the desktop HOME task is ready.
4. Requests expanded mode and waits for a real Console display.
5. Corrects portrait display geometry if required and applies the saved
   EDID-specific DPI before creating desktop UI.
6. Audits the existing MagicDesk task.
7. Creates `DesktopActivity` directly as a fullscreen HOME task, or recreates
   only a MagicDesk task that Nubia converted to ordinary freeform. An
   already-approved Root or Shizuku session is not routed back through Device
   Setup.
8. Focuses an already-correct desktop task without recreation.
9. Restores the saved visible freeform window layout.

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
MagicDesk calls `setSFOption(1102, 0)` through a short-lived privileged helper.

This SurfaceFlinger option is Root-only. Root reads
NubiaProjectionScreen's `PRIVATE_MODE_WIRED` preference before changing it,
records the value, and restores the latest Nubia preference or the recorded
value when the provider is temporarily unavailable. A clean Shizuku session
does not touch this option and does not claim native caption support.

### Teardown

**Switch to screen mirroring**, physical display removal, and explicit exit
share one cleanup path:

- remove display-scoped overlays and task captions
- stop the Console task watcher
- release keyboard and mouse display-port associations
- restore the physical right-button keymap
- restart the input bridge in shortcut-only mode
- restore Nubia components temporarily changed by MagicDesk
- wake the phone screen

A `DisplayManager.DisplayListener` validates the actual display lifecycle
instead of trusting only Nubia's global settings.

When Nubia removes the virtual Console display, the firmware may reparent
`SecondaryDisplayLauncher` or the MagicDesk desktop task to display 0 and leave
the phone in landscape. MagicDesk restores `QuickstepLauncher`
after a confirmed Console-to-mirror transition. Root and Shizuku first inspect
the exact phone task; Basic uses the confirmed transition itself because it
cannot inspect system tasks. The privileged check uses the visible
`topActivity`, not only the task's base component: Nubia keeps both launcher
tasks marked visible inside the same Home root task after recovery.

## Window And Task Management

Android does not expose `DesktopTasksController` as a public application API.
When Root provisioning disables desktop-mode device restrictions, this
firmware provides a shell entry point:

```sh
cmd statusbar wmshell-passthrough desktopmode moveTaskToDesk <task-id>
```

MagicDesk probes the command before using it. Root uses this path for native
WMShell captions. A clean Shizuku installation keeps firmware device
restrictions enabled, so `DesktopTasksController` is not initialized and the
command is unavailable. In that case MagicDesk submits the required
`WindowContainerTransaction` directly through its UID-2000 UserService.

A new application starts on the Console display through a short-lived
transparent dispatcher. The dispatcher immediately closes;
`DesktopTaskController` waits until ActivityTaskManager reports the target
task, then selects the probed WMShell path or the direct transaction path.
Existing tasks are moved and reused without relaunching their activities.

The system `ShellTaskOrganizer` remains the only organizer.
`DesktopTaskRepository` owns active, visible, minimized, and Z-ordered desktop
tasks. In Root-provisioned mode `DesktopModeWindowDecorViewModel` owns native
captions and resize behavior. Clean Shizuku mode intentionally has no
replacement caption overlay; window actions remain available from MagicDesk's
taskbar and shortcuts.

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

### Window Layout And Taskbar

The taskbar is built from real Android tasks plus persisted pins. Multiple
tasks from one package remain independent. Restoring a window layout reuses live
tasks, applies saved bounds, then focuses them bottom-to-top to reconstruct
Z-order. Closed tasks are skipped. A task explicitly promoted to fullscreen
is not forced back into the saved freeform layout.

Show Desktop stores exact task ids, bounds, and top-first ordering. It does not
close or force-stop application processes.

The application context menu can move an existing privileged task between
display 0 and the currently active Console display through
ActivityTaskManager. This preserves the Activity instance and avoids
the REDMAGIC behavior where a public cross-display relaunch kills the old
process.

## Audio And REDMAGIC Hardware

Tools uses public `AudioManager` APIs for media volume, mute state, and
connected output reporting. Android does not expose a reliable global route
selector to a normal application, so MagicDesk opens the system sound settings
for route changes instead of presenting a selector that may affect only its
own process. REDMAGIC Touch Panel remains the phone-side companion input
surface.

Root mode probes actual hardware nodes rather than accepting a model name as
proof of support. The verified REDMAGIC 11 Pro interfaces are:

```text
/sys/kernel/fan/fan_enable
/sys/kernel/fan/fan_speed_level
/sys/kernel/fan/fan_speed_count
/proc/driver/micropump/enable
/proc/driver/micropump/freq
/proc/driver/micropump/speed
```

One dedicated marker-delimited root shell reads all nodes and thermal zones in
a single polling command every four seconds while the manually started
MagicDesk runtime is alive. The parser accepts bounded numeric values,
classifies CPU/GPU/skin/battery sensors by thermal-zone type, and rejects
hardware trip thresholds, BCL levels, and invalid temperatures.

Before the first write to a subsystem, MagicDesk atomically records that
subsystem's current values and takes ownership of it independently. Changing
the fan therefore cannot later restore or overwrite a pump state that
MagicDesk never changed, and vice versa. Explicit **System** actions, normal
**Exit MagicDesk**, and runtime shutdown restore the owned baseline. Ownership
markers are persisted so the next manual start first restores state left by a
process crash. MagicDesk has no boot receiver and does not apply a hardware
profile merely because Android starts. Auto fan control is opt-in, uses levels
0 through 5 with temperature hysteresis, verifies the actual node state on
each poll, and restores the pre-control fan state when the runtime stops.

Bypass charging deliberately uses the stock REDMAGIC control plane instead of
writing charging sysfs nodes. MagicDesk writes only the firmware's
`Settings.Global.charge_separation_switch` through the selected Root or
Shizuku backend. It observes that key and `ACTION_BATTERY_CHANGED`, so the
Hardware switch follows changes made by the stock app, power disconnects, and
vendor safety policy without polling. Enabling is offered only while external
power is connected and the battery is at least 20 percent. Nubia's system
service remains responsible for notifications and automatic shutdown.

## Task Observation

A root `TaskStackListener` helper receives lifecycle events from
ActivityTaskManager for the display that currently owns the desktop. After a
short debounce it reads one task snapshot. This
drives:

- taskbar state
- exact-task focus
- window-layout persistence
- fullscreen taskbar visibility
- native maximize correction
- global task shortcuts

The helper is tied to the foreground service through stdin and exits when that
service stops.

`TaskStackListener` does not report changes to a task's
`requestedVisibleTypes`. While a non-MagicDesk application is visible, the same
helper checks only that task among the upper desktop tasks every 150 ms.
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

Start separates application navigation from operational controls. `Tools`
contains display density, runtime state, diagnostics, and session actions,
including the Nubia touchpad entry point. `Hardware` contains
battery state, bypass charging, audio routing, and REDMAGIC fan/pump monitoring
and controls. The compact battery percentage in the taskbar opens the Hardware
tab; detailed temperature and RPM values remain in that panel.

Start uses a focusable overlay for its application search and requests the
search field only after the overlay receives window focus. Tools remains
non-focusable so opening a command panel does not take keyboard focus from the
active application. Only one auxiliary panel stays open, and clicking empty
desktop/taskbar space or pressing Escape dismisses it.

## Physical Keyboard Architecture

Nubia normally reinjects external-display key events with
`POLICY_FLAG_DISABLE_KEY_REPEAT`. MagicDesk's root input bridge associates
external alphabetic keyboards with the physical external-display port and
keeps Nubia's input-panel Binder token alive. The vendor framework then
preserves original key events and Android's normal repeat behavior.

Physical keyboard layout switching is independent of the selected on-screen
IME. `Ctrl+Space` invokes Android's configured physical-layout cycle, and the
taskbar label is derived from the active layout locale. MagicDesk does not read
or change the selected input method. Layout discovery mirrors Android's
`KeyboardLayoutManager`: MagicDesk asks `IInputManager` for the selected
physical layout of every enabled IME subtype and de-duplicates the resulting
descriptors in system order. This avoids direct access to
`/data/system/input-manager-state.xml` and lets both Root and Shizuku shell
sessions use the same Binder implementation.

In Shizuku Console Mode, `libmagicdesk_keyboard_bridge.so` opens every physical
alphabetic keyboard read-only, creates one `/dev/uinput` keyboard with the
source identity and a stable input-port location, then waits. A separate
`app_process` routing session associates both physical and virtual keyboard
ports with the external display, holds Nubia's input-panel Binder token, and
selects the vendor mouse input source. Only after EventHub reports the virtual
device, routing succeeds, and Android applies the selected layout to it does
the helper acquire `EVIOCGRAB`.

The helper forwards ordinary events unchanged, including repeat and modifier
combinations. It consumes MagicDesk shortcuts before Android's global gesture
handling. During `Ctrl+Space`, events arriving while the Binder layout update
runs are queued and released only after the new layout is active; this avoids
the first character using the previous language. The unmodified Meta key is
suppressed, while `Win+L` stays on the normal Android path because phone locking
is outside the Shizuku capability boundary. Outside Console Mode, Shizuku keeps
the read-only `getevent` layout shortcut without grabbing the keyboard.

Both Shizuku processes require a heartbeat. Closing either stream, losing
Shizuku, or stopping MagicDesk releases every grabbed source, destroys the
virtual keyboard, removes input-port associations, and clears the vendor
routing state within six seconds.

The global shortcut watcher handles desktop operations while another
application owns focus. Shortcuts operate on exact task ids and the current
Console display.

### Alt+Tab Firmware Failure

Android 16's `KeyGestureController` recognizes physical `Alt+Tab` before the
focused application and asks the system launcher to show Recents. On the
verified REDMAGIC firmware, Quickstep's `DesktopTaskView.bind()` creates a
desktop task container without a title view, while the inherited
`TaskView.setThumbnailOrientation()` path still dereferences that view. Opening
Recents with Console freeform tasks can therefore crash
`com.zte.mifavor.launcher`.

MagicDesk avoids only that gesture. While the root Console input bridge is
active, the native helper maps standard HID Tab usage `0x0007002b` from Linux
`KEY_TAB` to `KEY_UNKNOWN` with `EVIOCSKEYCODE_V2`. Android InputReader ignores
the remapped key, so `KeyGestureController` cannot open the broken Recents
implementation. The bridge reads that one usage from the physical `evdev`
node:

- with Alt held, it advances MagicDesk's exact-task switcher and commits when
  Alt is released;
- without Alt, it injects a standard `KEYCODE_TAB` into the Console display,
  preserving Shift, Ctrl, Meta, and hardware repeat.

The bridge does not grab or proxy the keyboard. Every key except the single Tab
usage stays on Android's normal path. On cleanup, Tab is restored to
`KEY_TAB`, the logical `InputDevice` is refreshed, and the display-port
association is removed. A setup failure rolls the mapping back immediately;
physical hot-plug restarts the bridge for the new event node.

Do not replace this path with an Accessibility key filter. Physical keys routed
through Nubia's private Console display do not reach a third-party
`AccessibilityService` reliably. SystemUI's Recents disable flag affects the
phone display rather than this Console gesture, and changing launcher
AConfig/`device_config` feature flags at runtime can trigger a userspace reboot
on this firmware.

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

That is the Root implementation. Shizuku cannot write `EVIOCSKEYCODE_V2` or
create the display-scoped `InputMonitor`, so it uses the fail-open virtual
pointer path described in **Keep The Root Mouse Path Minimal** above.

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
Shizuku UserService and dispatches finite shell commands through AIDL.
Lifecycle-bound `ParcelFileDescriptor` streams carry task events and control
the fail-open physical-input helpers. The task stream is bidirectional:
MagicDesk writes focus/watch commands back to the same child helper through the
UserService. Streams and their remote processes are closed together when their
watcher stops. Shizuku mode does not fall back when the server or permission is
unavailable and never starts the root input helpers.

After explicit confirmation Root applies only missing values:

```sh
su -c 'settings put global enable_freeform_support 1'
su -c 'settings put global force_resizable_activities 1'
su -c 'setprop persist.wm.debug.desktop_mode_enforce_device_restrictions false'
su -c 'setprop persist.wm.debug.desktop_use_rounded_corners false'
```

The first two correspond to Android's **Enable freeform windows** and **Force
activities to be resizable** developer options. MagicDesk does not enable the
Developer Options master switch.

Shizuku applies and records only those first two global settings through shell
`WRITE_SECURE_SETTINGS`. It cannot modify either persistent property. This is
the intentional clean Shizuku profile: direct task transactions are available,
while WMShell native captions are not.

No other `persist.wm.debug.desktop_*` value is currently required. MagicDesk
does not force desktop mode on every freeform display, enable cross-display
window dragging, override density globally, change maximum task count, or
replace Android's resize animation.

WMShell and ActivityTaskManager cache these values. Device Setup records the
boot id and requires a real reboot after a change. Root verification:

```sh
su -c 'settings get global enable_freeform_support'
su -c 'settings get global force_resizable_activities'
su -c 'getprop persist.wm.debug.desktop_mode_enforce_device_restrictions'
su -c 'getprop persist.wm.debug.desktop_use_rounded_corners'
```

Expected Root values are `1`, `1`, `false`, and `false`. Expected clean
Shizuku values are `1`, `1`, `true`, and `true`.

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

On conventional Linux, Gradle compiles the arm64 input helpers with the Android
NDK. On Termux it uses `$PREFIX/bin/clang`. The outputs are generated under the
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
