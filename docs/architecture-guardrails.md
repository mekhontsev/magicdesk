# Architecture Guardrails

[Architecture overview](architecture.md) | [Documentation](README.md)

Review constraints for shared Android behavior. Capability and firmware evidence
remain in [compatibility](compatibility.md) and the
[vendor audit](nubia-vendor-audit.md).

- [Keep physical input independent of the IME](#keep-physical-input-independent-of-the-ime)
- [Keep task transitions at the framework boundary](#keep-task-transitions-at-the-framework-boundary)
- [Route devices through Android](#route-devices-through-android)
- [Keep vendor input APIs behind a capability boundary](#keep-vendor-input-apis-behind-a-capability-boundary)
- [Do not draw replacement application captions](#do-not-draw-replacement-application-captions)
- [Native desktop decorations](#native-desktop-decorations)
- [Do not recreate application tasks through display 0](#do-not-recreate-application-tasks-through-display-0)
- [Use one fullscreen topology on every desktop](#use-one-fullscreen-topology-on-every-desktop)

## Keep physical input independent of the IME

Do not select an IME, hardcode Gboard or a project-specific keyboard, or enable
shortcuts only while a particular IME is active. Android owns physical event
delivery, repeat, modifiers and keyboard layouts. The key-only
`DesktopShortcutService` handles desktop combinations before system policy;
it never requests accessibility window content or editor text.

## Keep task transitions at the framework boundary

Window commands address exact Android task IDs through the shared transition
gateway. Framework adapters select available WMShell, ActivityTaskManager and
WindowOrganizer operations. UI and platform extensions do not implement their
own task transitions or package-specific launch policy.

## Route devices through Android

`DisplayInputSession` serializes ownership on one worker. Physical keyboards
and mice remain Android InputReader devices; MagicDesk does not read or forward
their event streams. `DisplayInputRoutingSession`, hosted by the privileged service, binds
their input locations to the selected display's stable unique ID through
`FrameworkInputRoutingApi`. It selects the Android 14 association signatures or
the Android 15+ port-specific names. On API 34, the framework adapter also
checks Android's global pointer target before acquiring associations. A matching
port map with a pointer controller on another display is not ready input.
Before the first mouse exists, the system's requested pointer target is checked;
the device-added refresh verifies the instantiated controller as well.

Android 14 external pointer preparation uses the existing **System desktop mode
on external displays** setting, with explicit user confirmation. It may require
reconnecting the display. Android selects one pointer display, so enabling the
option does not guarantee selection of an arbitrary connected screen. Acquisition
never changes this global setting, display geometry, HOME or Desktop state.

`DisplayInputTarget` separates Desktop preparation from manual input selection.
Desktop claims input only after preparation. Manual control can select an
ordinary display or release input without closing applications or Desktop.
Closing Desktop releases its own selected input, not a later manual selection
on another display. Removing a controlled display releases input first. Runtime
state publishes requested and ready display IDs, transition state and errors;
request acceptance does not imply readiness. The control panel marks only the
ready target as controlled. An explicit selection can retry a failed acquisition
of the same target; routine reconciliation does not retry it in a loop.

A composite keyboard/mouse sharing one location receives one association.
`InputRoutingLease` journals previous runtime port and unique-ID associations
before changing either map. It restores only values still owned by the session,
preserves concurrent foreign assignments, and refuses to override static routes.
The durable journal is boot-scoped because Android runtime associations do not
survive reboot. Binder owner death releases routes; interrupted cleanup remains
retryable. Unknown or incomplete input inventory is an error, not an empty list.

Existing input-device callbacks reconcile hot-plugged locations. There is no
periodic input inventory query. While input is explicitly acquired, a key-only
Accessibility service receives confirmed routed keyboard and mouse IDs, observed through
device-generation callbacks. It materializes the app's input-device inventory
after each callback so Android continues publishing generation changes. The
privileged routing adapter reads devices directly from InputManager's Binder:
the app callback can precede invalidation of the privileged process's separate
device cache. Shortcut eligibility must not retain that stale association.
This adds no polling or per-key Binder query. Outside a prepared Desktop it handles display
switching only; Android retains application shortcuts, including Alt+Tab and
Meta. With Desktop it also consumes MagicDesk combinations through the same
`DesktopOperations` and task-controller gateways as the UI. Recorded tiling
shortcuts apply only while tiling is enabled on that Desktop. Programmable
mouse buttons can use them if they send keyboard events. Raw pointer events
are not intercepted. Other keys pass through to Android. Service enablement
has its own shell-owned journal and preserves other Accessibility services.

Host registration alone does not start input. Preparation is published after
HOME draws, workspace ownership is configured and parked-task restoration
finishes. Routes are acquired first; the phone pointer's location can be
associated before its virtual device exists. `DesktopMouseBridge` then creates
one virtual relative mouse for the phone touchpad on external displays.
Ordinary display control uses the same route acquisition and pointer lifecycle,
without enabling Desktop shortcuts or acquiring HOME.
The native helper queries `UI_GET_VERSION`: protocol 5 uses `UI_DEV_SETUP`,
while protocol 4 writes a `uinput_user_dev` descriptor. Both create the same
relative mouse and use the same event stream. Selection depends on the kernel
interface, not the Android release, vendor or service UID; setup errors remain
errors rather than triggering another creation path.

Close invalidates input readiness before queuing teardown. The same worker
finishes any in-flight acquisition, destroys the phone pointer and restores
shortcut enablement and device associations before display removal. A stale
start completion cannot reopen input. Each session creates a fresh virtual
mouse; hardware mice keep their Android identities throughout.

MagicDesk uses one phone-side `MagicDeskTouchpadActivity` for every external
transport. `TouchpadPointerMotion` converts successive finger coordinates into
relative deltas. The shared native mouse relay forwards these deltas and button
state through its virtual pointer; Android owns pointer acceleration, cursor
visibility, hover shape, and window dragging. There is no additional motion
smoothing or acceleration loop. Explicit automation injects display-targeted
Android mouse events, separately from optional cursor observation.

A long press remains undecided until the finger either moves or is released.
Movement starts a primary-button drag; release without movement becomes a
secondary click. Two-finger movement scrolls, while a stationary two-finger
tap also becomes a secondary click. These decisions stay in the phone UI;
display-targeted event injection stays inside the shell UserService.

The user's Android IME connects directly to the focused display editor through
its normal `InputConnection`. `DisplayImePolicyController`, through the lazily
resolved `FrameworkDisplayImeApi`, temporarily applies
Android's fallback-to-default-display policy on controlled external displays by
default. The shared `keyboardOnAppDisplay` preference selects Android's local IME
policy instead, so the installed keyboard appears beside the editor on the
controlled display (including a portable workspace's logical source display).
Settings' **Display input** section and the taskbar context menu expose the same
live switch. Settings availability requires shell access, not Desktop. It does not
select an IME, relay text, or restart pointer, device-route or shortcut ownership.
`DisplayInputRoutingSession` owns the policy together with device routes:
release, close, and owner Binder death restore the previous policy if it still
has our value. Repeated configuration of the same display does not query or
write the policy. A live change retains the original policy for release, including
an unacknowledged write. Display-0 policy remains unchanged. IME placement is an
optional operation after route acquisition on every supported Android release.
A failed initial or live policy change reports `keyboardPlacementError` separately
from routing readiness and leaves physical input and the phone touchpad active.
Control panel and compatibility diagnostics expose that warning; a successful
policy change or input release clears it.
Settings refresh completes after the input worker finishes applying the policy,
not when the change is queued. Taskbar checkboxes dismiss their menu only after
that completion; stale callbacks cannot dismiss a replacement menu. Context
menus, including taskbar settings, retain their IME-focus exclusion. Pointer
opening does not request keyboard focus; explicit keyboard entry points do.
The desktop and taskbar base windows remain non-focusable. Android owns the
existing editor connection; changing display policy alone does not restart it
or guarantee immediate relocation of an already connected IME.

The phone touchpad is an ordinary Activity with a non-focusable attached
`PopupWindow` containing its controls and touch surface. Android's
`INPUT_METHOD_NEEDED` mode allows the window to coexist with the phone IME.
Touchpad motion and clicks do not take editor focus from the external
application. System and IME insets constrain the usable touch surface. The
popup is attached only while the Activity is started and is dismissed on stop;
it cannot remain over another phone application. The Activity itself retains
normal focus and Back handling when no external editor is active.

The touchpad's keyboard button explicitly requests the IME for the
focused application window on the controlled display, including a focused child
dialog. `DisplayInputSession` scopes the request to its current routing lease;
`FrameworkInputWindowObservationSource` obtains a request-scoped input-window
snapshot, and `FrameworkInputMethodApi` asks that window to show its IME through
Android's editor connection. The touchpad does not acquire editor focus or relay
text. Keyboard placement remains governed by `keyboardOnAppDisplay`. This path
requires shared display input, not a Desktop session, and a successful request
does not guarantee that an application without an editor will show a keyboard.

Start initially focuses its search for hardware input without requesting the
software keyboard. Clicking the search field enables and explicitly requests
the IME. Other applications use their own editor's native show/hide behavior.
The chrome task permits focus only while a focusable panel or dialog is
requested; its transparent base window and taskbar remain non-focusable.
`DesktopPanelWindowController` acquires task focusability before attaching the
panel and releases it on dismissal, failed attachment, or host teardown.
Acknowledgements from a closed panel cannot attach a replacement. The existing
task command queue orders release before a following application launch; no
worker, polling loop, or UI-thread Binder wait is added. Leaving the task
permanently focusable lets its always-on-top priority block application focus
even with no focused child window. Conversely, making the
whole task non-focusable rejects a child panel's input connection even when
that child receives ordinary hardware key events.

No editor text is captured or relayed by MagicDesk. Composing text, selection,
deletion, editor actions, and Back-to-dismiss remain Android IME operations.
There is no extra keyboard, polling loop, or software-keyboard selection.

A completed primary click or tap on empty desktop wallpaper requests dismissal
of the current IME, wherever it is shown. It does not change editor focus,
desktop focusability, window order, or display input routing. Long presses and
secondary clicks retain context-menu behavior. Empty grid cells pass gestures
to the wallpaper parent, which owns selection clearing and keyboard dismissal;
individual icons and widgets retain their own input handlers. `DesktopInputController` submits
an asynchronous shell Binder request; the lazily resolved `FrameworkInputMethodApi`
uses Android's `IStatusBarService.hideCurrentInputMethodForBubbles` operation.
Its originating display supplies Android user context, not editor-display
isolation. No Back key, focus pulse, visibility polling, or IME-policy change is
used. An unavailable API or denied request is logged without changing the
workspace or adding a startup prerequisite.

While an external input display is selected, the runtime temporarily enables Android's
`show_ime_with_hard_keyboard` setting so the user can explicitly open the
software keyboard even when a physical keyboard is connected. It remembers the
previous value and restores it when external input is released; no persistent
keyboard preference is imposed during setup.

## Keep vendor input APIs behind a capability boundary

MagicDesk does not package or link a Nubia binary library. The vendor surface
used for cursor diagnostics is the private Binder method
`IInputManager.getMousePosition` on RedMagic firmware.

The signature is resolved reflectively inside the shell UserService.
`PointerPosition` retains the source's display identity, or -1 when unknown.
Nubia's API exposes global coordinates without a display identity: these appear
as an unscoped observation, never as the requested desktop's position.
Display-targeted hover and positioned clicks use `DesktopPointerInjector` on
all platforms. They inject mouse events but do not reposition the hardware
cursor. Phone touchpad movement remains relative native input.
A missing optional package or method disables
the corresponding operation rather than changing unrelated device state. In
contrast, `libmagicdesk_uinput_bridge.so` is a MagicDesk-owned virtual mouse
helper compiled from repository C source by every local and CI build.
Its bounded stdin protocol carries relative motion, buttons, scrolling and
on-demand aggregate counters. EOF releases held buttons and destroys the
virtual device. Physical device recovery remains Android's responsibility.

## Do not draw replacement application captions

An application overlay cannot share a task's SurfaceControl leash or transition
atomically with WMShell. A separately drawn caption trails live movement,
maintains a different Z-order, and can leave controls above the wrong window.

MagicDesk instead keeps native WMShell captions visible. One persistent,
transparent `DesktopChromeActivity` supplies the application token for the
taskbar, Start, context menus, notification center, and desktop dialogs. The
shell launches this STANDARD host in a root-level organizer area, a sibling of
the standard task workspace. Both the area and its task use `MULTI_WINDOW` and
`alwaysOnTop`; the task is non-floating and normally non-focusable, with empty bounds
that fill the area. The shell also disables the
ActivityRecord input sink.
All visible chrome is an ordinary bounded `TYPE_APPLICATION_PANEL` child
window, so empty parts of the display-sized host neither draw nor consume input.
The shell excludes the host's caption inset. Its exported component is
protected by the framework
`MANAGE_ACTIVITY_TASKS` permission, so only the authorized shell runtime can
create it.

Custom caption controls require WMShell to preserve the application's
display-specific gesture-exclusion regions. Shell privilege does not give
MagicDesk ownership of SystemUI's input-window tokens. Replaying a caption
click as a synthetic touch is not equivalent to delivering its original mouse
stream. Current validation gaps are recorded in
[Compatibility](compatibility.md#known-limitations).

## Native desktop decorations

Native freeform captions and the fullscreen App Handle can be supplied by one
firmware decoration module. Disabling that module to remove the handle can
also remove freeform controls. MagicDesk preserves native decoration ownership;
it does not restart SystemUI at session boundaries or draw substitute captions.
Device Setup can explicitly toggle the platform's device-restriction property
with a confirmed SystemUI restart. Its dialog explains both effects; there is no
independent promise to hide the App Handle while retaining native captions.

## Do not recreate application tasks through display 0

Do not use the phone display as a window-mode trampoline, force-stop a target
application, or add guessed sleeps to refresh fullscreen geometry. Those paths
can destroy an Activity and its user session. Use same-display transactions
and the client-preserving refresh described in
[Fullscreen transitions](fullscreen-transitions.md).

## Use one fullscreen topology on every desktop

Every configured target uses the same independent topology. The HOME host and
freeform applications remain in the display's ordinary root workspace.
Every fullscreen application is placed in its own organizer-created ordering
plane and retains that plane for its complete fullscreen residency, so focus
never reparents it during a fullscreen peer switch. The topology does not
branch on display kind or vendor.

Each plane retains an empty, non-focusable separator Task in the ordinary
workspace as well as its internal anchor. The separator has no Activity or
Recents entry and leaves WMShell task organization intact. The activity-start controller restores
safe Task/TDA adjacency before a launch, preserving application order and using
a callback-safe immutable ownership snapshot. Separators follow plane lifetime,
including idle reuse and display-loss cleanup; see
[fullscreen launch boundaries](fullscreen-transitions.md#fullscreen-transactions).

Native freeform-to-fullscreen events are adopted by the existing shell plane
owner without waiting for a user selection command. After the framework's
transition barrier, the live root is replaced at its current workspace
position; task mode, bounds, Activity identity and foreground selection remain
unchanged. Ordinary phone tasks are not adopted. Stale mode events release the
unused reservation, and restoration still accepts a desktop-owned native root
that has not acquired a plane.

On display 0 MagicDesk is the active HOME surface. Android's WMShell normally
starts `DesktopWallpaperActivity` above Launcher when its freeform mode is
used; that fullscreen activity exists specifically to hide Launcher. While a
phone desktop is active, the shell activity-start policy blocks only that
exact SystemUI component and removes one instance that may predate observer
configuration. Freeform tasks otherwise remain ordinary Android tasks in the
default root workspace, and the taskbar retains its own bounded plane.

Taskbar, task overview, MCP, and Alt+Tab submit the semantic target to the same
`DesktopTaskController` focus gateway. The shell resolves the complete live
fullscreen set, creates a plane for any fullscreen task not yet represented,
and atomically orders the selected task and its existing plane.
Steady-state switches do not change task mode, bounds, parent, or hidden state.
UI and automation controllers never construct their own fullscreen stack or
transition sequence.

The process boundary is `DesktopWorkspaceCommand`, with distinct activate,
demote, bare-desktop presentation, desktop-workspace presentation, workspace
restore, and session restore operations. Activation carries exactly one target
task; workspace operations carry a named back-to-front plan. A multi-task
activation is rejected, not interpreted as a workspace restore.
`DesktopWorkspaceQueue` orders complete user intents on the controller Handler,
including their live snapshot, toggle decision, host-input preparation, and
shell acknowledgement, including Show/Restore. Requested
focus is kept separate from observed focus; the next click cannot use the
uncommitted target of its predecessor. Stop cancels the session's outstanding
intents without admitting late acknowledgements into the next session.
`ShellDesktopWorkspaceCoordinator` serializes the commands for the configured
display and is the sole adapter from logical workspace intent to
`ShellFullscreenTaskArea` and ordinary task ordering. The application retains
UX intent such as taskbar concealment and persisted restore state; shell owns
the live organizer topology and completes mixed fullscreen/freeform ordering
from framework task state.

Task selection has two explicit z-order operations:

- **Activate** brings a selected task to the front of its compatible desktop
  hierarchy. The task is effective foreground only when it is visible,
  focused, and has no managed application above it. Alt+Tab, task overview,
  taskbar selection of a background or covered task, and MCP focus all request
  this operation through the common gateway.
- **Demote** rotates the currently active task behind the next MRU application
  without minimizing or hiding it. Taskbar selection of the already-active
  task requests this operation. With no application peer, the desktop host is
  brought forward and the application remains live underneath it.

Occlusion is not minimization. A fullscreen task covered by another fullscreen
task or a freeform window remains fullscreen. Activating it moves the blockers
below its stable plane while preserving their mutual order; demoting it reveals
that previous stack without a repair transition. Activating a freeform task
places it above the current fullscreen plane without raising peers covered by
that plane. The shell derives exposed freeforms from typed root hierarchy
order, stopping at the first fullscreen application or HOME rather than using
task-local `visible` flags across independent areas. UI panels send only the
selected task ID; their captured launch context never becomes a focus plan.
Neither operation changes task
mode, bounds, parent, or hidden state.

`ShellFullscreenTaskArea` and
`ShellFullscreenTaskPlanes` own plane creation, ordering, restore, and removal.
Each plane ignores child orientation requests: the desktop session owns the
viewport orientation, while Android may rotate or letterbox application content
inside the fixed fullscreen plane.
No delayed mode repair or fixed post-transition delay is involved. On affected
external firmware,
workspace command completion captures a task-sample generation and a
SurfaceFlinger input-window generation before submission. It then waits for
both event sources and performs one InputDispatcher check. A missing input
target gets one ownership-aware repair followed by one more event-driven
commit confirmation.

Application-driven restores are completed by the observer before their result
crosses Binder. A fullscreen task leaves its plane through
ActivityTaskManager's existing-task freeform launch path. Every plane contains
one retained standard anchor task, which keeps the source hierarchy valid while
framework root selection moves the application task. The now-idle plane is
made non-focusable and reused by a later fullscreen task, avoiding repeated
organizer creation and deletion. A new anchor launches behind the foreground
task; its structural opening therefore cannot race the application's
fullscreen entry or acquire user focus. Session teardown deletes the owned
planes and their anchors. If Android removes a display first and migrates an
anchor to the phone, the plane owner removes that exact task by saved ID and
component.
