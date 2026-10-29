# Automation And Diagnostics

[Architecture overview](architecture.md) | [Documentation](README.md)

Shared command, observation and diagnostic ownership. For the public tool contract and examples, see [automation](automation.md).

- [Automation boundary](#automation-boundary)
- [Diagnostics](#diagnostics)
- [Desktop Self-Tests](#desktop-self-tests)
- [Task-Stack Test Guards](#task-stack-test-guards)

## Automation boundary

### Actions And Observations

- `DesktopAutomationController` is the typed action boundary for Desktop
  automation. It validates JSON arguments, delegates to shared services and session,
  task, window, capture, and UI controllers, and returns a uniform
  `DesktopAutomationResult`. It does not implement a second desktop policy.
- `DesktopAutomationStateReader` exposes immutable snapshots of runtime,
  displays, tasks, launchable applications, MagicDesk-owned UI, diagnostics,
  self-test state, and the actual input window above each focused application.
  `DesktopWindowObservation` joins that shell-owned input state with bounded
  crash/ANR state from the existing task observer, so a surviving
  `ActivityRecord` is not mistaken for a usable application behind a system
  error dialog. Task and application queries share bounded filtering and
  cursor pagination.
- `DesktopAutomationEventJournal` retains at most 256 process-local structured
  events and provides the condition variable used by event-driven automation
  waits. An event receives its cursor id atomically with publication, so
  concurrent producers cannot publish an older event behind a reader's cursor.
  `DesktopAutomationTaskEventTracker` derives task lifecycle, display,
  focus, top-activity, mode, bounds, and visibility events from snapshots
  already delivered by `DesktopTaskWatcher`; it does not register another task
  observer. Compatibility reports include at most the newest 64 events within
  a 24 KiB section, without adding persistent telemetry.
- `DesktopWindowTransitionProvenance` correlates semantic MagicDesk requests,
  application immersive callbacks, and activity-handoff corrections with those
  existing mode-change events. Uncorrelated supported mode changes are labeled
  `unattributed`, not attributed to firmware. Evidence distinguishes a matching
  recorded operation from an observed application request; neither is a framework
  transaction-token acknowledgement. Mode events include previous geometry,
  Activity, density, visibility and focus, and the last immersive observation with
  its age and policy disposition. Unknown observations remain unknown. The bounded
  history uses existing callbacks, without a timer or additional observer.
- `DesktopAutomationUiRegistry` is populated by the controllers that own live
  desktop `View` objects. `DesktopUiGateway` is still the only bridge to the
  Activity and marshals snapshots and semantic actions onto the UI thread.
  Invoking an element delegates to its existing click or long-click listener;
  automation therefore cannot grow a second Start, taskbar, or menu policy.
### Android UI And Input

- `AndroidUiAutomation` provides independent Android UI automation on API 34+.
  `ShellUiAutomationHandle` owns a Binder lifetime token; `ShellUiAutomation`
  connects one `UiAutomation` lazily under shell identity. The focused
  `FrameworkUiAutomationApi` owns hidden construction, connection, window-to-task
  identity and injection signatures. Existing accessibility services are not suppressed; the shortcut
  service still requests no window content. Another automation owner is an
  explicit conflict, never displaced. Idle expiry, explicit release, backend
  closure and Binder death release the connection and its bounded node cache.
  Four snapshots of at most 256 nodes retain 60-second handles. Identity evidence
  is immutable; recycled list rows cannot silently become another action target.
  Actions refresh separate node copies, preserving snapshot text revisions.
  `AndroidUiScope` selects exactly one display or task and can narrow it to a window
  or node subtree. `AndroidUiWindows` owns one cache-invalidated accessibility
  inventory per observation/action. Task selection uses confirmed Android task
  ids and discovers the current display without a task observer, foregrounding
  or package heuristics. Missing target windows and unknown/ambiguous ownership
  remain incomplete, including for absence waits. The same inventory validates
  captured display/window/task identities before actions and subtree refreshes;
  moved windows require fresh handles. Retained text reads stay immutable.
  Exact selectors search up to 4096 candidates before projecting bounded text
  previews; shared `UiText` pages retained text independently of traversal.
  Password values and lengths remain redacted. Public API 34 cache invalidation
  precedes each capture; event generations distinguish concurrent changes from
  stable traversal. Missing, unstable or truncated traversal cannot prove absence.
  Accessibility events wake UI waits without another task observer or a periodic
  UI poller. Waits release the action lock, so concurrent actions can satisfy them.
  Raw UI text never enters the desktop event journal or compatibility report.
- `AndroidAutomationInput` submits explicit-display touch gestures and key chords,
  always releasing pressed input on failure. It does not route physical devices
  or change HOME, window policy, animation settings or the hardware cursor.
  `AutomationAwakeLease` owns a timed public screen wake lock independently of
  Desktop. It never changes screen timeout or unlocks/wakes a sleeping device;
  exact tokens protect renewal and release from stale commands.
- `DesktopAutomationTraceManager` defines a trace as a baseline in the same
  bounded event journal plus final state and task snapshots. It adds no task
  observer and no persistent log. Exact UI waits use journal notifications and
  a bounded recheck for `View` state changes that Android does not publish.

### Linux Sessions And UI

- `UiAutomation` dispatches the shared semantic contract to Android or
  `LinuxUiAutomation`; `UiSelector` and `UiText` own common matching and paging.
  `AtSpiClient` owns toolkit protocol details, not display placement or input
  injection. Linux handles retain a unique bus owner, object identity and event
  generation. Actions refresh identity; observation never falls back to pixels.
  Event subscriptions use AT-SPI interfaces, not toolkit-specific object paths.
  A capture changed by an event is reconciled immediately; otherwise waits sleep
  on the connection event future. There is no periodic UI scan.
- `LinuxLaunchServices` prepares optional automation alongside audio and gamepad
  services. `LinuxAutomationLaunch` admits bounded, authenticated connections
  through `ExecutorEndpointBroker`, shared with Linux audio. The existing
  `linux-settings` helper hosts libdbus inside the selected guest session, after
  its login identity, namespace and D-Bus address are established. Native Termux
  uses the same helper; PRoot and prepared chroots only map its directory and
  environment. Missing guest services do not trigger host-bus fallback.
- `LinuxBusConnection` owns one connected process generation, its bounded typed
  replies/events and dependent console streams. Transport loss invalidates that
  identity. `ExecutorSocketChannel` waits on data or local cancellation, with a
  deadline only while assembling a started frame. Socket authorization and
  options remain with the captured executor. `DbusAutomation` is the raw typed
  diagnostic adapter; arbitrary bus calls require shell authority.
- `LinuxSessionCommands` exposes connected identities, captured launch context
  and exact-argv launch. `LinuxCommandStream` reuses the existing static PTY
  helper's pipe-shell protocol for headless consoles inside that same guest.
  `DesktopAutomationConsoleSessions` retains directory, environment, output and
  cancellation semantics across Android and Linux, without another job runner.
  Guest loss cancels dependent streams; it never switches execution to Android.
- Explicit `ui.prepare` may enable guest accessibility status under the separate
  MCP `linux_accessibility` grant. It never edits launch flags or restarts apps.
  Explicit launcher flags and environment use ordinary shell authority through
  `linux.launch` or consoles. A grant cannot manufacture an accessibility tree
  that an application/toolkit does not publish. Neither adapter is a Shroot
  syscall interception layer or a workaround for PRoot kernel limitations.

### MCP And CLI Transports

- `MagicDeskMcpRuntime` is owned by `MagicDeskRuntimeService`. When explicitly
  enabled, it starts a bounded Streamable HTTP server on literal
  `127.0.0.1:8765`, plus an optional independently authenticated listener bound
  to one selected private IPv4 interface and port. Network callbacks reconcile
  address changes only while network access is enabled; there is no poller.
  Network listener failure does not stop loopback or close the shared backend.
  Stopping MCP closes its listeners, client sockets and HTTP workers.
  A closed transport cannot be restarted; enabling it again creates a new one.
  A user launch may first create the service in automation-only mode so an MCP
  client can connect before the privileged service is available. That mode owns only the
  foreground service and MCP transport. The same service is promoted in place
  as requested services become available; Desktop coordinators initialize only
  for an explicit Desktop session, not merely because the privileged service connected.
  `AutomationCommandRuntime` owns `AutomationCommands`, including retained
  headless shells, UI automation and transfers, independently of MCP enablement.
  `AutomationCommandCatalog` describes the commands for both MCP and the built-in
  CLI. `AutomationCommandArguments` checks basic argument shape; individual
  services own semantic validation. `MagicDeskMcpBackend` adapts results and
  adds MCP permission descriptions. `McpAuthorizedBackend` checks a live, listener-specific
  `McpAccessPolicy` before every command. The complete catalog and permission
  descriptions remain stable when grants change; unknown tools fail closed.
  Local and network tokens and permission sets are independent. The optional
  network transport is HTTP, not TLS, and requires a trusted test LAN or VPN.
  External UI inspection/waits require `content`; UI actions, injected gestures
  and awake leases require `input_tests`. Content authorization is checked again
  before returning a result that may have outlived a permission change.
- `MagicDeskCli` runs from the APK through Android `app_process`. Its parser
  derives options/help directly from the shared catalog and calls the same
  executor through a lazily created loopback socket. No HTTP or MCP settings
  are involved. MagicDesk-launched shells inherit an ephemeral endpoint and
  secret through `CommandShellEnvironment`; the listener checks this capability
  before dispatching any command. The generic entry script contains
  no secret. Shell binding is configured once per command-runtime/service pair,
  separately from framework/Desktop setup. Termux launch preparation installs
  the same entry script in its own environment, without making Termux a
  prerequisite for shell execution. The channel has bounded messages/workers,
  no polling, and closes with the runtime. Process restart invalidates old
  channels. The CLI never retries an indeterminate action.
- `native/command-client` supplies a static argument/byte transport and an
  invocation wrapper independently of guest execution. `AutomationCliWire`
  invokes the same Java CLI parser, including caller-side file/stdin reads.
  `AutomationCommandLeases` revokes delegated keys on registration-connection
  EOF or runtime shutdown; captured executor replacement rejects stale requests.
  A guest supervisor retains the owner FD for its supervised tree, while native
  Termux/PRoot use `magicdesk-connect` for a wrapped invocation. Lease-owned
  headless consoles close with that owner; other shared services keep their
  existing lifetime contracts. The bridge adds no Desktop or shell startup
  prerequisite: each command still checks its own capabilities. Guest client
  mappings and shortcut flags carry no persisted authority.
### Prompts And Notifications

- `UserInteractions` owns script prompts and actionable notifications, lazily
  and independently of Desktop or MCP enablement. `UserInteractionRequest`
  validates bounded declarative content; `UserInteractionRegistry` retains
  process-local, non-consuming results and event-driven waits. Completion is
  first-wins and notifies outside its lock. Pending entries cannot be evicted;
  one-shot deadlines, explicit close and runtime exit release UI. Prompt
  Activities use `ToolApplications`, are not launcher entries or restorable
  windows, and retain drafts across configuration changes. App-private
  notification PendingIntents return only selected IDs or bounded inline text;
  no executable callback or extra privilege is attached to a button. All four
  commands require MCP content access, rechecked after a wait. Replies are not
  published in diagnostic events. CLI `--field` projects one returned JSON field
  without introducing a second command schema or a JSON-parser dependency.
### Device Checks, Transfers And Updates

- `AutomationDeviceState` shares on-demand awake/lock prerequisites between MCP
  and the self-test launcher. Build, process and installation identities are
  separate observations. `DesktopSelfTestResult` persists an atomic JSON result
  keyed by run id; current progress never borrows another run's saved report.
  Exact-run waits can use that saved result after process restart. Expiration of
  a wait is an observation outcome, not cancellation of the operation.
- `AutomationFileTransfers` owns at most 16 durable transfer journals and no
  persistent descriptors. `ShellAutomationTransferStorage` reuses Files'
  identity-verified IO and publishes completed uploads from a sibling temporary
  file after length and SHA-256 verification. Sequential chunks are bounded to
  128 KiB; acknowledged retries must contain identical bytes. Aborting removes
  only the original incomplete file. Downloads detect identity/size/mtime
  changes and provide a final digest for the client. This protocol is separate
  from desktop, task and window policy.
- `MagicDeskAppUpdates` records bounded per-operation installer receipts.
  `ShellAppUpdate` stages a same-package, same-signer APK using the shell-owned
  Android `PackageInstaller`; framework profile context belongs to
  `FrameworkUserApi`. The app persists the session identity before commit and
  hands the staged operation to `ShellAppUpdateService`, a separate,
  one-operation privileged worker. The ordinary command service stays non-daemon.
  `FrameworkPackageInstallerApi` explicitly requests package replacement for
  shell sessions and receives Android's installer result through a
  typed Binder callback in the surviving worker. A descriptor grants access
  only to one app-private result file; `AppUpdateReceipt` bounds and checksums
  that result so a partial write remains pending, not success. The worker
  waits on callback events with a deadline and exits after the operation.
  On success it starts the installer-only `AppUpdateResumeActivity`, protected
  by the signature-level `INSTALL_PACKAGES` permission. Its `Theme.NoDisplay`
  entry creates no window, starts enabled automation and immediately finishes.
  It is not `noHistory`: Android may otherwise remove a newly requested Activity
  on visibility loss before its cold process has bound and called `onCreate`.
  Explicit completion owns this entry's lifetime for both UID 0 and UID 2000.
  `FrameworkUserApi` launches it with the real shell identity and the requesting
  Android user, not the worker's synthetic application context.
  This avoids depending on background-service autolaunch while leaving
  `MagicDeskRuntimeService` unexported. Ordinary applications cannot invoke the
  entry. Automation-disabled state is still honored; no HOME is acquired.
  `MagicDeskUpdateReceiver` retains protected `MY_PACKAGE_REPLACED` for updates
  performed by other installers; those broadcasts can be blocked by firmware.
  Client reconnect logic is outside the Android process. Reconnect expiration
  or a missing installer callback remains an unknown outcome, not permission
  to repeat installation. No update worker runs during ordinary operation.
### Android Launch And Content Delivery

- `AndroidIntegrationGateway` is the single application boundary for typed and raw
  Android intents, semantic URI/file/share operations, published shortcuts,
  notification `PendingIntent` actions, Activity results, and external App
  Functions. Desktop UI and MCP adapters both enter this boundary.
  `AndroidDesktopAction` gives each user-visible operation one semantic id and
  source independent from the UI, MCP, or App Function surface that requested
  it. `AndroidDesktopActionCatalog` owns the bounded set of public system
  actions and their typed parameters. `AndroidDesktopActionDispatcher` is only
  the asynchronous UI adapter; it does not implement a second launch policy.
  Content drops from Desktop and taskbar use this same adapter with a
  UI-owned `ContentRequestScope`. Closing that owner cancels queued deliveries;
  running deliveries release their source grant before scheduling a UI result.
  Resource release never depends on the callback running, and queued callbacks
  check their owner again on the UI thread. A release error after the gateway
  has returned does not replace its delivery result; the dispatcher records
  `CONTENT-GRANT-RELEASE-001` separately instead of suggesting that a committed
  action needs to be repeated. The existing executors retain their
  ordering; no additional worker or polling loop is created.
  `AndroidIntegrationRequest` owns Intent parsing and validation;
  raw Intent URIs are an input form rather than a parallel executor. Direct
  launches cross the privileged task-launch boundary as full Parcelable Intents,
  preserving `ClipData`, grants, and typed extras. Discovery and App
  Function framework calls have shell-side adapters, but desktop placement and
  task reuse still enter the production launch coordinator.
  `ToolLaunchTarget` selects the destination independently of Intent delivery
  and rechecks Desktop ownership before dispatch. Non-Desktop destinations use
  `OrdinaryActivityLaunch` and `FrameworkActivityLaunchApi`, without window
  organizers, HOME or session setup. Background placement uses the privileged service; app
  authorization, URI grants, chooser/resolver policy and Activity-result relay
  ownership are shared with managed launches. Ordinary dispatch returns
  acceptance without an invented observed task; MCP clients confirm their
  requested UI with accessibility events. Only managed placement accepts
  relative window bounds or exact-task presentation parameters.
  `AndroidActivityResolution` distinguishes a real handler from Android's
  synthetic resolver without relying on an internal class name. Shared query
  flags include MAIN+LAUNCHER/LEANBACK_LAUNCHER entries without requiring DEFAULT;
  concrete launcher targets are pinned before dispatch. Other implicit Activity
  queries retain Android's default-handler filtering. Its typed
  `AndroidActivityAuthorization` independently evaluates enabled/exported
  state, same-package access, and permissions granted to the MagicDesk app.
  Shell is only a placement authority: denied app-identity access never crosses
  that boundary. Public handlers without a required permission or URI grants
  take the direct shell path. Choosers, system resolvers, content-grant Intents,
  and allowed handlers requiring app identity use an immutable one-shot `PendingIntent` created by the app and
  sent by shell with the requested display, activity type, mode, and bounds.
  The token preserves app authorization and URI grants; shell contributes no
  target authority. URI permissions travel with the Activity launch, without
  separate package-wide grants. A focused compatibility adapter owns the Android 15 and 16
  background-start option semantics for both creator and sender.
  Activity-result requests require an app Activity lifecycle. They retain the
  nested target in `AndroidActivityRelayStore`; shell receives only an opaque
  id and places the relay Activity. Relay ids use an atomic `ready -> claimed`
  lifecycle: Android task handoff may instantiate the relay Activity twice, but
  only the first instance can execute the payload. Claimed tokens remain in the
  same bounded store without retaining their Intent payload. The exported
  relay Activity requires `MANAGE_ACTIVITY_TASKS`, so only the same privileged
  task-launch boundary can consume those one-shot ids. Broadcast and service
  starts require the shell grant because they have no visible UI.
- `AndroidLaunchSpec` keeps the task's semantic target separate from the
  Activity used to execute a launch. `AppTaskController` derives task reuse
  identity from the concrete component for direct Intent launches. System
  selection surfaces use package-scoped identity because their published
  launcher component may hand off to another Activity in the same package.
  Result relays always create a distinct transient task under the relay's own
  identity; they never normalize, move, or reuse an existing task belonging to
  the result target.
  Published shortcuts follow the same separation:
  Android may redirect their metadata Activity to another Activity in the same
  app, so both fresh-task observation and task reuse are package-scoped while
  execution remains bound to the shortcut id. The component observed on the
  created task, rather than the optional published metadata component, becomes
  the mode-guard identity. Direct fresh Intent tasks receive the concrete
  Intent; an exact reused Intent task receives it as a task action.
  `DesktopActivityLaunchResult` carries one closed outcome through the
  UI-thread boundary: an observed managed task, an explicitly unmanaged
  acceptance, or a definitive/indeterminate failure. MCP and Android
  integration callers then use `DesktopTaskLaunchObservation` to confirm that
  exact task's typed STANDARD/display/mode topology through the existing event
  journal and one-shot repository snapshots. Resolver and chooser tasks omit
  a final component assertion because the user's selection is not yet known.
  A supplied completion owns error presentation; lower launch layers return
  the result without independently displaying a failure. Direct UI launches
  report through their launch context. Session-owned tools can discard late
  results when the resource they were presenting has already closed.
  `DesktopLaunchPresentation` is the sole transport for mode, relative bounds,
  explicit `reuse`/`new` instance policy, and an optional exact task id.
  Instance policy is not inferred from raw Intent flags. Relative bounds use
  the shared `0..10000` work-area scale and are valid only for windowed
  launches. An exact task id always means reuse, requires its explicit current
  mode, and lets content drops and automation deliver to an already managed
  task without moving or resizing it. Initial bounds are invalid with an exact
  task. A missing or mismatched exact task is a failure and never falls back to
  creating another window.
### Activity Results And Grants

- `AndroidActivityResultStore` owns the bounded lifecycle of document picker
  and other Activity results. Synchronous automation waits use `EventDrivenWaits`;
  UI owners subscribe to request readiness without occupying a worker while a
  user chooses a document. Notifications run outside the registry lock, including
  failure, discard, and eviction; subscribing after completion cannot miss the
  result. There is no result poller. A returned content grant is retained only
  while its terminal result is owned by the store or a claimed import.
  Claiming removes the result atomically from the bounded registry without
  revoking its grants; unrelated requests cannot evict an active import's access.
  `PersistedUriPermissions` tracks typed
  grant ownership independently of result JSON; consuming or evicting one
  result releases only permission flags no remaining result owns for that URI.
  Stored result data and every reader's projection own independent nested JSON
  objects. Consuming a result releases its grants before serializing the reply,
  so a response failure cannot strand already-consumed permission ownership.
  Files subscribes to the picker result, claims it, imports the selected URIs
  through its normal typed filesystem controller, and closes the claim after
  the copy completes. Because request ids are process-local,
  process startup releases result grants orphaned by an earlier process death.
- `AndroidActivityResultData` owns the bounded projection of returned Intent
  data, separate from request lifetime and grant ownership. URI addresses and
  identity fields are preserved exactly or rejected when oversized; they are
  never shortened into different identifiers. Grant acquisition uses the same
  validated URI snapshot exposed to consumers. Extras inspect at most 32 keys,
  including unsupported or unreadable entries. Optional data loss is reported
  as `extrasTruncated`; text prefixes retain complete UTF-16 surrogate pairs,
  and unsupported or non-finite numeric values do not discard later extras.
- `AndroidActivityCompatibilityHistory` records at most 64 Activity launches
  that already occurred. It keeps presentation, authorization, observed task
  topology, outcome, and only the URI scheme; full Intent extras and content
  URIs are excluded. The compatibility report, MCP, and developer Activity
  Explorer read this same process-local history. Activity Explorer resolves
  current exported handlers and launches them through the production gateway;
  it has no private task command or vendor component list.
  Nested diagnostic fields are copied when recorded; later edits to an
  operation response cannot modify the saved evidence. Result and event
  snapshots likewise never expose the registries' internal JSON objects.
### Files And Terminals

- Direct file reads, file writes and shell execution have separate permissions.
  `DesktopAutomationFileTools` delegates to the same typed `ShellFileSystem`
  service as built-in Files. `DesktopAutomationConsoleSessions` owns a bounded
  set of lifecycle-scoped `ShellCommandSession` instances and
  closes them with the shared command runtime. These non-PTY shells retain
  their environment and directory. The native pipe relay owns each UNIX session
  and frames stdout/stderr separately. `ShellCommandOutput` consumes a boundary
  on both channels before committing command status; binary stdout is never
  decoded as text. `TerminalOutputTarget` resolves peer identity and
  `TerminalOutputStream` delivers bounded blocks, optionally encoding PNG as
  Kitty. Ordinary commands retain bounded combined text output. Closing or
  cancelling the source shell terminates its jobs, not its terminal recipient.
  These are command services, not a second user-facing Console implementation.
- `ConsoleTerminalRegistry` owns up to 32 process-local terminal sessions and weak
  references to their optional windows. It exposes immutable task, display, PTY,
  dimensions, foreground-process, title, directory, viewport, and transcript
  state without retaining Activities. Reattachment replaces the presentation,
  not the shell; detached sessions continue accepting input and collecting output.
  Detach releases the old View's input and resize ownership before Activity
  destruction. Its expired IME connection cannot write into a retained session.
  `DesktopAutomationTerminalWindows` maps the gated MCP
  `terminal.*` tools onto that registry and the normal built-in-window launch
  path. Terminal input therefore reaches the real PTY directly instead of
  synthesizing pointer coordinates. Closing the MCP server does not terminate
  headless or user-owned terminal sessions.
### Capture And App Functions

- `CaptureRequest` describes an explicit display or task and an optional immutable pixel
  rectangle. `CaptureService` resolves geometry, validates selection,
  and asks the existing shell capture backend for one cropped PNG pipe or one
  bounded pixel batch. It is independent of MCP, accessibility and Desktop;
  selection and publication belong to its callers. It rejects observed display
  geometry changes rather than returning stale coordinate metadata. Metrics
  come from the requested display's Context, not application resources that
  can describe the last Activity on a different display.
  `DesktopAutomationCapture` is the JSON/image adapter and owns only MCP's
  omitted-display default (sole Desktop, otherwise display 0 when none exists).
  Multiple workspaces require an explicit display; an explicit selection never
  evaluates that default. Image bytes
  are never staged in a filesystem cache. Task selection never resolves the
  default display or changes focus. Its frame metadata and reliable PNG pipe
  arrive in one typed `TaskCapture` reply; only that frame supplies dimensions
  and rotation. Display/element rectangles remain visible-composition captures.
- `MagicDeskAppFunctionService` is the Android 16 system-agent adapter. Android
  protects it with `BIND_APP_FUNCTION_SERVICE`; resource gating disables the
  component below Android 16. It exposes only a small non-shell subset and
  executes it through `DesktopAutomationController`.
- App Functions never accept arbitrary shell commands. MCP exposes shell and
  filesystem operations only behind their explicit permissions.
  Transport authentication, command permissions, platform
  permissions, and action validation remain independent checks.

## Diagnostics

`CompatibilityDiagnostics` records stable error codes with bounded local
history. A bounded set of the 256 most recent distinct signatures suppresses
repetitions, including interleaved events. An evicted signature can be recorded
again. Exact
duplicates left by earlier process runs are also collapsed when the report is
built. The issue report includes firmware identity, displays, external input,
desktop settings, service UID/domain/capability probes, and MagicDesk-only
logcat. It excludes user files, accounts, notification content, clipboard, and
the installed-app catalog.

Input diagnostics are event-driven lifecycle counters: startup attempts,
ready sessions, refresh failures and the last routing display. The virtual mouse
keeps aggregate protocol and write-error counters; the shortcut service reports
connection state, routed device IDs and command counts. Neither records key
codes or typed text. At the start of explicit compatibility-report
generation, MagicDesk requests one native statistics frame and one bounded
`FrameworkInputSnapshotSource` snapshot. The resulting report compares owned
MagicDesk ports with current InputManager associations and records the observed
cursor observation with its source-supplied display identity (unknown when not
provided), without refreshing the viewport or attempting pointer recovery.
No diagnostic input polling runs during normal desktop use. The
report also states whether the optional desktop-session wake policy is enabled
and currently held.

Compatibility probes are non-destructive: they inspect permissions and reject
invalid/null mutations after framework permission checks rather than changing
real input, display, or hardware state.

The manual desktop self-test combines those probes with reversible black-box
operations on a simulated, connected external, or phone display. APIs that can
be checked without peripherals are reported as PASS/WARN/FAIL. The selected
wired or Miracast transport is recorded as exercised, while physical keyboard,
mouse, and Touch Panel input remain NOT TESTED because the automation injects
input. The last bounded result is included in the normal compatibility report;
no periodic self-test or diagnostic polling runs in the background.

Debug builds also expose this production path through `DebugSelfTestActivity`.
The smoke script starts that Activity and reads the normal bounded result file;
it does not replace the app process with instrumentation, so the runtime and an
enabled MCP server remain alive. It is intentionally not run by host-only CI.

Desktop wallpaper selection starts with the effective workspace's prepared
appearance resource, including an optional shader and poster. Without a theme
wallpaper, the controller reads the shared Desktop media file or decodes bundled
`drawable-nodpi/desktop_wallpaper.webp`. The [selection contract](appearance.md#wallpaper-selection)
is shared across displays. MagicDesk Files offers **Set as
desktop wallpaper** for local images and MP4/WebM video. The selected file is reopened
through its verified device/inode identity, validated by `WallpaperAsset`, and atomically copied to
`/storage/emulated/0/Desktop/.magicdesk/wallpaper`;
selecting **Use MagicDesk wallpaper** clears wallpaper/shader selection in the
current appearance scope and removes the shared file. Other scopes keep their
theme selections. Unavailable or undecodable shared media falls back to the last
valid custom cache or the bundled background and
records one compatibility event per distinct failure instead of changing
desktop session state.
Confirmed absence of the custom file clears its cache and selects the bundled
background. A solid-color emergency frame is used only if the bundled resource
cannot be decoded. Wallpaper source selection belongs to the shared desktop UI;
the shell boundary only reads and writes the optional Desktop file. The desktop
folder observer owns custom wallpaper change notifications.
The existing `wallpaper_rendered` event records the selected source (`bundled`,
`custom`, or `fallback`), media kind, poster/view dimensions, and density after the
frame commits. `wallpaper_playback` records playback state changes separately.
Bundled artwork provenance is documented in [Artwork](artwork.md).
Each background load owns a unique temporary cache file. The existing load
generation cancels superseded work before provider reads, between transfer
chunks, and before cache publication and rendering. Cancellation does not
trigger fallback or a compatibility failure, and an already decoded but
unused image is recycled. Temporary cache allocation failure still permits
cached or bundled wallpaper. Source observation adds no polling loop.

`CommandConsoleActivity` is a permission-protected, multi-instance window
over a retained `ConsoleTerminalSession`. The registry owns each independent
interactive PTY, terminal emulator, current-directory state, and selectable
scrollback; an Activity attaches only its view. Android-shell and Termux transports share this UI and
session layer. Input is a byte stream rather than discrete command jobs, so shell
editing, signals, ANSI output, alternate-screen applications, and terminal
mouse protocols retain their normal semantics. Closing the Activity detaches
its view; running `exit` or explicitly ending the session closes the shell.
Commands supplied by explicit Files and Desktop
actions are safely quoted and sent after the PTY becomes ready.

`TaskManagerActivity` consumes the active session's published task snapshot;
outside a session it requests a snapshot on open or explicit refresh. Its
process statistics retain their own UI refresh cadence, without repeatedly
querying tasks. It owns no task-stack parser or windowing policy. Focus, task
close, and explicit
force-stop therefore use the same validated operations as the taskbar. A log
action launches `AppLogViewerActivity`, whose lifecycle-bound owned stream runs
`logcat` with a numeric UID filter. The viewer keeps a bounded transcript and
closing it closes the remote process. Arbitrary command entry remains exclusive
to Console.

## Desktop Self-Tests

Contributors can run `scripts/smoke-simulated-display.sh` from a host with ADB.
The script starts the debug self-test Activity, so it uses the same display
driver, owned overlay lease, desktop session, window suite, and cleanup as the
built-in simulated self-test without replacing the running app process.

The built-in **Diagnostics > Run desktop self-test** runs the same bounded core
on a selected simulated, external, or phone display. A desktop session must be
closed when the test starts; an already connected secondary display is allowed.
Preparation and execution belong to `DesktopSelfTestLauncher`, not to an
Activity instance. Diagnostics observes immutable `DesktopSelfTestRunState`
progress through lifecycle-scoped invalidation callbacks and collects a full
report only outside an active run. The same progress is exposed through MCP.
For non-phone targets, `DesktopSelfTestGuardWindow` connects Diagnostics'
resume/stop events to the existing phone input guard. It reuses the report task
and hides it before phone-UI and Close-to-HOME assertions; there is no separate
guard Activity. Unexpected input remains recorded, while the Stop button
requests cancellation of the exact run. The harness restores the report only
after production cleanup and destination checks.
Its explicit isolated session policy suppresses saved-workspace restore
and persistence on every display driver. A scoped orientation lease locks the
phone at its current rotation and restores the exact previous auto/locked mode
through the common finalizer. The target owner prepares the session once, while
the common core derives bounds from the actual viewport, adopts any larger minimum window size
enforced by WMShell, and uses production session and task controllers to verify
a freeform Activity, task-local native
caption source and geometry, display-targeted application input, native caption
and resize input handles, true fullscreen, restore, minimize, and cleanup. It
then opens two independent editor fixtures, uses the native caption menu to
place them on the left and right halves when the selected diagnostics provider
defines that test scenario, and verifies keyboard focus transfer through both
the desktop task controller and mouse input. Native caption menu automation is
currently scoped to the Nubia windowing provider; other platforms retain the
common window/focus checks and report the native snap scenario as NOT_TESTED.
This is test coverage, not a native snap capability declaration. Production
window policy treats caption controls as opaque and observes actual task modes
and bounds instead of interpreting button contents or positions.
It also switches the
pair twice as true-fullscreen tasks and verifies that neither task becomes
freeform while the Alt+Tab panel is open or after focus changes. It restores and
closes one task, then verifies that the fullscreen survivor still receives real
injected text. A mixed-stack phase places a third freeform task between two
fullscreen peers, selects it through Alt+Tab, and verifies from the rendered
surface that the most recently selected fullscreen peer remains its background
while the older peer stays underneath. It then selects both fullscreen peers
through the production focus route and verifies their rendered colors. The
root-workspace freeform window remains above that background. Input
assertions wait for the current InputDispatcher focus state rather than a fixed
transition delay. InputDispatcher frames are normalized from the display's
natural coordinates into its current rotation, so the same PHONE scenario runs
in portrait and landscape. The test also
requests the native horizontal resize cursor and verifies WMShell's transition
trace when that firmware trace is available.

The application-fullscreen phase keeps one application-owned immersive task
and two MagicDesk-managed fullscreen peers alive together. It activates each
peer through the common single-task focus gateway, returns to the immersive
task, and verifies distinct stable planes, all three task modes, real input
focus, the application's immersive marker, and the rendered fullscreen
surface. This
catches a repeated hierarchy rebuild and an implementation that works only for
a pair of tasks. `WINDOW-015` and `WINDOW-020` identify these
application-fullscreen hierarchy checks.

That scenario has a checked cleanup boundary independent from its assertions.
In a full run, a failed required application-fullscreen step skips only its
dependent checks. The harness closes its temporary fixtures and verifies the
primary window's restored mode, bounds and input focus before resuming the
other window tests. The task-stack observer sees a separate cleanup stage;
restoration and removal cannot be attributed to the prior fullscreen contract.
Fail-fast, cancellation and failed cleanup still unwind to the global finalizer.

The phone-to-desktop transfer probe captures its reference after the source
task has left the desktop and the production Show Desktop command has committed.
It compares the transfer against that settled destination, not a previous
workspace with the source window's shadow. Pixel tolerances and first-visible
freeform mode/bounds assertions remain the same.

The simulated self-test owns its fixture display through a Binder-owned shell stream;
closing the stream or losing its owner closes stdin, runs a shell `trap`, and
restores the prior setting. Its test deliberately closes that lease once while
the desktop and a fullscreen fixture are still alive. It verifies that the
runtime and owned display stop and that a surviving fixture is
never left freeform on display 0. The external target selects the existing
wired or wireless display automatically and never treats the physical display
or its unrelated tasks as test-owned. An existing Miracast transport remains
connected. The phone target uses the normal local-
desktop navigation and cleanup path. Each target closes only the MagicDesk host
and test fixtures that it created. Cleanup closes the host before removing its
fixture tasks so SystemUI can reconcile live task IDs instead of retaining
references to tasks that the test already destroyed. The phone navigation
guard is released even when task reconciliation reports a failure; the pending
marker remains for a later recovery attempt.

## Task-Stack Test Guards

The shell task observer exposes an optional self-test guard. While a test is
active, every task callback captures a bounded `getAllTasks()` snapshot tagged
with the current test stage. A pure analyzer checks the desktop host, fixture
display and windowing mode, HOME visibility, one-way task transitions, and
windowed/fullscreen visibility continuity. It receives only the selected
display ID and desktop-host task ID; it does not branch on a display kind,
display number or vendor. The analyzer requires every
simultaneous fullscreen fixture to have a distinct feature ID, exactly one
anchor in that plane, and the same parent throughout focus switches. A fixture may
leave the selected display only in fullscreen mode during
the explicit transfer scenario. The guard requires a visible desktop task at
committed stack boundaries. HOME visibility metadata may change while a
freeform fixture stays visible; that flag alone is not a surface failure.
Separate wallpaper and taskbar assertions check the rendered desktop.
No guard snapshots are taken
during normal desktop operation, and the guard uses neither polling nor timing
guesses. Android can deliver remote `onTaskMovedToFront` before the matching
visibility update; only a gap beginning at that callback may remain pending,
and it must resolve by the coalesced `onTaskStackChanged` callback or the test
stage boundary. Other visibility gaps fail immediately.

Before the first desktop input step, `TASKBAR-002` verifies both the host's
logical taskbar state and one pixel from the rendered taskbar surface. The
capture runs only inside the manually requested self-test and detects a panel
that is logically shown but composed below another surface. Closing the
isolated desktop session emits a lifecycle cancellation event; the test stops
at its next checkpoint and proceeds directly to cleanup instead of recording
failures against a session that no longer exists.

`SelfTestTaskStackInvariantAnalyzerTest` exercises these structural rules
without an Android device. Simulated, phone, and wired self-tests exercise the
same assertions against real WindowManager and firmware paths. All targets
verify parent continuity, mode, input focus, browser-style immersive state, and
the absence of desktop visibility gaps without weakening assertions by display
kind.

A separate one-shot launch probe captures the first
`onTaskMovedToFront` configuration, so the test distinguishes a true initial
freeform launch from a fullscreen task that is corrected after it becomes
visible. The same probe verifies a direct fullscreen-phone to
freeform-external move.

Self-test fixture launches also carry an explicit visual role. Primary,
secondary, and transition fixtures use stable red, green, and blue surfaces,
respectively, so a person watching the test can identify which task flashed,
moved, or disappeared. Window geometry and input assertions do not depend on
the palette. Composed-screen checks identify each fixture by its RGB proportions:
a neutral system task shadow may reduce intensity to half of the source color,
with at most three channel levels of rounding/composition error. This recognition
is limited to the explicit fixture palette; a wrong fixture, neutral replacement,
near-black surface or additive highlight must not pass. Wallpaper continuity and
panel visibility retain their separate color comparisons. The test does not
disable shadows or change task topology to obtain the source RGB value.
