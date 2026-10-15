# Application And Shell UI

[Architecture overview](architecture.md) | [Documentation](README.md)

Owners of native application surfaces, control-panel interaction and Desktop shell composition.

- [Control Panel And Shared Placement](#control-panel-and-shared-placement)
- [User-facing lifecycle](#user-facing-lifecycle)
- [Desktop UI](#desktop-ui)
- [Application profiles](#application-profiles)
- [Task Manager](#task-manager)
- [Desktop Surface And Widgets](#desktop-surface-and-widgets)
- [Viewport And Desktop Chrome](#viewport-and-desktop-chrome)
- [Shared Application Catalog And Start](#shared-application-catalog-and-start)

## Control Panel And Shared Placement

The control panel uses `DisplayTableView`: one row per Android display with its
identity, Desktop status, independent applications and Viewer links. The display list
follows a full-width status and a shared row of clickable Access, Termux and Desktop
summaries, without an extra section heading. `IntegrationStatusDialogs` separates
read-only prerequisite summaries from explicit authorization/setup actions.
Termux's `Available` summary describes the installed service and Android permission,
not verified command execution. `TermuxConnectionStatus` starts one asynchronous,
bounded command/result check after the first resumed UI, once prerequisites are
available. Service-only MCP startup does not trigger it. `Checking` becomes `Ready`
after an acknowledged command; a timeout returns to `Available`, not an inferred
permission denial. An explicit failure retains its details. The check uses
`TermuxCommandResultReceiver` and a clean shell without CLI preparation, a PTY or
opening Termux's UI. It may start Termux's foreground service and notification.
Results are process-local and endpoint-scoped; a changed endpoint or prerequisite
invalidates the old result. Panel refreshes and subsequent Activity resumes do not
retry completed checks. `TermuxSetupDialog` offers a user-run setup command and
manual retry through the same status owner. Closing the dialog only unsubscribes;
the shared check retains its bounded lifetime. No probe result gates tools.
**Create display** belongs to the lower general-action grid and uses the table's
current selection for creation defaults. **Exit MagicDesk** asks for confirmation
before invoking the existing exit controller. Desktop Start's Tools page exposes
only **Close desktop** for its own workspace; global exit belongs to the control
panel. Radio selection
is local to this view and survives catalog refresh by display unique ID. Newly
connected or created displays are selected when they appear in the catalog;
status or resolution changes preserve manual selection. Initial selection prefers
a non-built-in display in list order, otherwise the panel's host display. The
first catalog is a baseline, not an arrival event. A shared two-column
action grid addresses the selected display: Start/Show, Close, Apps, independent
applications, input control, output settings, removal and presentation actions.
Its labeled icon buttons use the same `DesktopUiFactory.controlAction` styling
as the session controls below, with equal-width columns and stable action slots.
Rows retain their View identity for each live display ID/unique-ID pair, and the
toolbar is created once. Status refreshes update existing controls without
detaching them, preserving in-progress touch and accessibility interactions.
The display section remains available without shell. Its Apps action uses the
selected display while Desktop, system input, Viewer, resource creation and
global task controls retain their own privilege requirements. A single local Apps
action remains available if inventory is unavailable. Fullscreen Start starts only the tools
runtime, never Desktop; without shell it offers saved Recent entries instead of
querying Android's task catalog. Desktop readiness combines API 35+, privileged
access and the read-only `DesktopSetupStatus` observation of device setup and
pending reboot. It does not use process-local runtime authorization. Checks run
on access events, panel resume and explicit refresh/setup actions, not periodically;
the panel and MCP consume the same cached result. Opening the status dialog does
not configure settings, authorize Desktop, acquire HOME or start a session.
Device Setup audits, including failed reads and startup prerequisite checks,
only update their UI and readiness observations. They do not revoke runtime
authorization or release existing Desktop services. Explicit setup actions own
their changes independently of the audit result.
The same audit refreshes WMShell's advertised desktop provider independently
of the supported task-entry command signature. A configured device without that
provider is **Limited**, shown in the theme's warning color; an unreadable probe is **Unverified**. These observations
do not disable independent tools or the explicit freeform fallback. A refreshed
audit replaces the command cache, including after SystemUI restarts.
Settings leaves independent preferences editable while
unavailable privileged settings remain disabled, without reading their shell store.
Selecting a row does not claim input or change a session. Commands capture the
selected identity; creation uses its resolution as a default. Creation and output
configuration have separate dialogs. A Viewer remains an application; it does not
own either display.

The independent-application picker keeps one row per Android task on the selected
display. `LauncherAppRepository`, profile-scoped `AppReference` and `TaskTitle`
provide the same icons and names as Start and task overview. Details identify
terminal directories and Viewer sources; task numbers distinguish repeated
titles instead of exposing package IDs as primary labels. Row activation and
the separate close icon use `ApplicationTaskPlacement.controlIndependent`,
retaining its live identity and ownership checks. Successful activation dismisses
the picker; closing refreshes its task snapshot without closing the picker or
adding a task observer. Empty and failed results remain explicit.

`DisplayProfiles` owns shared profile identity and creation snapshots. Ordinary
creation through the panel or automation inherits a reference display's current
logical resolution and explicitly saved DPI (otherwise its live density).
New portable Desktop sources instead default to the same resolution-based
`DisplayDensityPolicy` recommendation as direct external Desktop startup.
An explicitly saved DPI takes precedence; an explicit System preference resolves
to the reference's live density in both creation paths. Each created
display has its own profile, including its creation size and DPI, and a flattened
`originProfileKey`: the reference's origin or its own profile key. A creation with
no reference becomes its own origin. A descendant needs neither its parent nor
the originating monitor to remain connected. Profile updates write only to the
display's own key; they do not propagate to ancestors or other descendants.
Viewer attachment, detachment and output switching never alter profile origin.
Physical output timing and protected-content policy are not inherited.
Profiles never enable `alwaysUnlocked`: this keyguard policy is an explicit,
default-off creation option, verified against the returned Android display flag.
It is independent of protected content, power and application liveness. A missing
profile-save receipt after creation reports the retained display identity; callers
must not mistake that partial result for either no allocation or a ready portable
workspace. Shared creation does not initialize Desktop or acquire HOME/input.

Every shared Start surface uses the same grid and `StartLaunchControls`: Current
or an explicit display, App default / Desktop window / Desktop fullscreen /
Independent, and an optional New window request. Desktop choices require an
existing workspace on the destination. Independent applications are ordinary
fullscreen tasks, even on a display hosting Desktop. Explicit selection of a
recent task or terminal session reuses it. New window requests another Android
task subject to the application's manifest, not another account or process.

`ApplicationTaskPlacement` revalidates task identity and coordinates ownership
handoffs. Android remains the source of all live tasks and their displays;
MagicDesk tracks Desktop membership published by its task observer, including
adopted freeform tasks. An unavailable membership
snapshot is unknown, not independent. Leaving Desktop uses its existing topology
owner to release the task; ordinary-to-ordinary placement uses
`FrameworkActivityLaunchApi` without initializing an organizer. Same-display
selection preserves the topology unless the requested ownership or mode changes.
Ordinary Android launches from `.desktop` entries use the same preparation and
delivery boundary as Start and automation, including on the phone. They do not
bypass task release or replace an app-owned PendingIntent with a shell launch.
Input selection is independent of every launch and transfer.
Shared task closure also checks live ownership: independent tasks close through
Android without preparing Desktop focus. Managed tasks retain their topology
owner; unknown or rejected ownership never falls back to an ordinary close.
Before Android removal, registered built-in `CloseHandler`s receive the request
on the UI thread, without holding the registry lock. X11 hosts retain their
task/output for client confirmation; actual client disappearance finishes the
Activity and uses the existing task-removal observer. Task-scoped force stop
uses that same handler before considering Android package termination. Explicit
package-scoped force stop retains its Android semantics and rejects MagicDesk.

## User-facing lifecycle

- `ControlActivity` and `PhoneControlPanelController` provide the compact phone
  control surface. They do not create taskbar, wallpaper, or app-catalog UI.
  `DisplayTableView` gives each display a selectable status row and shares one
  responsive action toolbar across the list.
  `DisplayCreationDialog` owns creation choices; `DisplayOutputDialog` shows
  the addressed output's current mode, read-only while it has a Desktop session.
  The output button keeps a permanent slot and is enabled only for supported
  wired outputs. Opening a dialog does not change a mode or switch a session.
- `FileManagerActivity` is an ordinary tool Activity, fullscreen outside Desktop
  or a managed window inside it. The activity owns
  navigation and selection; `FileManagerView` renders them and routes user actions.
  `FileDirectoryReader` reads a complete listing on the existing Files worker;
  `FileManagerOperationController` owns
  lifecycle-bound remote operations and `FileManagerImportController` owns
  incoming Android URI drops. It has no vendor dependency.
- `CommandConsoleActivity` presents a retained `ConsoleTerminalSession` on the
  phone, an ordinary secondary display, or Desktop. Closing its window detaches
  an ordinary presentation; a managed tmux window releases only its client PTY.
  Ending the session explicitly closes its PTY and emulator.
  Sessions remain isolated from each other and have at most one attached window.
- `SettingsActivity`, `SettingsView`, and `MagicDeskSettings` own persistent
  user-selected desktop behavior. They are separate from the transient Quick
  controls panel for the active session. Settings also provides the stable
  entry points for device setup, diagnostics, and
  About, keeping the phone control surface focused on session actions. The
  activity is exported only behind `MANAGE_ACTIVITY_TASKS`, allowing the shell
  launch backend to create its desktop task without exposing it to regular
  applications. `BuiltInDesktopAppCatalog` is the single allowlist that
  separates user-facing MagicDesk tasks such as Files, Settings, and
  Diagnostics from shell
  infrastructure. It also records whether an internal window can have multiple
  tasks, appear in the launcher or taskbar pins, and share profile-scoped application window
  state. Settings is a reusable task per display with compact centered default
  bounds. A single constrained, scrollable `SettingsView` uses the same dense
  visual language on phone and desktop. The phone opens it normally, while the
  desktop task controller launches the same Activity in a dedicated reusable
  freeform task.
- UI translations are standard Android string, array and plural resources.
  `locales_config.xml` declares supported languages; `SettingsActivity` reads
  that declaration and uses the public `LocaleManager` for the per-app language
  preference. An empty locale list follows the system. Android owns persistence,
  resource selection and Activity recreation; retained terminal sessions keep
  their PTYs across that recreation. `ApplicationCatalog` invalidates Android
  labels on locale changes, and the runtime service refreshes its notification.
  Linux process environments, diagnostic report formats and automation
  identifiers do not depend on the UI language.
- Diagnostics follows that same built-in-window path on a desktop. It therefore
  cannot replace the desktop host Activity or hide every application
  merely because a report was opened. Phone-side callers may still open the
  same Activity normally in their current task.
- `DesktopActivity` is the secondary HOME host; `PhoneHomeActivity` is the
  primary HOME host. Both select ordinary Start or Desktop from local residency.
  Before creating either surface, the host rejects `SECONDARY_HOME` on the
  default display. A misrouted new Intent is ignored without destroying an
  existing valid HOME. Primary HOME and explicit Desktop launches retain their
  normal behavior; secondary built-in displays follow the same routing rule.
  `DesktopShellActivity` composes controllers and forwards Android callbacks;
  it does not own every feature directly.
- `DeviceSetupActivity`, `DeviceSetupManager`, and `DeviceSetupView` own the
  one-time platform audit and provisioning flow.
- `MagicDeskRuntimeService` composes the persistent notification and
  process-level runtime without duplicating subsystem state. Other components
  use the process-local `MagicDeskRuntime` facade instead of depending on the
  Android Service implementation. The service attaches a package-private
  backend for its lifetime; absent-runtime calls have explicit safe defaults,
  and a stale service cannot detach a newer backend instance.
  `RuntimeDesktopSessionCoordinator` owns desktop-display identity, unexpected
  display removal, retained phone-task recovery, and one-shot HOME-lease
  reconciliation. It consumes one immutable
  `DesktopSessionSnapshot` per decision, so the host display and the prepared
  display target cannot come from different lifecycle transitions.
  `RuntimeDisplayInputCoordinator` composes
  input-device routing, the phone pointer and shortcut filter, desktop text routing,
  and software-keyboard policy. `RuntimeDesktopTaskCoordinator` owns the
  display-scoped `DesktopTaskController` instances, initializes observation for
  admitted workspaces, and binds each reconciliation to that workspace's
  residency snapshot. It implements the narrow `DesktopTaskRuntime`
  contract exposed through `MagicDeskRuntime`; callers do not locate a
  process-global active task controller. The optional non-reference-counted partial
  wake lock is held only while both its setting and a MagicDesk desktop
  session are active. A separate opt-in screen wake lock keeps the phone display
  on while any workspace is active, including simulated workspaces. Neither
  lock wakes a sleeping device; explicit screen-off and lock remain authoritative.
  Both are released after the last workspace. Adaptive-brightness suppression
  also covers all workspace kinds: `FrameworkDisplayBrightnessApi` preserves the
  current float brightness before switching to manual mode. The controller
  restores automatic mode only when it owned the change and has not observed a
  subsequent user mode change. Manual brightness adjustments remain available.
  **System theme during Desktop** in **Settings > Appearance** is a live, opt-in
  system-wide override (Do not change / Light / Dark), shared by all workspaces.
  `DesktopSystemThemeSession` journals the previous and applied policies before
  writing through `FrameworkSystemThemeApi`; schedule and bedtime remain distinct
  from automatic, light and dark modes. Switching the preference retains the
  original policy. Do not change or the last workspace closing restores it only
  while MagicDesk still owns the change. A settings observer relinquishes ownership
  on an observed external change; ordinary workspace refreshes do not reapply it.
  `DesktopSystemTheme` serializes setting events and workspace reconciliation on
  the existing command queue. Desktop startup publishes its workspace and awaits
  that reconciliation before activating HOME, so the initial host uses the selected
  system theme. An expired preparation aborts startup rather than launching HOME
  ahead of the pending theme operation. Privileged-service readiness recovers an interrupted
  override without starting Desktop. Failed restoration retains the journal for
  subsequent recovery. The framework adapter checks the current user and does
  not redirect a profile's request to the privileged service's user. There is no
  polling, global ViewDebug invocation or SystemUI modification.
  These session settings do not modify Android's screen timeout. There is
  no boot receiver; the user starts MagicDesk manually. The notification body
  is a stable display-0 entry
  point to Phone Control Panel; its separate touchpad action opens the
  phone-side input panel. Both use direct, immutable Activity PendingIntents
  with display-0 launch options, never service or broadcast trampolines.
  A separate **Terminal** action is present while a local PTY session exists,
  with or without Desktop. Its Activity entry resolves the last focused terminal
  at click time and uses `TerminalSessions.open` to activate the existing window
  on its display or reattach a retained PTY. It never starts a replacement shell.
  Window-focus callbacks record recency in `ConsoleTerminalRegistry`; session
  registration and removal refresh the notification, not terminal output or polling.
  Process-scoped PendingIntents cannot attach to a reused ID after app restart.
  The touchpad action is offered for the selected external input display;
  the Activity validates its target again before requesting phone input.
  Any visible phone application suspends automatic touchpad restoration using
  the existing task snapshot. Opening an app or Control Panel from the
  notification therefore leaves the touchpad behind it without cancelling the
  user's input request. Restoration resumes only on an exposed HOME or empty
  phone workspace; there is no component-specific Control Panel exception.
  Desktop Show/Restore remains a taskbar and `Win+D`
  command rather than a state-dependent notification action.

## Desktop UI

- `UiDialogs` creates native modal dialogs and installs `DialogContentInsets`
  before presentation. The content host fits title, body and buttons together,
  preserving Android's decor handling and adding only the owner's remaining
  caption overlap. Layout and inset callbacks update the fit before drawing;
  attachment owns observation lifetime. Callers retain their show/dismiss/button
  callbacks. This contract does not require Desktop or privileged access.
  `UiChoiceAdapter` shares the host palette across a spinner's selected value,
  lazily created dropdown rows and popup background while preserving typed values.
  Appearance settings are a page inside Settings, with the same Activity content
  inset policy and a measured `UiContentColumn`; focused edits use modal dialogs.
- `ShellAppearance` separates semantic colors, typography, resources, control
  shapes and background presentation from panel layout. Its `Backdrop` combines
  opacity and blur radius; the common backdrop supplies native shell panels and
  popup backgrounds through Window bindings, while a nullable `PanelStyle.backdrop`
  overrides it for one panel. App dialogs share their owner's scoped palette
  and backdrop through `UiDialogs.builder`, including native controls and lazy
  list rows; external Android dialogs are not modified.
  `AppearanceStore` persists app-private defaults and sparse workspace overrides
  without Desktop or privileged services; Settings, MCP and the CLI share its
  validator. Workspace keys use stable identities,
  while live host bindings resolve the appearance for Views on each display.
  `UiAppearance` updates existing native Views and drawables. `PanelGeometry`
  resolves density-independent dimensions through `DesktopShellLayout`, retaining
  the existing focus, input and task-area owners. See [Native appearance](appearance.md).
- `DesktopTaskDescription` publishes app-owned titles, icons and scoped palette
  colors through the public Activity API. Appearance publication and host
  attachment/layout callbacks refresh changed metadata only; weak ownership
  does not retain closed Activities. `FrameworkTaskDescriptionApi` isolates the
  public bitmap-icon API difference across supported Android versions. Native
  captions remain WMShell-owned; third-party task descriptions are not modified.
- Backdrop blur is an optional compositor effect behind a surface, clipped to its
  visible rounded region; native text, icons and other content remain sharp and
  opaque. Radius 0 disables blur, and density conversion caps the radius at 150
  physical pixels. Dynamic system blur availability changes presentation only:
  an unavailable effect retains the chosen background opacity and stored settings.
  Standard system capability signals are respected without forcing blur on or
  substituting captured screenshots.
  `UiPanelWindow` borrows public framework decor for native panels, preserving
  their caller-owned window type, token, flags and geometry. `UiBackdrop` uses
  `Window.setBackgroundBlurRadius`; Android owns dynamic capability observation.
  Child-menu decor occupies only the menu, inside its unchanged larger input host.
  Appearance controls and persistence require neither blur support nor Desktop,
  HOME, input acquisition or privileged services. Workspace backdrop edits retain
  the sparse field-level patch pipeline; panel overrides retain whole-panel-list
  replacement semantics.
- `ShellComposition` declares native panels at each edge, their components and Start sections.
  `ShellComponentLayout` places start/center/end groups without overlap;
  `ShellPanelMetrics` derives button and icon geometry from panel thickness and padding.
  Start grid/list navigation recycles visible entries and measures page capacity from
  the host viewport and cells rather than reserving fixed rows.
  `ShellComponentLayout` allocates fixed and flexible slots; UI reconciliation
  retains component Views and the existing action/service owners. `ShellMotion`
  and state feedback are presentation-only. `UiMotion` transforms child content
  inside a fixed native panel host; Android ViewGroup dispatch maps pointer input
  through those transforms. Frame/backdrop geometry, reservations and focus stay
  with their existing owners. Pre-draw starts effects, and detach/theme changes
  cancel them without retaining callbacks or delaying closure.
  `ShellAppearanceSchema` supplies the
  machine-readable contract and validation, while `AppearanceTransaction` owns
  non-persisted preview leases shared by Settings, MCP and CLI. No theme creates
  a window manager, execution backend or service authorization.
- `ThemeBundleStore` validates bounded, data-only archives and atomically installs
  immutable content-addressed assets. `ThemeAssets` prepares decoded resources off
  the UI thread; `AppearanceStore` publishes the resolved document and resources
  together. Rendering borrows cached resources and never reads bundle files.
- `StartMenuController`, `TaskbarController`, `TaskOverviewController`, and
  `NotificationCenterController` own the persistent desktop controls.
- `DesktopWorkspaceController` composes the fixed Android `Desktop` directory,
  freedesktop folder, web, and application Desktop Entries, and Android widgets
  on one `DesktopGridLayout` surface. Desktop Entries remain real files and use
  the same drag, rename, delete, and placement path as every other desktop
  file.
- `DesktopFolderController` owns asynchronous desktop-file operations and the
  lifecycle of an event-driven observer. `ShellDesktopDirectory` constrains
  typed UserService operations to `/storage/emulated/0/Desktop` and owns its
  `FileObserver`. Each `DesktopWidgetController` owns its workspace's
  binding/configuration UI; `DesktopWidgetHosts` owns the Android host leases.
- `ShellFileSystem` is the separate, general filesystem boundary used by the
  built-in Files task. It intentionally accepts any absolute path available to
  the connected UserService identity; this broader contract is not reused by
  desktop metadata or automatic background work.
- `DesktopStateStore` is the single typed model for taskbar pins, global
  layout, application window and presentation state, settings, and display
  profiles. `DesktopLayoutStore`, `AppWindowStateStore`,
  `AppPresentationProfileStore`, `DesktopPreferences`, and
  `DisplayProfileStore` are narrow domain facades over that model.
  Updates mutate a private copy and publish it only after persistence succeeds.
  External file reloads read and publish under the same transaction lock, on
  the folder worker; the UI receives only the change notification, never a
  delayed snapshot that could replace a newer save.
  Its persisted schema accepts only the current format; an unsupported format
  starts from defaults instead of running an in-process data migration.
  `DesktopPlacementEngine` is the platform-independent collision and reflow
  policy.
- `DesktopPanelWindowController` provides consistent toggle, dismissal, and
  placement for desktop panels. It attaches ordinary
  `TYPE_APPLICATION_PANEL` windows and dialogs to the persistent
  `DesktopChromeActivity` token also used by the taskbar. There is no transient
  panel task or panel-specific organizer hierarchy.
- `DesktopHomeSurfaceHost` borrows background and bottom graphical surfaces inside
  HOME's view tree. `HostedShellWindows` reconciles the same protocol-neutral
  leases for HOME and chrome. Geometry, exact local input and output lifetime stay
  separate from Android task topology and the retained graphical session.
  `ShellPresentationScope` publishes workspace-local layer visibility from the
  existing taskbar fullscreen/reveal policy. Concealment releases borrowed windows,
  not protocol mappings or reservations; scope loss revokes hidden contributions
  as well. Native taskbar auto-hide remains a taskbar preference. See
  [Shell layout](shell-layout.md#android-adapter).
- `SystemPanelController` presents Quick controls using those same panel
  windows. It measures content within the available work area and opens on the
  inward side of the panel containing Quick controls, with scrolling when needed.
  Audio, density, pointer speed and optional hardware retain their existing
  controller and observation owners; the UI adds no monitoring loop.
- `DesktopInputController` handles shell UI input and delegates global physical
  shortcuts to the key-only Accessibility service.
- Pointer menus invoke the same semantic operations as shortcuts. Window
  arrangements retain the menu's exact task and call `MagicDeskRuntime.arrangeTask`;
  they never synthesize arrow keys or choose a newly active task. Taskbar Back
  captures the application before opening the panel and waits for the normal
  task focus gateway before sending Android Back to that display. Quick controls
  exposes device lock, and Start Tools exposes shortcut help. Pointer-opened
  context menus remain non-focusable and excluded from IME targeting.
- `DesktopRuntimeBridge` is the weak-reference, main-thread boundary through
  which services reach the active desktop. Host registration and display
  target changes are serialized into one immutable `DesktopSessionSnapshot`;
  the target may intentionally outlive an Activity during configuration
  recreation or external-display teardown.
- `DesktopLayoutController` owns WindowInsets, viewport, and taskbar geometry.
- `DesktopTaskSnapshotController` serializes task refresh generations and
  filters the taskbar model. During an active session, refresh reuses the
  controller's published display snapshot; it does not issue an independent
  raw query that can expose a transient Activity handoff to chrome policy.
  Unknown publication leaves visibility unchanged and remains unavailable.
  Replies requested before activation also recheck the current session.
- `TaskbarOverflowController` separates refreshed taskbar entries from the
  open menu's captured rows. Rebuilding its button after a task/focus snapshot
  does not dismiss or repopulate the menu. It closes when overflow disappears,
  on a normal panel dismissal or on teardown. Selection still passes the task
  identity to the existing controller, which resolves current workspace state.

## Application profiles

Application identity has four explicit levels:

- `AppProfile` is a resolved Android user id plus its stable user serial.
  Runtime task matching uses the id; durable references use the serial, which
  Android does not recycle when a profile is deleted.
- `AppIdentity` is a profile serial plus a package. `AppItem` retains both this
  durable identity and the resolved profile. `AppLaunchTarget` only describes
  an entry point (package, component, action); it is not a complete app identity.
- `AppReference` combines `AppIdentity` with an optional built-in tool entry.
  Files, Settings, and Console retain separate identities despite sharing one
  APK. A hosted application additionally carries its semantic launch-recipe key,
  so Calc and Writer do not share geometry merely because both use `X11Activity`.
  Ordinary Android applications remain grouped by profile and package.
- `LaunchActivityIdentity` binds an entry point or a package-scoped system
  surface to an explicit user id before task lookup. Direct launches bind at
  the current-user ingress; shortcut and PendingIntent launches retain the
  publisher/creator user. Reuse and launch confirmation never match another
  user's task just because its package and component agree.

`HiddenTaskApi` reads `TaskInfo.userId`; `FrameworkTaskSnapshot` carries it over
Binder into `TaskRepository.TaskEntry`, including published copies and parked
task records. Missing framework identity remains `-1`, not user 0. These fields
reuse existing snapshots and add no profile polling. The diagnostics task list
and MCP task rows expose the observed user id.

Generated Android Desktop Entries retain `AppIdentity` in
`X-MagicDesk-AppIdentity`. `DesktopLaunchRequest` preserves it through command
expansion and integration preparation. The coordinator rejects a reference
outside the supported current profile before any Android or Exec action; it
does not reinterpret it as the current user's same-package application.
Portable Desktop Entries without this field are current-context launch
descriptions, not durable references to a particular Android profile.

This is an identity foundation, not multi-profile support. The launcher catalog
still enumerates only the current profile. Profile discovery/availability,
badged icons, work-profile quiet mode, Private Space policy, cross-profile URI
grants and launch permissions are not implemented. Before widening the catalog,
profile resolution and permission-aware launching must be extended together.
The catalog, taskbar pins and window state exchange typed
`AppReference` values; DPI and application actions exchange `AppIdentity`.
Persistence alone serializes them as stable keys. Unbound or malformed stored
keys are skipped, never assigned to the current profile. There is no
package-only compatibility lookup.

`DesktopStateStore` stores pins, geometry and DPI. `RecentApplicationStore`
stores bounded, profile-private launch histories in `files/recent/desktop/*.desktop`
and `files/recent/independent/*.desktop`.
Android entries carry `AppIdentity`; command entries bind the selected Termux
package when applicable. Each file contains its launch recipe and last-use
timestamp, with no separate index or persisted task/session IDs. Semantic keys
merge repeated default Android launches and equivalent command recipes;
presentation, labels and file copies do not create duplicate history items.
One IO queue atomically replaces records and prunes each history beyond 24.
`RecentApplications` records successful launches and observed focus changes;
Graphical sessions publish the original recipe when they become usable. No additional
observer, timer or synchronous focus-time disk write is introduced.
Bounds callbacks carry `FrameworkTaskSnapshot`, so shell
observation does not need application-storage keys or profile serial lookup.
`BuiltInWindowIdentity` carries the hosted reference through launch Intents,
validated against the destination tool and Android profile. The live host publishes
the same reference through `BuiltInWindowRegistry.ApplicationSource`; existing
typed task callbacks resolve it for the common `AppWindowStateStore`. Recipe-less
graphical windows have no durable geometry key, rather than overwriting another program.
X11 dialogs (including those without a transient parent) and protocol-declared
child toplevels do not inherit the application's launch or geometry identity.
Live task/window identifiers and document titles are never persistent identities.
Unknown and unsupported task users cannot overwrite current-profile geometry
or receive its DPI. Application details, shortcuts and force-stop resolve the
explicit profile before dispatch; force-stop uses its resolved user id.

The Desktop directory remains a single shared MagicDesk workspace, not a
separate directory per application profile. File placement is path-based, while an Android application's profile is stored
inside its Desktop Entry. This does not grant access to another profile's
files or URIs. No Private Space permission or additional profile UI is declared
by this foundation.

## Task Manager

Task Manager is an independent shared tool, not a Desktop session owner.
`SystemCpuReader` owns the bounded aggregate CPU read and `SystemCpuUsage` the
rate calculation shared by the process monitor and appearance signals. CPU-only
requests use a typed counter snapshot without enumerating processes.
`SystemMonitorReader` publishes bounded procfs snapshots through the existing
privileged service. `SystemMonitorRepository` calculates rates between samples;
`ProcessCatalog` projects identities and process trees without knowing about
Android windows, Termux, or graphical protocols. Identity is PID, UID and process
start ticks, never a command name. Exited processes and failed observations discard old
counters. Inaccessible processes remain explicitly reported as incomplete
observation, not zero consumption.

`TaskManagerApplications` joins Android tasks, retained PTYs, tmux sessions and
X11/Wayland sessions through `GraphicalSessions`, without taking ownership of their
lifetimes. Session host tasks are claimed once; multiple tmux clients remain one
session entry. Android resource
figures are package-process totals and can be shared by multiple windows. tmux
resources come from pane processes and their same-UID descendants. Graphical entries
label server-only resources; clients remain visible in Processes. The Termux
filter includes processes started outside MagicDesk as well.
Graphical targets retain session IDs and re-resolve their owners before acting.
Hostless sessions reopen a whole-desktop viewer or a selected mapped client;
client selection is revalidated before launch. Force-close availability comes
from each Android host's existing close contract, not its graphical protocol.
Applications and Processes share name, CPU and memory ordering through
`TaskManagerSort`; processes additionally offer PID ordering and a collapsible
tree. Resource sorts place unknown samples last and use stable identity/name
tie-breaks. Application resource totals are calculated once per delivered
snapshot, not inside sorting comparisons.

CPU for a process or session uses 100 percent per CPU core; aggregate system CPU
uses 100 percent for the whole device. Memory is resident set size (RSS), not
PSS or private memory: adding processes may count shared pages more than once.
The first CPU sample is unknown. Resource sampling uses the visible Activity's
three-second refresh cycle and stops with it. Desktop task lists reuse the
existing framework observer; standalone task and tmux discovery run on opening
or explicit refresh, not on the resource-sampling timer.

`TaskManagerActions` delegates window activation/close, terminal detachment,
session termination and package force-stop to their existing owners. Managed
focus uses `DesktopTaskController`; independent focus uses
`ApplicationTaskPlacement`. `ProcessControl` sends TERM or KILL to one captured
process incarnation, never a process group. The native helper pins a proc
directory, validates UID/start time and uses `pidfd_send_signal`; an unsupported
kernel reports an error instead of falling back to a racy `kill(pid)`. The
selected privileged identity stays unchanged. Termux-owned processes can use
the already authorized RUN_COMMAND endpoint under their own UID. No Desktop,
root requirement, new daemon, or automatic privilege escalation is introduced.

## Desktop Surface And Widgets

`DesktopGridLayout` is a real `ViewGroup`, not a bitmap or remote task
container. Every shortcut, file, and `AppWidgetHostView` remains an ordinary
Android view with native accessibility and input behavior. Placements use
logical cells and row/column spans rather than pixels, so DPI or resolution
changes only reflow items that no longer fit.

`DesktopWidgetHostIds` assigns a persistent Android host ID to each profile serial
and logical workspace display unique ID. The private preference file stores only
this mapping and its monotonic allocator; Android remains the authority for widget
IDs, providers and configuration. Allocation requires a successful disk commit;
a failed write removes the tentative in-memory mapping before a retry can use it.
The scope never uses a session UUID, numeric
display ID, output profile, display name, geometry or inherited origin. Reopening
Desktop on the same logical screen recovers its widgets. Viewer attachment changes
no bindings. Creating a new logical screen gives it a separate widget collection;
this does not recreate deleted virtual displays or promise persistence of their
Android identity across recreation.

`DesktopWidgetHosts` is the process-wide owner of live host leases. Every workspace
has a different `AppWidgetHost`; replacing an Activity retires its old lease before
the new callback starts. Late stop/release calls from the old Activity cannot stop
the replacement. Close releases subscriptions and views, not the Android bindings
or the saved host ID. Other live workspaces continue receiving updates. Provider
catalog/removal callbacks refresh only the current owner, without polling. Host
views use the destination Activity's resources and retain native input behavior.

`DesktopWidgetController` enumerates only its host's IDs. Configuration results,
removal, resize and view creation check that ownership; a pending bind/configuration
is retained across Activity recreation and an unfinished new widget is discarded
when its owning Activity finishes. File layout remains shared; independently owned
widget IDs keep their own logical placement and cell span in the layout store.
No migration or automatic adoption of unscoped widget bindings is performed.
Provider clicks remain native; widget movement is entered
explicitly from the context menu so drag handling cannot steal controls or
scroll gestures from the provider. Binding and optional configuration use the
system widget activities without privileged widget APIs.

The fixed Desktop surface and the general Files task use separate typed AIDL
contracts instead of interpolating filenames into shell commands.
`ShellDesktopDirectory` rejects paths outside its fixed root, symbolic-link
traversal, invalid names, and accidental overwrite. Removing an application
shortcut or widget never deletes application data, and MagicDesk does not
delete the Desktop directory or its contents during Exit or uninstall.
Desktop changes arrive through `FileObserver`; the fixed folder is not polled.
State and wallpaper writes use an exclusively created temporary file in the
metadata directory for each operation. Overlapping Binder calls cannot remove
or publish each other's staged bytes; the last successful publication wins.
Writer and publication failures remove only that operation's temporary file.
Publication requests an atomic replacement, retaining a regular replacement
fallback for filesystems without atomic moves; this is not a power-loss
durability guarantee.
Metadata observation forwards changes to the published state or wallpaper
path, plus invalidation of the metadata directory itself. Temporary-file
events do not trigger a state reload or a wallpaper decode. The state reader
enforces its 2 MiB byte limit during streaming, not just through a prior file
size check; wallpaper transfers enforce their 64 MiB limit the same way.
The wallpaper writer owns its incoming descriptor before preparing the
metadata directory, so preparation failures release it as well.
Built-in Files uses the same `DesktopEntryFile` parser outside the fixed
desktop root, so a `.desktop` shortcut can be kept and opened from an ordinary
folder without introducing a second shortcut model.

`AddWebShortcutActivity` is an explicit Android Share target rather than a
launcher-shortcut interceptor. It accepts only validated HTTP(S) URLs, asks the
user to confirm the display name, and writes the same standard `Type=Link`
Desktop Entry consumed by Desktop and Files. Opening that entry resolves the
current Android browser and then uses the normal desktop application-launch
path; when Android still needs the user to choose a browser, its resolver is
opened on the same display.
Share URL extraction rejects source text exceeding 32 Ki UTF-16 code units
before copying or scanning it. Suggested titles and bounded clipboard reads
use `BoundedText` to retain complete surrogate pairs within their existing
code-unit budgets; title whitespace normalization remains a separate policy.

`ShellFileSystem` deliberately exposes the complete filesystem visible to the
connected UserService identity. Path validation requires normalized absolute
paths, protects the filesystem root from mutation, prevents recursive copies,
and treats symbolic links as links during copy and delete. Copy, move, and
recursive delete run on one operation executor only after an explicit user
action. Operations support cancellation and Binder-owner death; there is no
file-manager polling or idle worker loop. Name conflicts receive a numeric
suffix, so an interrupted copy never begins by deleting an existing target.
URI imports from both Desktop and Files use the same provider-name validation
and shell-side name reservation. The UI does not enumerate a partial directory
page or maintain its own occupied-name set. Collision handling follows the
destination filesystem's case rules, with exclusive creation deciding races.
`FileTreeTransfer` owns the filesystem copy/move mechanics, independently of
Binder callbacks. Copies create files exclusively with `CREATE_NEW`; a target
that appears after name selection is a conflict, never an instruction to
truncate it. During an explicit copy, a transient list records the created
entries and their parent identities. Rollback visits only those entries in
reverse order, checks their filesystem keys and parents, and deletes directories
non-recursively. Replaced paths, unavailable identity, or foreign children leave
the affected partial result intact instead of risking unrelated data. The list
is discarded when the operation completes; it is not a persistent index.
If cross-filesystem move cleanup fails after a complete copy, the destination
is retained rather than risking loss of both copies.

`FileOperationCenter` owns copy, move, and delete at MagicDesk process scope.
Its `FileOperationState` binds callbacks to the originating request, including
before the remote operation ID is returned. Cancellation, disconnection, or a
new request cannot let late callbacks finish another operation or clear its
clipboard selection. The center owns Binder and UI dispatch; the state model
owns progress and terminal transitions without Android dependencies.
`ShellFileOperationHandle` binds cancellation to the originating UserService,
not whichever service is current when a delayed cancellation is delivered.
Files windows subscribe only to immutable progress snapshots, so closing the
window does not cancel a remote operation. The process Binder remains the
remote owner: process death still cancels work, and a disconnected shell turns
the active snapshot into a bounded failure rather than leaving a permanently
busy UI. Imports from external `content://` providers remain Activity-scoped
because their temporary drag permission belongs to that UI interaction.
`ContentRequestScope` owns queued requests on each UI's existing executor.
Closing it releases grants for work that never started and signals cancellation
to running work; a running import releases its grant only after leaving provider
I/O. Executor rejection and release failures also complete the request, and a
closed UI ignores late presentation callbacks. Completion carries both the
operation value and any failure: a grant-release exception cannot erase an
already committed copy or action. Subscribers run after release and outside
the owner's lock; a subscriber failure cannot alter the published completion
or prevent releasing other requests. There is no additional executor
or polling loop. Cancellation is checked before provider access, between chunks,
at EOF, and before accepting the completed output, including empty/text imports.
An already blocked provider read must still return before its worker can finish.
`ContentImportBatch` owns the immutable source list and shared per-item execution
for Files, Desktop, and the Android share receiver. Sources are captured before
queueing; `ContentUriTransfer` binds the provider/text copy operation to that
request. Result counts distinguish committed copies, failed items, and items
left incomplete or unattempted. Cancellation is separate from success and
preserves already committed files and earlier failures. A provider error does
not prevent subsequent items from being imported unless cancellation is also
requested. Files progress counts processed items, including failures, rather
than successful copies alone. Progress-delivery failures stop the batch without
discarding its copy/error counts. Finalization preserves those counts and any
earlier provider failure when resource release also fails. The batch adds no
executor or background work;
`DesktopFileRepository` only loads desktop entries and thumbnails.

`ShellFileCreation` binds a newly created ordinary file to its originating
UserService. Imports and `.desktop` entry creation share this boundary. Writes
verify the original device/inode; commit retains a complete result.
Provider imports close both input and output streams before commit, so an input
close failure still rolls back the uncommitted file instead of leaving a saved
file that the batch reports as failed.
Otherwise close requests an immediate, non-recursive removal after shell verifies
that the path still names that ordinary file. A mismatch leaves it untouched and
cleanup errors are attached to the original failure. This replaces path-only
asynchronous cleanup jobs in Files and Desktop; it is not a filesystem-wide
atomic transaction against concurrent external renames.

`FileManagerActivity` maps the selection model to the same typed operations
for toolbar commands, item context menus, and standard file-manager keyboard
shortcuts. Metadata displayed by Properties comes from the same `stat` result
used for capability identity checks. APK installation is the only package
operation: it is offered only for a selected APK, requires a confirmation that
shows the absolute path, and executes as the already-authorized UserService
identity.

`FileItemContextMenu` renders the same file/folder command model into the
desktop context panel and the Files popup. `ItemActivationPolicy` likewise owns the
shared single-click/double-click decision; selection remains local to each
surface. Desktop placement updates still use the fixed-folder API, while
general copy/move work remains in `ShellFileSystem`, so UI integration does not
widen the automatic desktop-filesystem boundary.

File rows receive ordinary Android pointer meta state. Android delivers
physical `Ctrl` and `Shift` directly, including modifier-click selection.
The shortcut filter consumes desktop commands without forwarding text. Files
and desktop files/folders use double-click to open by default, with one shared
optional single-click mode in Settings.

Files windows are separate Android tasks with independent navigation and
selection state. `FileOperationClipboard` holds process-local shell paths and
an explicit `COPY` or `MOVE` intent; it is not a text clipboard and is never
persisted. `FileClipboardInterop` is the only bridge to Android. A selection
containing readable ordinary files is additionally published as read-only
`content://` items. Directories, symbolic links, and selections larger than
the bounded Android publication limit remain internal because Android has no
portable directory-clipboard contract and clipboard Binder payloads must stay
bounded. `ShellFileGrantStore` makes the same all-or-nothing publication
decision for shell-file clipboard selections and drag-and-drop. It checks the
entire bounded selection before preparing URIs, rejects special filesystem
nodes, and registers a batch only after URI preparation succeeds. Failed
clipboard writes and refused drag starts discard their unpublished entries;
accepted transfers retain them for consumers that open files asynchronously.
The prepared selection carries each URI together with its existing file MIME
metadata. Clipboard and drag producers consume the same typed items, so drag
does not replace known file types with wildcards or require another provider
query. Desktop file drags preserve the metadata from their file snapshot too.
`ShellFileGrantStore.Preparation` reuses that bounded staging map for explicit
Android Open/Share requests. A URI can be placed in an Intent before it is
registered with the provider. These calls publish only after the entire action
and requested display are validated, so a bad final source or malformed launch
option cannot leave a partially registered selection or evict older entries.
Publication precedes execution, not its observation result: a launch timeout
does not prove that a recipient has stopped using the file. Writable access
is capped in the grant entry itself, and the Open Intent uses that effective
value rather than the requested flag.
Files and Desktop can also paste content copied by another Android
application. URI items are imported as files; plain text becomes a UTF-8
`.txt` file (or `.html` when HTML is the only representation). External
consumers always see copy semantics; only MagicDesk can
complete the internal move. A completed move clears only its own generation
and the matching Android URI clip, so an older operation cannot discard a
newer selection or unrelated clipboard data.

Directory pages use a total name tie-break after the requested sort key, so
unchanged files with equal sizes, dates, or case-insensitive names cannot shift
between pages merely because the filesystem enumerated them differently.
`FileDirectoryReader` captures each load's path and sort/filter options in one
immutable request and returns one immutable listing, including desktop-entry
metadata. It abandons superseded work before querying another page or reading
more desktop entries and checks cancellation after blocking reads. A page must
advance its offset and retain the same canonical directory path. Pagination is
not an atomic snapshot of concurrent external directory mutations. The activity
invalidates pending reads on navigation, shell loss, and destruction; failures
clear the backing listing and selection together, not just the visible rows.
`FileManagerStatus` keeps listing summaries separate from operation messages:
automatic refresh cannot erase an import result or failure with an item count.
Explicit refresh, navigation, filtering, or selection clears the message and
reveals the current summary. This uses the existing footer, with no timer.

The current-folder name filter operates only on the already loaded page set;
`Ctrl+F` changes only the local Files presentation. Recursive name search is a
separate explicit action. `ShellFileSystem` walks without following symbolic
links, returns bounded batches through a typed callback, and cancels on request
or Binder-owner death. Files and desktop Start share `FileManagerSearchController`;
each search has one `FileSearchRequest` identity, including callbacks received
before the Binder start reply. Cancellation rejects late replies and signals
the original service through a bound `ShellFileSearchHandle`. The cancellation
is a one-way Binder signal, so closing the UI worker cannot discard it. It
creates no persistent index or idle scanner. Each
Files window also owns a shell-side `FileObserver` for only its current
directory. Callback bursts are coalesced into one posted reload without a
polling interval or guessed delay; manual refresh remains available when a
filesystem cannot be observed. Both the callback and the posted reload retain
the observer's generation; closing it invalidates already queued events, so
an old directory cannot supersede a new navigation request.

Files opened or dragged into another application are exposed through the
non-exported `ShellFileProvider` and a process-local capability URI. The registry
retains at most 256 entries in access order; eviction or process exit expires
an entry, so these URIs are not durable file references. A grant
records the selected path and file identity; each open is performed again by
the UserService and accepted only when device and inode still match. Drag
grants are read-only, while an explicit open grants write only when the
UserService reported the file writable. The receiving application never
receives shell access, a raw privileged path, or the UserService Binder.
`ContentProviderFileAccess` gives both shell-grant and Desktop-file providers
the same cancellation and descriptor-transfer boundary. Cancellation is
checked before opening and after the Binder reply;
a descriptor returned after cancellation is closed, and cancellation retains
its Android exception type instead of becoming a file-not-found error. This
does not interrupt an already running Binder call.
The in-task **Open with** dialog avoids Android ResolverActivity hiding the
desktop taskbar. It reads Android's current preferred handler. Its **Always**
action asks the shell UserService to write the same PackageManager preferred
activity record used by the system resolver; MagicDesk does not maintain a
second file-association database. The same dialog can include executable
Desktop Entries from the MagicDesk desktop when their standard `MimeType`
list matches and `Exec` accepts a file or URI field code. These command
profiles are one-time launch targets: they never enter Android's preferred
activity record and therefore cannot be selected with **Always**.
`FileHandlerRepository` owns blocking PackageManager and desktop-entry discovery
and preferred-handler writes. `FileOpenWithController` uses the existing UI
owner's worker and a replaceable `ContentRequestScope`; a newer file request or
owner teardown discards queued work and stale callbacks. Only result presentation
and launcher callbacks run on the UI thread. While **Always** saves the selected
handler, selection buttons are disabled; dismissing the dialog prevents a late
launch. No dedicated thread or association cache is added.

Files **Share** serializes the same `AndroidContentPayload` used by Desktop and
clipboard actions. **Import files** launches Android's `ACTION_OPEN_DOCUMENT`
surface as a managed STANDARD task. `FileManagerImportController` owns both the
pending picker and its eventual import on the Files window's existing worker.
The worker is free between launch completion and result delivery. Only one
picker can be pending per window; closing Files discards its request, including
a launch reply that arrives after teardown. Late Activity results cannot
recreate a discarded request. Result delivery atomically claims the result and
passes grant ownership to the import. The claim is closed if delivery fails or
the window closes before handoff; queued/running imports release it through
`ContentRequestScope`. Window closure or registry eviction cannot revoke access
underneath a running provider read. A failed launch response still exposes an allocated result
request id; an exception that prevents returning that id discards the request
inside the integration gateway. Dropping a file or other Android content
onto an application shortcut or a concrete taskbar instance enters the same
gateway and preserves the source grant until delivery completes.

Incoming global Android URI drops are copied into the visible Files directory.
Incomplete imports are removed when their recorded identity still matches,
conflicts gain a numeric suffix, and the incoming drag grant is released.
Cross-window import depends on the source
publishing an Android global drag session; private in-window drag gestures are
not visible to MagicDesk. For drags between MagicDesk's own Desktop and Files
windows, `FileDragPayload` keeps absolute paths in process-local state. That
typed path supports files and recursive folders without publishing privileged
paths or inventing directory content URIs; the default action is move and
holding `Ctrl` when the drag starts selects copy. Only a complete bounded
selection of readable ordinary files receives read-only URIs for drops into
other Android applications. Mixed selections are never exported as a subset.
On Android 15+, local-only selections use `DRAG_FLAG_GLOBAL_SAME_APPLICATION`,
so files and folders can cross MagicDesk windows without exposing a label-only
drag to other applications. On Android 14, those selections stay within the
source window. Files passes Android's actual drag-start result
back to the gesture owner. The built-in Console
can be prefilled with the current directory. Process-local file drags dropped
on its input insert normalized, shell-quoted paths but never run a command.
Console can open its current directory in Files, and selected output is treated
as a path only after `ShellFileSystem` verifies the resolved absolute target.
File completion lists the exact parent directory through the typed filesystem
API instead of parsing shell completion output. `ConsolePathText` uses the
same surrogate-safe prefix boundary when completing multiple names, so a
shared half-character cannot become a replacement shell path.
Optional Termux integration
uses Termux's documented `RUN_COMMAND` intent and permission; it is not
required by Files. Files can launch a new Termux-backed Console at its current
shared directory. MagicDesk atomically installs a versioned native relay from
the APK through `RUN_COMMAND_STDIN`; a random per-window token authenticates
the relay's loopback connection before any terminal bytes are accepted.
MagicDesk does not mirror or mutate the Termux application's own PTY registry.
When tmux is installed, its independent session registry is queried on demand by
session pickers, Task Manager and automation, not by resource-sampling timers.
Bounded Termux commands use its documented `RUN_COMMAND_PENDING_INTENT`
result channel. The result receiver is explicit, non-exported, one-shot, and
bounded by a timeout; long-running PTY commands do not wait for process exit.
Streaming `CommandExecution` uses one framed `ShellCommandSession` contract for
shell and Termux. `CommandStream` owns its transport: a privileged-service pipe
for shell, or an authenticated loopback relay bootstrapped through the selected
Termux endpoint. Both drain stdout/stderr continuously and close their owned
process session on cancellation; no PTY is allocated for background commands.

The Android [external-command API](run-command.md) is a separate authorized
adapter to these services. `RunCommandService` requires an Android dangerous
permission and an explicit user opt-in, captures the selected executor identity,
and resolves Shroot environments by stable catalog ID. It does not use MCP
tokens, start privileged access, or own Desktop. Background requests retain
their command channel until completion; interactive requests hand ownership to
the shared terminal or graphics service. Deadlines cancel unhanded resources,
with potentially blocking cleanup off the Android main thread. Raw requests do
not create application-history entries. Graphical configuration and application
lifetime are independent of that optional history.

Explicit commands and composite Android launches use the shared
[Desktop Entry format](desktop-entries.md).
The explicit **Run script** action in Files opens Console with a safely quoted
initial command. Console submits that authorized command once its PTY is ready.
Ordinary file opening uses the selected file handler and does not take the
Run script path.

Normal application launch continues to reuse an existing task. The explicit
**New window** action first validates the resolved Activity manifest, then
requests `NEW_DOCUMENT | MULTIPLE_TASK` and tracks the exact returned task ID.
For `singleTask`, `singleInstance`, and `documentLaunchMode=never`, a ready
privileged service supplies one current task snapshot across displays. The
first instance is allowed; an existing matching Activity in the same user
profile rejects another-instance request before dispatch, without moving its
task. Unavailable task observation fails explicitly instead of assuming no
instance exists. This preparation is shared by managed and independent
launches; reuse remains available. Without shell access, a restricted Activity's
explicit new-instance request fails rather than silently reusing a task.
Ordinary local reuse and Activities supporting multiple windows do not acquire
a privileged-service prerequisite. Files supports multiple windows directly.

During a managed Activity dispatch, `ShellTaskLauncher` owns a bounded
`ShellActivityLaunchScope` carrying its resolved launch identity. All workspace
phone-migration and Activity-handoff guards consult that shared provenance,
including on Binder callback threads; caller Intent flags are not reliable
evidence of an explicit MagicDesk launch. The scope ends on success or failure,
without a timer, and does not bypass unrelated Activity admission policies.
It recognizes the requested component and its alias target, not every Activity
in the package (except explicitly package-scoped selection surfaces).

## Viewport And Desktop Chrome

The desktop uses one `WindowMetrics`/WindowInsets viewport model on every
display. `DesktopViewport` supplies stable system geometry to the protocol-neutral
`ShellLayout`. `ShellLayoutScope` owns revocable, namespaced surface bindings.
`DesktopShellLayout` submits the complete native panel set; `ShellPanelPlacement`
describes ordinary panels and owner-relative popups. The immutable
result supplies taskbar, icon-grid, popup and application work-area bounds through
`DesktopLayoutController`. Separate layout instances isolate Desktop and nested
graphical scopes. Precise edge exclusions coexist with the rectangular work area;
absolute partial reservations and stacked exclusive zones retain distinct
semantics. See [shell layout](shell-layout.md) for the model and protocol boundaries.
A phone desktop is an explicitly selected primary HOME session. Stable
status/navigation insets bound content on every edge, using the bars'
ignoring-visibility geometry; a dedicated external display normally has no such
insets. Native panels keep separate content, paint and physical-output rectangles.
A panel spanning a stable content edge without gaps may extend its background
through that edge's system inset. A floating gap or external reservation moving it inward
suppresses this extension; controls remain within content bounds. Attached
windows do not subtract system-bar or IME insets a second time.

When fullscreen policy or auto-hide conceals chrome, each bounded panel
becomes transparent and limits input to a reveal strip on its configured
physical output edge.
Input remains bounded to the individual strips, never a rectangle spanning gaps
between panels. Expansion restores that panel's resolved background and content.
Managed and independent fullscreen tasks share the edge-reveal policy;
phone Home can also explicitly reveal chrome. The transparent,
non-input chrome host stays structurally stable. IME visibility can hold an
auto-hiding panel open but does not relocate it: the keyboard may cover an edge
without moving desktop chrome. The same model serves phone and external displays.

The wallpaper is a full-display backdrop outside the inset-aware desktop
content layer. Status-bar and viewport changes therefore reposition icons and
windows without rescaling the wallpaper. `WallpaperAsset` owns validated source
metadata, an immutable static poster with `Bitmap.DENSITY_NONE`, and encoded
media when playback needs it. `WallpaperView` center-crops against fixed physical
output dimensions, not its transient inset-adjusted viewport. Posters must not
inherit process density: `BitmapDrawable` would scale their intrinsic size a
second time on another display. Wallpaper readiness is published only after the
selected poster reaches a committed frame; reload generations discard stale callbacks
without a settling delay. On the phone display, opaque desktop-chrome backdrops
cover the reserved status- and navigation-bar insets above the wallpaper.
Android can therefore keep normal system-bar behavior for HOME and freeform
tasks without exposing bright wallpaper strips around snapped windows.

Each `WallpaperView` owns its animated-image decoder, muted `MediaPlayer` and
`SurfaceView`, or AGSL `RuntimeShader` drawable. Immutable theme assets may be
shared; playback never is. `ShaderWallpaper` is the typed description;
`ShaderWallpaperAsset` validates compilation and bindings during worker-side
appearance preparation. Per-output shaders are also constructed on the wallpaper
executor. `ShaderWallpaperDrawable` owns vsync callbacks with a capped frame
rate; frames update time and explicitly bound cached device signals, with no resource loading or
recompilation. The same resource snapshot and preview lifecycle owns its source,
poster and textures. The existing wallpaper executor performs image decoding, while video preparation
and first-frame readiness use Android callbacks with a bounded failure timeout.
Visibility, attachment, display state, appearance, animator settings and power
saving drive playback without polling. Only an active AGSL drawable requests
Choreographer frames. Loss of HOME focus
does not stop animation. Stopping releases playback, cancels shader frames and restores the poster;
stale completions cannot attach a previous source. Playback failure leaves the
desktop usable and records a compatibility event. Wallpaper hosts own no input,
focus, shell reservation or task transition.

`AppearanceSignalRegistry` reference-counts allowlisted normalized sources across
outputs, independently of AGSL. `AppearanceSignalSources` lazily supplies public
Android RAM and battery observations and aggregate counters from the already
authorized shell service. Battery uses one shared receiver; CPU/RAM use a shared
worker only while requested, with one-second telemetry samples and three-second
expiry. Missing shell access stops CPU sampling without affecting other sources.
No application-start hook initializes telemetry. `ShaderSignalBindings` owns
per-output smoothing and preallocated uniform arrays; the drawable acquires its
lease only on playback and releases it on stop. Themes without bindings never
construct this adapter or acquire sources. `appearance.get` exposes active
subscriptions without triggering collection.

The desktop icon grid is a non-focusable container with the default View focus
highlight disabled. Its click listener must not make the whole grid an
automatic keyboard focus target and paint a translucent rectangle over the
wallpaper. Individual desktop items retain keyboard focus and highlighting;
the container does not block descendant focus or change click/drop handling.

The desktop chrome host is a translucent, normally non-focusable `MULTI_WINDOW` task in its
own root-level organizer area beside Android's standard task workspace. Each
native panel is a bounded child application window under that one chrome token,
so a foreground application that suppresses non-system overlays cannot
suppress it. Empty task bounds fill the area without a freeform caption.
Both the area and host task are `alwaysOnTop` in `MULTI_WINDOW` mode:
Android 15+ ignores this flag in fullscreen mode. Native DisplayArea ordering
keeps chrome above the application workspace and below system windows and IME.
Keeping chrome outside the workspace also avoids the root-task sibling cast
in `ActivityStarter` during app-owned child/result launches.
The shell disables that Activity's Android 15+ ActivityRecord input sink, so
only the individual panel windows' bounded touch regions receive input; pointer
events outside them continue to the desktop and application windows.
Auto-hide keeps both the non-touchable host and panel window frames stable.
Each panel has a persistent transparent child window for its physical reveal
edge. The content window contains the panel, dock effects and the space
between the dock and screen edge. Hidden content is transparent and
non-touchable. Keeping the edge window stable prevents panel relayout from
cancelling a stationary hover. These windows handle panel and reveal input
without synthetic events or polling, keeping input aligned with the visible
edge. `ShellDesktopSurfaceOrder` only orders fullscreen planes within the
application workspace. Chrome's
framework priority handles window relayout without retaining its organizer
leash, manually setting a surface layer, or appending a transaction or wait to
application operations. Both the visible panel and hidden
reveal edge share this ordering policy rather than separate layer fixes in
taskbar clicks, Alt+Tab, overview, or MCP.
Start, context menus, notifications, and dialogs reuse this
same application token rather than creating another infrastructure task.
`DesktopWorkspaceScene` reads the complete top-first physical display snapshot
before workspace ownership filtering. The first visible HOME, freeform or
fullscreen task determines normal or concealed taskbar presentation; PiP and
infrastructure do not replace the scene underneath. A covered freeform window
cannot reopen chrome. Managed and independent fullscreen tasks retain the same
reveal edge, while task lists and window operations remain limited to
session-owned tasks. Presentation does not transfer keyboard focus to HOME
underneath PiP. The taskbar's
shared controller measures the actual task viewport on every display and
reserves one slot for an overflow menu when task or pin icons no longer fit.
Overflow entries retain the same exact-task actions and context targets as
their ordinary taskbar icons; screen drivers do not implement separate sizing
or task-switching behavior.
The phone desktop also exposes the hidden taskbar through a touch edge gesture.
It uses Android's configured edge and touch slop, is scoped to display 0, and
feeds an explicit reveal state into the shared controller. Phone Home navigation
uses the same state to reveal a hidden taskbar, including over an independent
fullscreen application. Explicit reveal overrides fullscreen concealment
without changing application focus, bounds or ownership. The transient reveal is
dismissed by the next taskbar action or outside touch rather than by a timeout.
An open Start menu holds the taskbar visible independently of that reveal until
the menu closes. IME visibility independently holds the taskbar visible.
When automatic hiding is enabled, the same existing pointer-edge state machine
reveals it without introducing a second overlay or polling loop, and window
placement uses the full viewport. IME and other forced-visible policy still
take precedence. This also avoids tying shell visibility to Activity focus
callbacks.

`Win+D` gives the live desktop-host focus state precedence over the cached task
snapshot. A newly opened system activity can therefore never make a stale
"no visible app" snapshot select Restore; MagicDesk exposes the taskbar and
raises the desktop host first. Once the watcher confirms that state, the next
`Win+D` can restore the previously visible freeform stack normally.

## Shared Application Catalog And Start

`FullscreenStartController` embeds `StartMenuContent`, the same contents used by the
desktop `StartMenuController` popup. `StartActivity` opens the fullscreen version
from Control Panel without acquiring HOME or requiring Desktop.
`PhoneHomeActivity` and an unassigned secondary HOME use that ordinary
launcher controller without creating Desktop. Each Start owns its view, query,
page, selection and focus; multiple Starts can be visible at once. Recent is
owned by `StartMenuContent`, not its host: `RecentLaunchScope` selects global
Desktop or Independent history from the resolved launch destination and mode.
`RecentApplications` serializes IO and caches both scopes; `RecentApplicationStore`
stores bounded, deduplicated Desktop Entry recipes in separate private directories.
Built-in recording occurs at successful launch completion, independently of the
caller's UI lifetime. `BuiltInRecentLaunch` strips ephemeral task/session/display
identities; terminal backend remains meaningful, and X11 records its original
recipe with the explicit launch scope after readiness. Live managed-task focus
can update Desktop history without assigning independent tasks to that history.
Shell availability never changes the meaning of Recent.
Recent offers scope-specific history clearing with confirmation and per-entry
removal through the existing context menus. These operations delete only private
history records, not source shortcuts, pins or running tasks. Edits share the
launch-history IO queue and notify visible Start instances; the action retains
the scope selected when it was opened even if the destination changes.

Fullscreen Start also has a separate Running tab. Ordinary HOME Running is
derived from its local tasks through `HomeRecentApps`. It includes
launchable phone applications opened from notifications, filters HOME and
shell surfaces, and deduplicates application identities in snapshot order.
Phone HOME requests a typed task snapshot only when resumed or when Running is selected;
stopped instances discard pending results. An unavailable snapshot is an error,
not a fabricated empty history. The phone uses only application search, without
constructing the desktop file-search worker. `ApplicationCatalog` is shared by
all Start windows in the application process/profile. Android discovery uses
`LauncherAppRepository` on a worker, invalidated by `LauncherApps.Callback` and
resource-configuration changes, not a timer. Termux discovery uses the selected
RUN_COMMAND endpoint through `TermuxApplicationSource`, refreshed when Start
opens or automation requests its catalog. Both sources use
`ApplicationCatalogSource`: independent loading/error state, immutable last-good
results, joined in-flight requests and generation-checked completion. Android
apps load independently of Termux. Cold Start shows a loading state until Android
discovery completes.
Reopened Start immediately uses cached results. Endpoint identity/availability
changes clear Termux entries and invalidate old callbacks; transient read errors
retain the last successful list. The catalog merges application identities and
sorts entries, while each Start retains its own navigation and subscribes only
while presented. Grid capacity follows each panel's measured viewport, including
keyboard resizing.

Guest discovery isolates unreadable environments: healthy entries remain
available, while the source snapshot and compatibility diagnostics retain the
per-environment errors. A partial result is distinct from a failed query, which
retains the last successful snapshot.

`ApplicationIcons` owns optional Linux artwork independently of discovery.
`ApplicationIconBinding` subscribes attached views, including recent launches,
file shortcuts and application pickers; X11/Wayland hosts retain a subscription
for their task presentation. Native client artwork takes precedence. Keys retain
the Termux package or immutable guest environment ID; history never borrows an
icon from another installation. `ApplicationIconCache` coalesces demand, pins
active entries and retains at most 256 idle hits/misses per provider. Source
changes discard stale completions; explicit refresh and reconnection retry
misses and failed reads. Termux uses bounded four-icon RUN_COMMAND batches;
guest artwork reads only requested environments through their inode namespace.
Metadata discovery does not load image files. Decoding runs on an artwork worker,
and renderers read immutable bitmap snapshots. Completion updates only the
subscribed image or task presentation, without rebuilding lists. Binding adds
no synchronous file or command I/O and requires neither Desktop nor an X server.
See [X11 applications](x11.md#applications) for the bounded PNG lookup policy.

`LauncherExports` exposes saved Linux catalog entries to Android launchers.
The build-only `launcher-template` module supplies one unsigned Activity APK;
bounded manifest string-pool substitution and PNG replacement produce an entry
without an on-device compiler or APK library. `LauncherApk` writes aligned ZIP
entries and APK Signature Scheme v2 framing using Java cryptography and an
installation-local AndroidKeyStore RSA key. SDK `apksigner` independently
verifies the format in unit tests. Installation and removal use Android's
interactive package UI and `GeneratedContentProvider` read grants, never a
privileged installer fallback.

`LauncherExportStore` retains a random package identity, source path, Android
profile, executor package/UID and signing-certificate digest in private storage.
The exported `LauncherEntryActivity` accepts no command or target from extras:
Android's result-calling package and its certificate must match a private record.
Each activation resolves the live source and validates the retained executor
identity before dispatch through `ApplicationEntryLauncher` and `ToolLaunchTarget`.
For independent launches, completion follows the real window handoff or command
dispatch, not coordinator admission. This keeps transient callers alive during
Linux startup; terminal recipes use the same built-in window gateway in both
launch contexts.
Deleted entries and changed identities fail explicitly. Exporting does not
acquire Desktop, HOME, input or MCP, and Termux entries require no shell service.
Artwork uses the shared icon service; history and launcher registration have
independent lifetimes. Clearing app data invalidates exports and their signing
key; old packages must be removed and recreated.

Every Start has a compact launch-display selector alongside search. **Current**
means the display hosting that instance, not the selected input display. Choosing
a destination preserves query and navigation, does not dismiss Start, and changes
neither HOME nor input ownership. The display catalog is read when the selector
is opened; there is no new periodic observer. Explicit choices retain the live
display identity and are revalidated at launch, so a disconnected destination
cannot silently redirect an application to another screen. Changing the target
or launch mode refreshes Recent and search without clearing the query; a managed
mode resets to Auto when the selected destination has no Desktop. Start's application
context-menu launches use the same captured destination. Fullscreen remains the
existing window presentation, not a separate task-ownership mode.

`DisplayAppLauncher` selects the launch path from current destination ownership,
not the Start host's role. Ordinary destinations do not apply Desktop density
or saved window bounds. A one-shot typed snapshot identifies an existing task
on another display only if no matching destination instance exists. It moves
through `TaskRepository.moveTaskToDisplay` before the ordinary launcher Intent
is delivered, avoiding cross-area Intent reuse inside Android's ActivityStarter.
Standalone fullscreen Start uses the same application grid plus a running-task
section; each task retains its own identity and title. Display identity is checked
inside the transfer owner before mutation.
Ordinary HOME exposes controls, touchpad and production Close. Phone HOME navigation
releases the touchpad request so recovery cannot cover the requested Start;
navigation on other displays does not change the phone touchpad.
Its window-local automation registry does not register a desktop host.
