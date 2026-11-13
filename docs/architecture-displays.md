# Displays And Desktop Sessions

[Architecture overview](architecture.md) | [Documentation](README.md)

Display resources, viewers and Desktop sessions have independent lifetimes. This reference describes their coordination and transport-specific behavior.

- [Display And Session Model](#display-and-session-model)
- [Display Presentations](#display-presentations)
- [HOME Lease](#home-lease)
- [Display Drivers And Preparation](#display-drivers-and-preparation)
- [Phone HOME And Recovery](#phone-home-and-recovery)
- [Display Profiles](#display-profiles)
- [External Desktop Activation](#external-desktop-activation)
- [Output timing](#output-timing)
- [Caption visibility](#caption-visibility)
- [Teardown](#teardown)

## Display And Session Model

`DesktopDisplayCatalog` chooses the privileged inventory when connected and
`ApplicationDisplayCatalog` otherwise, without starting a privilege transport.
The latter uses public DisplayManager APIs only. It retains connection-scoped
addresses invalidated by display removal and process exit; they are not persisted
as monitor profiles. Public inventory does not infer transport type, trust,
resource ownership or Desktop support. MCP reports the identity scope and uses
null for unavailable profile/built-in metadata. Start does not hide a usable
destination merely because its connection type is unknown. Rejected privileged
catalog reads do not silently fall back to a less complete inventory.

`DisplayNames` resolves presentation labels from Android's public
`DeviceProductInfo`, falling back to `Display.getName()` when the product name
is absent or blank. The shared
catalog, UI, automation and diagnostics use this policy; `systemName` remains
separate so product metadata never changes display identity or profile keys,
including the name-based fallback when no unique ID is available.

A `SessionProfile` stores only a display selection policy. Runtime display IDs
are never persisted as constants.

`DesktopDisplayTarget` binds a logical task workspace (`workspaceDisplayId`) to
a `DesktopDisplayOutput`. Output kind, output display ID, activation source and
the saved profile key belong to the output. These roles are distinct even though
the production presenter currently requires a direct binding: workspace and
output are the same Android display. Representing a different binding does not
enable output switching; startup rejects it before display setup or HOME changes.

`DesktopSessionRegistry` admits one workspace per logical display. Phone and
external workspaces can coexist. `DesktopWorkspaceSnapshot` publishes its UUID,
binding and registered host atomically. `DesktopWorkspaceRuntime` owns that
residency: Activity recreation retains its identity; Close invalidates it, and
a later start creates a new identity even on the same Android display.
`DesktopSessionSnapshot` adds the residency's policy. Isolated self-tests cannot
join user workspaces or another test workspace.

Start, Alt+Tab, settings and workspace presentation carry a display address from
their command boundary through the runtime facade to the UI gateway. Global
shortcuts resolve the independently selected input display at that boundary.
Deferred host actions remain bound to their original Activity.
`RuntimeDesktopTaskCoordinator` owns one controller and observation subscription
per workspace. `FrameworkTaskObservationSource` uses one shared scheduler for
the existing 150 ms reconciliation fallback. Policy consumers add no polling.
`ShellActivityStartController` multiplexes Android's global controller slot;
the last client releases it. `ShellWorkspaceMembership` prevents external
phone-task normalization from touching a live phone Desktop, and appoints only
one external observer to normalize an ordinary phone workspace.
Release validates the workspace identity before removing its observer.
Window-state finalization uses the same identity, so a delayed Close cannot end
a replacement's state session. Retained-window restoration is initiated once
by the workspace coordinator; each restoration batch belongs to its destination.
Host registration, task placement, window geometry, task density, capture and
input routing address the workspace. Output mode preparation, transport caption
policy and monitor-profile selection address the output. A profile still stores
UI density and output timing together, but their application has different
owners: density is applied to the workspace, physical timing to the output.
Android runtime display IDs are not physical compositor tokens. Display catalog
identity validation remains the resource boundary, including rediscovery after
an output mode change.

HOME lease state and the host Activity retain the complete immutable target,
not separate reconstructed copies of its fields. Intent and saved-instance state
use the same target Bundle codec. Matching a session checks both ends of the
binding; changing profile values does not change display identity. Phone HOME
selection follows task residency rather than the output transport. Input has
one independent selected destination. Starting a Desktop selects it only after
that workspace is prepared; closing another Desktop does not release or redirect
input. Teardown preserves virtual-mouse recreation and association restoration
ordering. Display windowing defaults are acquired and restored per display.

Display removal recovery addresses the lost workspace. With direct bindings,
unplugging the output also removes that workspace and retains the normal close
behavior for that workspace only. A direct physical workspace cannot be retained
by exchanging compositor tokens: that would not transfer Android's logical
display ownership. The optional virtual-first path below keeps residency on an
owned virtual display from the start, without changing ordinary startup.
Workspace-local release is not the whole Close operation: the outer session
coordinator still owns HOME handoff, input release, task return and final HOME
surface cleanup in their existing order. Closing the only workspace leaves
independent automation, file and terminal services available.

Owned virtual displays retain trusted, touch-capable, own-content and independent
power-group flags without requesting SystemUI decorations. Desktop starts its
selected host explicitly; system navigation is not a creation prerequisite. Native
window captions and display IME policy keep their existing owners. This applies
when the display is created, not when its viewer is detached or attached to
another output.
Android's optional forced external desktop mode or a system display override can
still enable system decorations; MagicDesk does not change those settings here.

Virtual-display creation optionally accepts `protectedContent`, off by default
and not inherited from creation preferences. The current privileged service must
hold `CAPTURE_SECURE_VIDEO_OUTPUT`; this is a permission query, not a vendor or
UID selector and never triggers per-operation elevation. Missing permission
rejects creation before allocating resources. Ordinary UID-2000 displays and
independent API-34 services retain their existing prerequisites.

`FrameworkVirtualDisplayApi` creates such a source with `SECURE` and a PRIVATE
ImageReader with GPU-sampled/protected usage. Detach returns to that same
protected sink; the callback only releases frames, never maps their pixels.
System overlay previews do not implement this contract and reject the option.
The live catalog distinguishes Android's `secure` output capability from the
`protectedContent` policy of an owned virtual source. `TRUSTED` and managed
Desktop eligibility remain independent.

## Display Presentations

`DesktopDisplayInfo.canHostDesktop` admits direct managed workspaces, not
presentation outputs. Android rejects organizer-created task areas on untrusted
displays even for privileged callers. Such a display can still receive an
ordinary Viewer through the privileged Activity launcher: a portable workspace
keeps its HOME, task areas and input on MagicDesk's trusted virtual source.
The catalog publishes `requiresPortableDesktop` separately from direct eligibility:
a known public external output lacking TRUSTED uses the portable launch path when
Start Desktop is requested. Private, unknown and unverified additional built-in
displays are not automatically admitted this way. Other direct-start failures
are reported without falling back to portable startup.

`DisplayPresentations` maps a live source display to an ordinary
`DisplayViewerActivity` on another live display. This is a separate presentation
edge, not a mutation of the workspace's immutable `DesktopDisplayTarget`, HOME
lease or task ownership. The existing direct target continues to describe the
Android display containing those tasks. A source need not host Desktop.
Output attachment placement is explicitly independent and fullscreen, even
when the output has a managed workspace. Reopening uses its exact Android
AppTask, never the output's Desktop focus gateway. Ordinary Display Viewer
applications instead use the shared Start/tool placement, including managed
windows or independent fullscreen. They can choose a source inside the window
or receive it through `open_builtin`'s `viewer` options. Mirror mode creates
another copy, including for owned virtual sources, without taking over an output
attachment. Output mode uses the existing independent-output transaction and
reuses that output's Viewer; it rejects managed placement. `immersive` controls
the toolbar and system bars, not task ownership. `AutomationToolWindows` adapts
these options to `DisplayPresentations.openViewer`; launch, binding readiness
and cleanup stay with the existing shared services. A supplied source completes
only after its Surface attaches, while an interactive selector retains ordinary
launch acceptance. No timeout cancels an accepted launch.
The shared `FrameworkActivityLaunchApi` supplies explicit empty launch bounds
with fullscreen mode for ordinary Activity, PendingIntent and existing-task
launches. Omitting bounds lets Android's launch-parameter modifier restore a
persisted freeform mode on a freeform-default display, overriding the requested
fullscreen mode. This is launch configuration, not a post-launch Viewer repair
or a change to Desktop task-area ownership.
The presentation snapshot includes the Viewer's Android task ID, populated by
its Activity, so automation closes it with the standard `close_task` operation.
Activity teardown releases the binding; there is no separate MCP attach/detach
protocol or CLI implementation. `select_display_viewer` remains the operation
for changing a live binding's source or returning to its previous source.

`DisplayPresentationMode` distinguishes direct presentation of a MagicDesk-owned
virtual source from mirroring an existing display, including an owned virtual
source. Every built-in panel is
addressed by its own display ID and unique ID; mirroring is not restricted to
display 0 and does not require that panel to support managed Desktop.
`DisplayPresentationSurface` owns only the presentation lifetime. Direct
presentation returns the source to its existing sink on close. The mirrored
implementation uses Android's `IWindowManager.mirrorDisplay` under
`READ_FRAME_BUFFER` through `FrameworkDisplayMirrorApi`; it attaches the copied
scene below the viewer's SurfaceView and releases only that copy. The mirror API
creates no additional display, changes no source power state and leaves
applications in place. Owned virtual sources additionally use the scoped power
lease described below. Missing framework support or permission fails only the
viewer operation.

`FrameworkVirtualDisplayApi.OwnedDisplay` uses `VirtualDisplay.setSurface` to
present to the viewer's `SurfaceView`. Its buffer retains the source dimensions;
the view fits it into the output window preserving aspect ratio. Android performs
composition and scaling, without frame copies, a Java rendering loop, vendor
display tokens or additional libraries. Letterbox margins do not accept source
input. A source change gets a new Surface consumer: returning from `setSurface`
does not certify that the previous compositor producer has disconnected.

For a protected virtual source the new SurfaceView is marked secure before
attachment. Both the presentation registry and the privileged lease owner
require a secure output. An incompatible source exchange is rejected before
either old lease is detached, including its peer's destination. No operation
silently downgrades protection. Ordinary mirrored displays keep Android's
per-layer protection semantics; a secure physical panel is not automatically
a protected-source policy. Protected decoder buffers and end-to-end DRM/HDCP
support still depend on the graphics stack, output and playing application;
the display flags are not playback certification.

`ShellDisplayViewer` is a revocable Binder lease, independent of the source's
resource owner. It serializes source-addressed input and releases held keys and
touch streams before detaching. The framework injection adapter is shared with
the existing test pointer injector. Detaching a direct output returns the virtual
display to its existing non-null ImageReader sink with unchanged task IDs,
logical dimensions, density and configuration. Every attached presentation of
an owned virtual source, direct or mirrored, shares its display-scoped Android
wake lock. The first acquires it; the last releases it and allows ordinary idle
sleep. Detaching a direct output therefore does not put a source to sleep while
a mirror still needs it. This does not change the phone's screen timeout or hold
unrelated displays awake. Closing a viewer or losing its output does not close
that source's Desktop or move applications.

The process-local registry admits one output attachment per source and output,
plus independent mirror windows, including several on the same output or source.
The privileged owner enforces one direct Surface consumer per virtual source.
Both boundaries validate the complete multi-edge presentation graph, including
implicit Android overlay previews. Selecting a source already shown by another
output attachment exchanges those two bindings. Ordinary mirror selection never
exchanges attachments, becomes a Show Desktop return destination, or changes
physical-input routing. Both old attachment leases are
released before either new attachment; completion includes acquired physical
input handoff. A hidden peer commits its logical binding without waiting for a
Surface; it remains unready until Android shows its window and attachment
succeeds. Hiding a participant releases its attachment barrier without raising
that window. Input follows its current output only if the user had selected
that source, and a later explicit input selection takes precedence. Merely
opening a viewer never claims physical devices. Touch/key events delivered to
the viewer are forwarded with the inverse presentation transform.

`DisplayInputRequests` belongs to the runtime input owner, not the Viewer
registry. Its cancellable requests are checked when queued input actually
executes. Detach cancels the viewer's pending acquisition and conditionally
releases its selected source without superseding a newer explicit selection.
Desktop preparation, release and runtime teardown invalidate obsolete requests.
`DisplayViewerConnection` observes Binder lease death and input failures with
the same binding generation used for attachment. A dead connection invalidates
readiness; selecting that source again uses the normal detach/rebind path.
Late failures from an old lease cannot invalidate its replacement.

Cycle validation includes Android overlay previews' implicit presentation on
the default display. A source geometry change is observed through the display
listener and reuses the binding transaction, updating aspect-fit and input
coordinates together. Refresh-rate and power notifications do not query the
privileged catalog when dimensions/density are unchanged. Source geometry
changes do not release and reacquire unchanged physical-input routing.

The viewer source menu and previous-source action share this registry.
For a successfully attached direct virtual output, its Recents entry represents
the source's Linux desktop. The shared task-metadata owner borrows the title/icon
only when the source has exactly one whole-desktop host; ambiguous sources keep
the display identity. Internal graphical hosts on that virtual source are
excluded from Recents through the public own-task API, not removed, moved or
finished. Their original flags survive Activity recreation and are restored
when they leave that source. Closing a Viewer retains this internal-source role
until display removal; ordinary phone and physical-display hosts are unchanged.

`Ctrl+Alt+Tab` opens a display switcher on the output currently showing the input
source, or on that display itself when there is no visible output attachment.
Repeated Tab cycles valid live sources, Shift reverses, releasing Alt confirms,
and Escape cancels. Selection does not change presentation, focus or input.
The non-focusable picker uses the existing Accessibility service's display-local
overlay, or a child popup in an ordinary Viewer. It requests neither window
content nor accessibility events. No Desktop host or task plane is created.
Names include IDs, Desktop state and a one-shot application count; unavailable
task observation remains unknown. Exact-identity, per-output MRU makes a quick
chord return to the previous screen. The output's **This display** entry closes
its output Viewer instead of creating a self-mirror.

Without a managed Desktop, `HostedKeyboardShortcuts` forwards modifier chords
and system keys from confirmed routed physical keyboards to the focused
whole-desktop X11/Wayland host. It checks typed task/profile/display identity at
chord admission, not on every key or through a periodic task observer. The same
borrowed output receives both presses and releases, including autorepeat and
reversed chords. Focus/output/route loss releases held guest keys and drains
the remaining consumed releases without sending them to a different application.
Ordinary unmodified typing remains Android input. Power/volume keys and the
shared display-switch gesture stay outside the guest; managed Desktop shortcuts
continue to use their existing task controller.

Taskbar **Switch display...** shares the same controller,
MRU and commit operation with the keyboard picker. Its clickable selector uses
the existing Desktop panel host without keyboard focus or IME targeting; it
needs no additional Accessibility overlay. Dismissal releases its display
listener and never commits the highlighted choice. The keyboard-only picker
retains its non-touchable overlay/Viewer popup.

`DisplaySwitchOperation` explicitly acquires input after the fullscreen
presentation is ready. Its binding transaction does not separately redirect
input. An input failure restores the earlier binding and input selection when
still owned; a later explicit selection wins. Failure is reported, not treated
as a successful image-only switch. A requested phone touchpad follows external
output switching without covering a Viewer on the phone itself. Ordinary Viewer
opening/selection keeps its existing conditional input policy and never acquires
devices just because a window opened. No task changes display ID, mode or owner.
The picker disappears before commit; the resulting Viewer has no toolbar or
frame. Ordinary application Alt+Tab is unchanged. Fullscreen hides viewer controls and requests
immersive system bars; Back returns to the controls before detaching the viewer.
Reattaching an existing output applies the requested fullscreen state too; Attach
waits for that output's attachment even if source selection committed while hidden.
An explicit **Show Desktop** returns the source's current or last Viewer to the
front and waits for Surface attachment before presenting the workspace. Each
successfully committed source remembers its last output for that Viewer's
lifetime, independently of the output's currently selected source and Back
history. Returning a replaced source selects it through the existing binding
transaction, including its conditional input handoff. The Viewer mode and
workspace identities stay unchanged. Active bindings take precedence over
remembered outputs; display unique IDs prevent reused IDs from inheriting them.
Explicit detach or output loss clears its remembered destinations. A workspace
without such an output does not create a Viewer implicitly. Internal session
recovery does not raise output windows, and a pending user return cannot activate
a replacement workspace after Close.
The display selector's **Show another display...** treats the selected row as
the output. Its source chooser excludes that output and lists other displays
with their name, ID and Desktop status. The existing attachment transaction opens
a fullscreen Viewer without controls or changes the source in that output's
existing Viewer; graph and protection checks stay with the presentation owner.
**Show another display...**, **Stop showing** and **Start portable desktop here**
are direct buttons in that same grid. **Stop showing** is visible only for an output with
an active Attach, resolved through `DisplayPresentations.forOutput`; its grid
slot remains reserved while hidden. It closes
that Viewer through the existing detach operation. It is not a source-display
action or a prerequisite for unplugging or switching sources.
**Display Viewer** is a normal built-in application
in Start, search and `open_builtin`; there is no duplicate display-menu launcher.
It chooses a live source by exact identity. Losing or detaching that source
returns the window to selection; moving the window rebinds its mirror on the new
output with cycle validation. Closing it releases only its own presentation.
Both **Show another display...** and **Start portable desktop here** use the same
attachment transaction with captured source/output identities, so detaching and
choosing a reconnected output
does not turn the portable Desktop into a windowed viewer. The choice belongs
to the command, not stored display metadata; input remains explicitly acquired.
`DesktopPresentationLauncher` first retains the output's existing Attach. Otherwise
it selects a MagicDesk-owned headless virtual source with the same current logical
resolution, no other output attachment, and compatible protection/cycle constraints.
The fewest managed application tasks wins, followed by matching profile origin and
ascending display ID. Infrastructure is excluded; unavailable membership is an
error, not zero. One shared task snapshot serves the selection, with no new observer
or polling. Existing source DPI, origin and tasks are unchanged; ordinary mirror
windows do not reserve the source. With no candidate, it creates a source using
the output's current resolution and explicit DPI preference, or the shared Desktop
density recommendation when no preference was saved. Advertised Cast density is
not an implicit Desktop preference. Reusing a source never recalculates its DPI.
The launcher runs normal Desktop startup or shows the existing workspace, then
attaches the output. Concurrent repeats for the same exact output join that launch;
another portable launch is rejected until completion. The presentation owner
revalidates exact identities and the expected output attachment before committing,
so a changed output is not overwritten. HOME acquisition and automatic phone UI finish
before this final presentation, so they cannot cover a viewer opened on the phone.
Provisioning retains the exact chosen
launch action. Failure retains the selected or created display and reports its identity.
The normal transition gate explicitly accepts or rejects startup. Driver results
propagate through it, so the portable launch callback reports the Desktop launch
result, not just a successfully attached viewer.

Owned virtual displays are named **MagicDesk** and distinguished by their display
IDs. The control panel's creation dialog initially selects **Default**, a snapshot
of the table's selected display's current resolution and configured DPI. Without
a selection it uses the saved creation parameters; scale remains editable.
Fixed resolutions and custom dimensions remain available. This choice does not
link the created display's configuration to later changes or loss of its output.

Attach connects an output to a source, not the source's lifetime to an output.
Detach changes only presentation; **Stop showing** acts on the selected output
for both direct virtual sources and mirrored sources. Neither action
disconnects Android's physical or wireless display transport. Close Desktop
and Remove Display remain separate commands. Reconnecting
an output requires selecting its current catalog identity and attaching a viewer
for the retained source; no numeric output ID is persisted or automatically
reused. Mirroring an existing screen does not substitute its physical panel's
contents: a window placed over the source also appears in its mirror. Arbitrary
phone/HDMI logical-display exchange and seamless native pointer
crossing between outputs are not implemented by this presentation path.

## HOME Lease

Starting the first desktop creates one persisted `DesktopHomeRoleLease`, owning
workspace membership, policy and the optional HOME selection. The
`requestHomeRole` setting defaults to true. Disabling it skips role queries,
component selection and role changes. A refused acquisition falls back only
after successful rollback. The persisted `homeRole` flag retains the selected
mode across additional workspaces and recovery; edits affect the next shared
session, after all current workspaces close.

Without HOME, `IndependentDesktopActivity` supplies the same `DesktopShellActivity`
UI in an explicit standard fullscreen task on either the phone or another display.
The common launcher verifies its type and destination, and the same workspace,
focus, input and close owners remain responsible. System Home/Recents and
secondary-HOME selection are untouched. Recovery and release do not restore or
present a launcher that the session never acquired.

With HOME, MagicDesk temporarily becomes the package-wide Android HOME holder
and remembers the previous role state plus complete target membership. Further
workspaces join without acquiring HOME again. Android may have a working HOME
surface while the role has no explicit holder; that empty state is valid and is restored by removing MagicDesk rather
than selecting a launcher on the user's behalf.
The same lease owns the `MAIN`/`SECONDARY_HOME` preferred Activity. Before enabling
its components it captures the resolved handler, or resolves Android's
`config_secondaryHomePackage` when there is no concrete selection. Activation
selects `DesktopActivity`; the last release restores the saved component, using
the same system fallback if that component is no longer available.
`FrameworkSecondaryHomeApi` encapsulates user-scoped PackageManager queries and
replacement of this exact Intent filter, without clearing a package's other
preferred activities or using persistent system-only preferences.
`DesktopHomeSurfaceRouter` atomically enables one primary HOME component,
`PhoneHomeActivity`, and one secondary HOME component, `DesktopActivity`, before
claiming the role. Both identities stay enabled until the last workspace closes.
Starting or closing the phone Desktop changes content, not the preferred HOME
component: replacing an enabled Activity can invalidate Android's preferred
resolution even while the package still holds the role.
Each host selects Desktop for a resident workspace or ordinary
`FullscreenStartController` content without Desktop preparation or task observation.
An empty selection enables no HOME surfaces. Android creates the primary host
in its standard task area; explicit external startup launches the secondary
host through the typed privileged task API.

`DesktopActivity` uses `singleTop`, allowing Android to create a display-local
secondary HOME instance. Android rejects `singleTask` and `singleInstance` for
secondary HOME and can redirect the launch to display 0 while returning a
successful start result. Session startup uses an explicit component Intent
without HOME categories and requests the HOME root type through ActivityOptions.
This keeps the selected display and root under the typed launch boundary for
both shell and root callers. System HOME/SECONDARY_HOME Intents are separate
entry points and delegate to the session's registered host on that display.
The session registry owns the active host identity, independently of manifest
launch-mode restrictions. Startup still verifies the actual display and selected
host task type (HOME or standard).

The lease is the only owner of HOME transitions and HOME-surface selection.
Closing one workspace retains HOME for the others. Closing the last workspace
quiesces its HOME entry points, restores both launcher selections, and retains existing
HOME surfaces through workspace teardown. It disables those components before
presenting the restored launcher; later cleanup failure never reclaims HOME.
A failed component selection retains the closing membership for recovery rather
than recreating a workspace that has already closed.
Unexpected display loss releases that workspace's lease membership through the same role boundary,
and a user-selected third-party HOME is never overwritten. If a new MagicDesk
process starts while still holding HOME, the startup guard disables its HOME
surfaces and opens system HOME immediately without waiting for the privileged
service. It marks the lease `STARTUP_RELINQUISHED`; once privileges are ready,
reconciliation restores only SECONDARY_HOME and clears that record, without
reopening a workspace or overriding the user's new primary HOME selection.
Subsequent HOME admission checks the current active lease,
not a process-lifetime recovery flag, so a new explicit session can start in
that same process. One event-driven reconciliation clears a release record
left after HOME was already transferred before process loss. This recovery does
not add a runtime polling loop. `DesktopOperations` owns the common target-aware
close operation; transport-specific code stops at target preparation.

## Display Drivers And Preparation

- An already connected wired or wireless secondary display enters
  `DesktopSessionController` directly on every platform. Closing the desktop
  releases its application tasks as independent fullscreen tasks on that output;
  it does not disconnect or reconfigure the system-owned transport.
- The Nubia projection extension may configure physical HDMI timing and
  native caption visibility. These are independent capabilities and
  do not create or own a second logical display.
- Starting an external desktop requires an existing Android secondary display.
  A separate **Wireless** action is exposed only when the
  selected platform driver provides an available connection UI. Standard
  Android resolves `Settings.ACTION_CAST_SETTINGS` through PackageManager and
  opens it with ordinary application permissions; the manifest declares that
  query. The Nubia implementation opens SmartCast. Both return to Phone Control
  Panel after Android reports a newly connected Wi-Fi display, without starting
  the desktop implicitly. **Wireless** remains available for an existing
  connection; reopening its settings does not request an automatic return to
  the panel. Cast-settings availability does not guarantee Miracast support;
  a real secondary display must still appear in the shared display catalog.
- Once Android reports a Wi-Fi display, MagicDesk passes that display ID to the
  common desktop session. It does not implement a second discovery or streaming
  stack.
- Display preparation and session ownership are separate. The phone panel
  selects a live `DesktopDisplayInfo` from `DesktopDisplayCatalog`, with source,
  unique identity, dimensions, support, and removal ownership. Secondary built-in
  screens remain explicitly unsupported until their HOME/input path is verified.
- Built-in topology and the system default-display role are distinct. The output
  model uses `BUILT_IN`, not a transport inferred from `displayId > 0`.
  `DesktopDisplayInfo` publishes both identity properties, while catalog and
  presenter admission separately reject secondary built-in hosting. Such a target
  can be represented by policy tests without starting an unsupported session.
  Removal eligibility excludes every built-in screen independently of ownership.
- `ShellVirtualDisplays` owns headless Android virtual-display tokens independently
  of HOME/tasks. Several displays and desktop workspaces may coexist.
  Creation size and density are configurable; scrcpy captures an
  existing logical display without owning its lifecycle. Framework primitives
  and hidden flags belong to `FrameworkVirtualDisplayApi`. App-owner Binder death
  releases its display tokens. A callback-driven ImageReader supplies the
  output Surface required to keep the display ON on Android 15 and 16.
  Frames are discarded without pixel reads; there is no timer or per-display
  thread. Keeping a virtual display alive still has Android rendering costs.
- The optional phone-preview type uses Android's overlay adapter through
  `SimulatedDisplayLease`. The setting replaces the entire overlay set, so
  creation refuses an existing overlay instead of disturbing it. Headless
  displays have no such singleton limitation. The four existing session drivers
  remain; both virtual sources use the standard simulated-display path.
- Close Desktop returns that workspace's tasks, releases input only if selected
  there, and restores HOME only for the last workspace, without deleting a display.
  Explicit removal validates both runtime ID and unique identity, requires
  MagicDesk ownership, closes an active session on that display first, then
  waits for transition quiescence before releasing the display token. Wired,
  wireless, foreign virtual, and built-in screens cannot be removed this way.
  DisplayManager callbacks refresh the panel; catalog reads add no polling.

When a new external desktop task is ready and automatic touchpad opening is
enabled, `PhoneTouchpadController` opens `MagicDeskTouchpadActivity` on display 0
if that display driver permits it and display 0 does not host a Desktop.
Explicit touchpad opening remains available. No absolute-position API is required.

The runtime asks the selected platform to expose native captions for wired and
wireless desktops. The Nubia driver applies its matching privacy filter;
standard Android and simulated displays do not modify vendor SurfaceFlinger
state. Android associates input locations with stable display unique IDs on
every target. Simulated sessions exercise the same phone IME policy, shortcut
filter and virtual phone-pointer lifecycle as a physical desktop.
Virtual input remains scoped to the session and cleanup waits for
its removal before the test completes. The test inspects WMShell's caption and
resize input windows after a cross-display move, including their display ID,
frame, input channel, token, and
touchable region. Synthetic events target an explicit display; physical pointer
dragging additionally exercises InputReader's device associations.

- A normal launch on display 0 opens the phone control panel.
- Desktop hosts are excluded from Recents. With HOME, the phone uses a dedicated
  primary HOME task; without HOME, either target uses a standard fullscreen task.
- With HOME, an external desktop is a display-sized secondary HOME Activity. Its stack
  position separates exposed workspace tasks from tasks below HOME. The phone
  uses `PhoneHomeActivity`, showing its own Desktop if active or ordinary Start.
- Phone control and external desktop are separate tasks and may coexist.

## Phone HOME And Recovery

The phone touchpad startup preference is evaluated once after a newly
created external desktop becomes ready. It does not disable manual opening or
change the existing requested/visible lifecycle used to preserve an open
panel across task transitions.

On display 0, Nubia Quickstep can crash while binding Recents to a desktop
group containing freeform tasks. Its `DesktopTaskView.bind()` creates task
containers without a title view, but `TaskView.setThumbnailOrientation()`
unconditionally assumes that view is present. Current AOSP Launcher3 permits
the field to be absent; this is a vendor integration defect rather than
malformed task metadata.

When the shared desktop session acquired HOME, MagicDesk owns Android's HOME role.
`PhoneHomeActivity` remains the phone navigation surface throughout the shared
HOME lease, showing ordinary Start or the local Desktop. The task layer enforces a
separate invariant: no application task may remain freeform in the ordinary
phone workspace after migration or teardown. A live phone Desktop is excluded.
`ShellExternalTaskMigrationGuard` normalizes system-driven moves while an external
observer owns that policy, and
`PhoneDesktopTaskRecovery` reconciles live tasks with WMShell's retained desktop
repository after desktop close or external-display loss. Already migrated
phone tasks can still be indexed under the external display, so recovery
checks all repository groups against the live display-0 task snapshot. It does
not move tasks that remain on another display or revive unrelated external
entries. External Close runs this reconciliation before presenting restored
HOME, including when the physical monitor remains connected.
External task observers with HOME attach `ShellSecondaryHomeStartPolicy` to the shared
activity-start controller while configured. It rejects unaddressed secondary
HOME selection before any Activity or HOME root is brought forward, including
selectors resolved to MagicDesk itself. Android's package-addressed per-area
HOME starts and our explicit display-targeted host launches are preserved.
The callback exposes no destination options, so admission does not infer a
display from focus or launch a replacement. Closing one workspace removes only
its policy; closing the last external workspace restores ordinary selection.

The optional `ShellPhoneOverviewRouter` starts independently of the main
activity-start observer. With HOME acquired and `RECENTS_TO_HOME` enabled, routing becomes
active only after resolving the system Recents component and preparing its
existing tasks. Missing capabilities or preparation failures leave routing
disabled and report the reason through `TASK-OBSERVER-RUNTIME-001`; they do not
disable task observation or intercept the ordinary system Recents request.
There is no background retry.

The Overview router may remain registered while task teardown is still
finishing, but it cancels the firmware Recents launch only after the app-side
callback confirms an `ACTIVE` HOME lease. The lease enters `RELEASING` before
the last membership leaves through normal close, failed start, self-test cleanup,
or unexpected display loss. Recents therefore returns to the system launcher at
the HOME ownership boundary rather than at the end of task cleanup; the check
runs only for an attempted Recents launch and adds no background work.
After a user session relinquishes HOME, the close coordinator explicitly
presents the verified current role holder once task-session and display
teardown have completed. Role transfer precedes the last workspace's teardown, but
the later presentation avoids asking Android to start HOME through an
organizer hierarchy that is being removed. It also prevents Android from
leaving the now-inactive `PhoneHomeActivity` task visible after the role itself
has already changed.
Both MagicDesk HOME components are disabled when no workspace is owned,
including their manifest defaults. First preparation enables `PhoneHomeActivity`
and `DesktopActivity` in the same component batch, before role acquisition and
HOME presentation. Adding or closing a non-final workspace leaves both identities
enabled. The last Close returns the role first but keeps existing HOME surfaces
alive through task parking and host/display teardown. Its final phase disables the
components and clears the `RELEASING` lease, before presenting restored HOME.
Disabling a live Activity component itself starts Android CLOSE transitions;
it is not a harmless way to update role eligibility while parking tasks.
The runtime's existing start/close gate prevents recovery callbacks from
finalizing that lease concurrently. Existing HOME instances ignore new HOME
requests during release rather than destroying the host ahead of its owner.
Final Close, rollback, and session loss leave both disabled;
neither primary nor secondary launcher choices may offer inactive MagicDesk.
A missing role holder
therefore reaches Android's launcher resolver without selecting inactive
MagicDesk again. Process-start recovery disables the surfaces before privilege acquisition
is needed, and detects both a stale lease and HOME resolution to MagicDesk,
not just `RoleManager.isRoleHeld`.
An isolated self-test closes its own fixtures through the production task
controller before calling the common Close-to-HOME coordinator. Workspace
isolation does not suppress restored HOME presentation. The harness verifies
the concrete primary HOME component on display 0 before restoring its report;
a secondary launcher from the same package is not an equivalent result. If the
display-removal suite already completed production display-loss recovery, the
expected phone destination is Control Panel instead.
With an active phone workspace, the routed request reveals its hidden taskbar
through `DesktopUiGateway` on the registered host.
Without a phone workspace, an ordinary, package-scoped HOME Intent on display 0
selects Recent within phone Start. There is no separate phone Overview Activity.
An unavailable phone host rejects the request. UI dispatch revalidates the host
and its active lease.
Managed tasks remain available through the desktop taskbar, task overview and
Alt+Tab. With `RECENTS_TO_HOME` disabled, the system retains its native gesture.
This common compatibility preference uses the platform provider's recommended
default and can be overridden by the user for the next session.

## Display Profiles

Each desktop target has a profile keyed by its Android display identity, never
by the transient logical display ID. Profiles store DPI and wired output timing;
created virtual displays also retain their creation size and flattened origin.
The stored size describes creation, not a live resolution override. Files and `.desktop` shortcuts under
`/storage/emulated/0/Desktop`, taskbar pins, file placement, application window state,
and recent-app history are global across displays. Widget instances belong to
individual workspaces, with independent provider configuration and size.
Desktop items and freeform windows store fixed-point relative anchors rather
than monitor pixels, so the same layout follows the user between the phone, a
tablet, and every monitor while adapting to each viewport.
## External Desktop Activation

When starting Desktop on a selected external display, MagicDesk:

1. resolves the selected live display and its stable identity;
2. loads the profile keyed by that display's stable identity;
3. optionally applies a platform-specific physical output timing;
4. corrects geometry and applies the display profile DPI;
5. optionally acquires HOME and creates its selected display-sized host;
6. focuses the desktop and restores the last visible window layout.

The target's workspace identifies the Android display that actually hosts the
tasks; its output identifies the selected presentation endpoint. The current
presenter binds them directly. MagicDesk does not create a vendor projection
display, infer lifecycle state from vendor settings, or return the physical
transport to another mode when the desktop closes.

Requests are serialized and duplicate requests during transition are ignored.
With no external display, the shortcut cannot accidentally create a second
desktop on display 0.

Display discovery and display hosting are separate contracts. The shared
desktop-session path accepts only a ready `DesktopDisplayTarget`. That target
identifies the Android display which owns tasks separately from the output and
its profile. The four preparation drivers produce direct bindings.
Phone, simulated, wired, wireless, UI, self-test, MCP, and App Functions starts
all converge on this boundary before the common session controller runs.
Normal starts use `DesktopSessionPolicy.USER`; diagnostics can select the
non-restoring, non-persisting `ISOLATED_SELF_TEST` policy without adding
display-specific restore exceptions.

Failed Desktop host launches retain per-attempt evidence before cleanup:
`DesktopHostLaunchDiagnostics` records the Android start result, stage, caller
identity and returned task/type. Only a failure reads one bounded cross-display
snapshot through `FrameworkTaskSnapshotSource`; it reports MagicDesk and HOME
task metadata, never Intent extras or UI contents. Missing observations remain
unknown. The bounded exception survives Binder into `DESKTOP-LAUNCH-002`, so a
report collected after HOME release still explains the failed launch. It adds
no retry, wait or task polling to a successful startup or an active session.

Secondary sessions share one display-default policy on every platform:
`SecondaryDisplayWindowing` prepares freeform before HOME activation through
`FrameworkRuntime.displayWindowing()` and the shared privileged Binder service.
Display 0 is untouched; explicit fullscreen task modes remain independent.
An unavailable or rejected display-default request fails preparation.

`DisplayWindowingSession` captures and durably records the previous effective
mode only when it changes. Close restores it after desktop teardown, and failed
startup releases the same ownership, including a lost write acknowledgement.
An already-freeform display requires neither a write nor a restoration entry.
Android 15+'s getter resolves effective framework policy, so restoration
preserves the previous effective mode, not the absence of a raw override.
A different mode selected by another owner is left intact. An already running secondary
launcher is not removed: restoring the display defaults does not promise a
return to mirroring or transfer ownership of another launcher's task.

WindowManager retains physical-display overrides after disconnection. Pending
restoration therefore uses the stable display identity, not the connection's
numeric id. Existing display-added and shell-ready callbacks recover interrupted
changes, skipping the active session. Virtual-display entries are discarded
only when the display is gone. There is no additional timer, task sampling,
worker, or idle Binder traffic. Diagnostics reports active display and pending
restoration count. This lifecycle is independent of optional input-focus repair.

## Output timing

Output mode discovery and selection belong to the projection contract, with
optional firmware and SoC backends plus Android's display-mode list. The
control panel presents the returned capability instead of assuming a vendor
node exists. Stable permission denial is cached and reported as an observation,
not retried on every UI refresh.

The Nubia timing controller can use advertised EDID timings when its nodes are
accessible. The independent Qualcomm backend can supply additional timings;
Android remains the baseline. If no backend exposes selectable alternatives,
the active physical mode remains usable and read-only. Exact vendor operations
are described in the [vendor audit](nubia-vendor-audit.md).

Physical timing changes happen before Desktop attaches tasks to the target.
Preparation waits for the selected mode and resolves the display identity again
because a mode change can recreate the logical display. The per-display profile
stores output timing separately from UI density.

**System/native** relinquishes MagicDesk's Android mode preference once when
leaving an explicit MagicDesk selection. Later starts preserve the system's mode;
there is no repeated reset. Failure to apply an optional timing does not imply
that the monitor's current mode cannot host Desktop.

## Caption visibility

RedMagic uses separate privacy filters for wireless and wired projection:

```text
SurfaceControl.setSFOption(1100, wirelessPrivacy)
SurfaceControl.setSFOption(1102, wiredPrivacy)
```

The firmware filters external layers whose names contain `Task=`. AOSP caption
layers are named `Caption of Task=<id>`, so captions can remain interactive but
become visually black or absent. Nubia's exported projection provider reports
the current wireless and wired privacy preferences independently. During an
external session MagicDesk sets only the active transport's filter to visible,
records lifecycle ownership, and restores that transport's latest preference
on session exit, transport change, or next-start recovery. Simulated displays
do not acquire this vendor state.

Nubia's separate password privacy shield is a focusable Presentation. With the
firmware privacy option enabled it can repeatedly steal browser/IME focus and
flash on an external desktop. The Nubia projection driver exposes **Prevent
Nubia privacy shield flicker** in **Compatibility (next session)**, default on;
other platform drivers expose no such option. At the first direct wired or
wireless workspace, the driver freezes that preference until the last external
workspace closes. After reading caption preferences it temporarily disables the
Nubia projection package using the existing authorized shell service. This also
disables that package's connection UI while protection is active. Android-owned
HDMI and Miracast outputs remain connected on the verified firmware; other
vendor casting protocols are not covered by that verification.

The package's exact prior enabled/default state is journaled before mutation.
An already-disabled package is not enabled on release, and later external state
changes are not overwritten. Close restores the package before restoring caption
privacy, before acknowledging completion. No saved firmware privacy preference
is modified. Shared startup recovers an interrupted lease when access becomes
ready without initializing Desktop; an application crash can therefore leave
the package disabled until MagicDesk next starts with privileged access.

## Teardown

**Close desktop** first captures live managed application tasks. If the display
still exists, its topology owner releases them in one transaction as ordinary
fullscreen tasks on that display, resets Desktop presentation overrides and
releases fullscreen planes without activating every app in sequence. Existing
independent tasks are not included. Confirmed display loss uses the existing
phone-fullscreen recovery; failure to query a display is not evidence of removal.
The in-memory workspace record is restored only on its own still-live display;
records from a removed display can be restored to a new workspace. Restoration
matches task ID, Android user and package, so it never recreates a closed task.
This retained task-layout record is distinct from portable workspace parking:
parking leaves a virtual Desktop active and changes only its output presentation.
The same record is captured from the latest observed task snapshot when
a display disappears or a desktop host is replaced before an explicit close can
query it. An explicit **Exit MagicDesk** clears this record and closes built-in
MagicDesk windows instead.

`DesktopCloseMode` distinguishes Close to the control panel, Close to the
restored HOME (including the phone HOME surface), and full Exit. It names a UI
destination, not a collection of cleanup flags. Before side effects,
`DesktopSessionEndPlan` validates the requested workspace against the current
target/host and captures the task disposition and phone-recovery policy once.
A stale close cannot release a different workspace's HOME or input. A retained
target may still finish cleanup after its host or display disappeared.
The plan ends only the selected workspace and releases input if that workspace
still owns the selection. It is not an output detach or a transfer to another
active workspace. The existing
task-release path retains layout for Close, but not for Exit. No destination
display is removed by this plan. Retaining a workspace after output loss uses
the separate virtual-source presentation path: Detach is not a Close operation.
Both Close destinations park tasks before releasing the desktop host; showing
the phone control panel is only a presentation choice. Exit uses the same task
return path without retaining a workspace for restoration.

Before normal teardown, `DesktopHomeRoleLease` marks the closing membership;
only the last workspace in a HOME-owning session restores and verifies the exact
original HOME role state: either the previous holder or no explicit holder. When acquiring the
role, the lease also
resolves that user-selected HOME package to a concrete `MAIN`/`HOME` Activity
through the shell PackageManager and persists its component, package version,
and static availability. This is a one-shot capability snapshot rather than a
launcher invocation or runtime compatibility probe. An unresolved or
unavailable Activity does not block HOME ownership or restoration, which
continues to use the role-holder package as its authoritative identity.
The lease enters `RELEASING` before that handoff so startup recovery can finish
an interrupted release without treating it as an active desktop. If MagicDesk
still owns HOME after process loss, the pre-privilege startup guard instead
disables its HOME surfaces immediately and retains a `STARTUP_RELINQUISHED`
record solely for secondary-handler restoration. During normal
close the role handoff does not disable Activity components: the `RELEASING`
record retains ownership of the remaining surface cleanup until the close
coordinator finishes task, host and display teardown. It then disables the
components and clears the lease. A later cleanup failure never claims HOME for
MagicDesk again. Unexpected display loss outside an explicit transition releases
the lost workspace's membership; only the last loss restores the role and
disables the surfaces without waiting for a UI callback.

Close is one-way even when a cleanup operation fails. A failed HOME handoff
does not skip input, task, and host release. Explicit display removal that cannot
pass its transition-quiescence gate leaves the display present, but its
desktop session stays closed rather than resumed. Close alone never removes
the display. Failures remain diagnostic
errors; they never reopen input routing or migration protection.

Physical display removal, **Close desktop**, and **Exit MagicDesk** share the
common cleanup path:

- hand HOME back to the package saved by a HOME-owning lease only for the last workspace;
- restore an active phone-display power guard before releasing input, even when
  the external display stays connected and the foreground runtime stays alive;
- release shortcut filtering, display associations, and the virtual phone pointer
  only if the closing workspace owns the selected input;
- keep HOME components enabled while parking tasks and removing the desktop host
  or owned display; after the last workspace, disable them before presenting
  the restored launcher;
- close display-scoped panel windows and stop task observation;
- stop phone-display streams;
- restore caption privacy and display geometry ownership;
- restore vendor hardware settings changed by MagicDesk;
- remember the owned display before Nubia can move its desktop host to display
  0, then normalize user tasks that WMShell still indexes under the removed
  wired or Miracast display;
- revive tasks that remain only in SystemUI's removed-display repository before
  normalizing them, instead of leaving an unavailable desktop entry behind;
- remove dead Recent entries retained by the current user's desktop repository
  and restore the phone control panel only after task cleanup completes;
- stop the foreground runtime on explicit exit.

A `DisplayManager.DisplayListener` validates actual display lifecycle instead
of trusting only Nubia's global state values.

Explicit Close owns both task parking and the subsequent phone-task
reconciliation; an expected display-removal callback does not enqueue a second
recovery. If the display was removed, Close also reconciles its retained
SystemUI entries within the existing bounded recovery, before returning its
result. All returned freeform user tasks are normalized to fullscreen on
display 0. Unexpected loss owns one cancellable recovery request, using task
events and the bounded display-removal watchdog only while migration is pending.
Success, cancellation, and failure are terminal, including an unavailable task
ID retained by SystemUI. Events caused by recovery itself cannot restart a
completed request, and its late callback cannot affect a newer request or session.
