# MagicDesk Automation

MagicDesk exposes shared services and managed Desktop through one typed action
boundary with three adapters:

- a Model Context Protocol (MCP) server with loopback and optional network access;
- the built-in `magicdesk` CLI for MagicDesk-launched shells and scripts;
- Android App Functions for authorized system agents on Android 16 and newer.

All adapters use production services and controllers. Automation does not
implement a second desktop policy or a parallel task observer. The APK baseline
is Android 14; managed Desktop and its self-tests require Android 15. Server
availability, service prerequisites and client grants are independent checks.
See [Runtime API levels](runtime-api-levels.md) for the validation boundary.

## Local MCP Server

The MCP server is disabled by default. Enable **Local MCP automation server**
under **Settings > Automation**. It exists only while the MagicDesk runtime is
alive and listens on the literal loopback endpoint:

```text
http://127.0.0.1:8765/mcp
```

Every request requires the generated bearer token. **Copy MCP connection**
copies the endpoint, authorization header, and ADB forwarding command.
**Replace MCP access token** immediately invalidates existing clients.
Diagnostics includes server state and counters but never the token.

For a client on a connected computer:

```sh
adb forward tcp:8765 tcp:8765
```

Configure Streamable HTTP at `http://127.0.0.1:8765/mcp` with:

```text
Authorization: Bearer <token copied from MagicDesk Settings>
```

A direct stateless protocol check is:

```sh
curl -sS http://127.0.0.1:8765/mcp \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $MAGICDESK_TOKEN" \
  --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"manual-check","version":"1"}}}'
```

The server supports MCP `initialize`, `ping`, `tools/list`, `tools/call`,
`resources/list`, and `resources/read`. It does not require an MCP session ID.

### Direct Network Access

**Network MCP access** is opt-in. Select a currently available private IPv4
interface and a port from 1024 through 65535. The server binds that concrete
address, never a wildcard. Interface changes use network callbacks, not a
background polling loop. An unavailable interface leaves network access stopped
and visible in settings; the loopback listener remains independent.

The network token and permission set are separate from the local ones. Rotating
one token does not change the other. Requests without a browser Origin header
are accepted with the correct token; browser requests must use the exact bound
origin. Tokens never appear in diagnostics or the public state projection.

This transport is **unencrypted HTTP**. Use only a controlled test network or a
protected VPN; do not forward its port to the internet. A private address alone
does not provide encryption or make other users of the LAN trustworthy.

File transfer and update clients can use `scripts/mcp-client.py` without ADB.
Supply the token through `MAGICDESK_TOKEN` or `--token-file`, not command-line
arguments. Non-loopback HTTP requires `--allow-plaintext-network` explicitly.

### Starting After a Phone Reboot

When the MCP server is enabled, opening MagicDesk from its normal launcher icon
starts the server before the privileged-service compatibility audit. This intentionally
does not start desktop, input, task-observer, or vendor runtime components.
An automation client can therefore connect first; after the privileged service connects, the
same process exposes newly available shell services without replacing the MCP
connection. Desktop still requires an explicit session start and its own setup.

`get_state.shell` reports the active and configured startup backend, the
independent force-shell-UID policy, and whether an app restart is needed.
Its `uid` is the verified command service identity, not the Shizuku server's
identity; an unconnected service reports an unknown UID. Backend selection does
not alter MCP grants or the tool catalog.

For Codex on the phone, open MagicDesk once after a reboot. A client with live
MCP reloading can then use `/mcp reload`; otherwise restart or resume the client
once so it discovers the server. No automatic boot receiver is installed.

For updates initiated through `app.update`, a short-lived privileged worker waits
for Android's installation result independently of the old application process.
It writes a bounded durable receipt, invokes the installer-only process entry
after success, and exits. That entry is a `Theme.NoDisplay` Activity with no
window or Recents entry; it starts enabled automation and immediately finishes.
This does not rely on delivery of the package-replaced
broadcast and adds no continuously running updater. The normal privileged command
service retains its original lifetime. Neither the worker nor the new runtime
opens desktop or takes HOME, and disabled automation remains disabled.

For APK replacements performed by other installers, the manifest receiver for Android's protected
`MY_PACKAGE_REPLACED` event restores enabled automation. It starts the existing
foreground service without opening an Activity, starting desktop, or taking
HOME. Disabled automation stays disabled. This applies equally to local and
network listeners. Clients reconnect to their existing endpoint and token;
the server cannot reload a client's cached tool schemas for it.

Firmware autostart restrictions can suppress that broadcast. If it is blocked,
open MagicDesk normally to restore the same endpoint. Installation may have
succeeded even when reconnect expires. An update permission explicitly
authorizes the one-operation worker, not persistent monitoring, boot autostart,
or changes to the device's battery and autostart settings.

## Permissions

`tools/list` always returns the full catalog, including commands that are not
currently permitted. Every description names its required permission. The
listener checks its current permission set on every invocation, before calling
the shared backend. Disallowed calls return `TOOL_DISABLED` with the missing
permission and `local` or `network` scope. Changing permissions does not change
the catalog and does not require restarting the AI session.

Both listeners default to authenticated observation only: device/runtime state,
task and application lists, diagnostic reports, and bounded event traces.
These observations can contain application names and are not anonymous data.
Each listener independently grants:

- `control`: desktop, application, window and semantic UI actions;
- `input_tests`: synthetic input, self-tests and package force-stop;
- `content`: capture, clipboard, notification contents, Activity results and
  script dialogs/notifications (including their user responses);
- `files_read`: filesystem listing and downloads;
- `files_write`: uploads, creation and replacement;
- `shell`: shell/terminal/tmux and background Intent/desktop-entry commands;
- `update`: replacement of the MagicDesk APK.

These are command capabilities, not isolated sandboxes: shell, input, UI control,
and code replacement can have broad effects or reach other application features.
Grant them only to trusted clients. Revocation applies to subsequent calls;
already accepted operations may finish. Disabling MCP resets permissions and
disables its network access. Command sessions belong to the shared runtime,
not a listener; explicit session close or runtime exit releases them.
Tokens remain private and stable.

## Built-In CLI

New MagicDesk Console and Termux Console shells provide `magicdesk` in `PATH`.
It also works in child shell scripts and MagicDesk-launched background commands.
The ordinary Console uses Android's shell; Termux is optional. The CLI needs
neither Python nor an enabled MCP server, network connection, or MCP token setup.

```sh
magicdesk --help
magicdesk list_tasks --help
magicdesk list_tasks --displayId 0 --limit 20
magicdesk terminal.list
magicdesk terminal.open --backend shell --placement display --displayId 0
magicdesk wait_for_state --condition desktop_inactive --timeoutMillis 1000
magicdesk list_tasks --args '{"displayId":0,"limit":20}'
magicdesk list_tasks --args @request.json
magicdesk close_desktop --dry-run
```

Command names and argument names match the shared catalog exactly. Boolean
options accept `--includeReport`, `--includeReport=true` or
`--includeReport false`. Objects and arrays use JSON. `--args -` reads one JSON
object from stdin; it cannot be mixed with named arguments. `COMMAND --schema`
prints the command descriptor. `--dry-run` validates and prints a request without
executing it. Help and schema inspection do not need a running app connection.

Stdout contains the shared `success`, `message`, `data`, `error` JSON result;
captures additionally carry an `image` object with `mimeType` and base64 `data`.
CLI diagnostics go to stderr. Exit status is 0 for a successful operation, 1
for an operation failure, 2 for invalid arguments and 3 for a transport failure.
A successful observation can still have `matched=false`. Acceptance is not
completion; use the ordinary observation commands and exact operation/run IDs.
There are no automatic command retries after a lost response.

`--field data.requestId` selects a dotted object field from a successful result.
Strings are printed unquoted; numbers, booleans, null, objects and arrays keep
their JSON representation. This works in ordinary `sh` without an external
JSON parser. Missing fields and operation failures return exit status 1 with
the full response on stderr. Invalid field syntax is rejected before execution;
field selection never retries the command. It cannot be combined with `--dry-run`.

The entry script invokes the Java CLI from the same APK using Android
`app_process`. It inherits a private local command channel from the shell launch,
not privileges from the APK. Existing shells must be reopened after upgrading
or restarting the runtime. The entry script contains no secret, and running it
from an unrelated app does not grant access. Shell descendants are trusted as
part of the user's command environment; do not pass it to untrusted code.

## Script Dialogs And Notifications

Four shared commands let scripts communicate with a user without Desktop or
Termux. MCP requires `content` for all four, including creation and cancellation.
The CLI uses its existing inherited command channel. Background dialog placement
uses the shared privileged launcher; notification publication and result handling
use ordinary app APIs. Android notification permission and channel settings still
apply and are never silently changed.

- `dialog.show`: `text`, `confirm` or `choice`; choice supports single or multiple
  selection. `displayId` defaults to 0. An active Desktop uses its normal managed
  placement; otherwise this is an ordinary Android dialog Activity. No HOME or
  Desktop is acquired. Dialogs are not application-launcher entries or restored
  windows. Back, outside dismissal and task close cancel the request.
- `notification.post`: title, message and up to three `{id,label,reply}` actions.
  `reply:true` collects an inline text response. Tapping the body returns the
  reserved `actionId:"open"`; a button returns its own ID, and a reply includes
  `text`. Swiping away cancels. The first answer completes the request and removes
  the notification. Buttons publish data, not stored shell commands or privileged
  callbacks; the waiting script decides what to do next under its own authority.
  Script and terminal message channels are also included in MagicDesk's
  notification center and `list_notifications`; runtime status notifications
  remain excluded. Inline replies use Android's notification UI.
- `interaction.result`: read by exact `requestId`, optionally event-wait for
  `waitMillis` (0-30000). Reads do not consume the response. A wait ending with
  `state:"pending"` is not an answer and does not close the UI.
- `interaction.close`: cancel a pending request. Repeating it cannot overwrite a
  completed response. Closing an unknown request still returns `not_found`.

Creation returns acceptance with a generated `requestId`, not proof of visible
UI or a user answer. Do not replay creation after a lost response. States are
`pending`, `completed`, `cancelled`, `expired`, `failed` and `not_found`.
`presented` means the dialog resumed or Android accepted the notification;
another window, DND or lock-screen policy may still obscure it. An asynchronous
presentation failure is recorded as `failed` with `failure` detail.

Requests live for `lifetimeMillis` (1000-86400000, default one hour). One-shot
expiration closes their UI independently of result waits. The registry retains
up to 64 requests and admits at most 16 pending interactions; pending entries
are never evicted to admit more work. Completed entries may be evicted by newer
requests. Runtime exit cancels pending work. Process restart loses responses;
old notifications are removed when the interaction service next initializes.
`not_found` never proves that a user accepted or cancelled a request. Repeated
reads are safe only while that exact result remains retained. Text is bounded
to 8192 characters; replies are not copied into the diagnostics event journal.

Example for an ordinary MagicDesk shell, with no `jq` or Python:

```sh
request=$(magicdesk dialog.show --type text --title 'Archive name' \
    --initialText backup --field data.requestId) || exit 1
trap 'magicdesk interaction.close --requestId "$request" >/dev/null' EXIT
while :; do
    state=$(magicdesk interaction.result --requestId "$request" \
        --waitMillis 30000 --field data.state) || exit 1
    [ "$state" = pending ] || break
done
[ "$state" = completed ] || exit 1
name=$(magicdesk interaction.result --requestId "$request" --field data.result.text) || exit 1
printf 'Chosen name: %s\n' "$name"
```

The loop repeats a bounded event wait, not state polling. Validate user input
for its intended use and quote it as data; never evaluate it as shell code.

```sh
magicdesk notification.post --title 'Build finished' --message 'APK is ready' \
    --actions '[{"id":"files","label":"Open folder"},{"id":"answer","label":"Reply","reply":true}]'
```

Use the returned request ID with the same result wait. Only after a
`completed` result with `actionId:"files"` should the script call `open_file`
or open Files. Notification clicks themselves do not launch an application.

## Result Contract

Every `tools/call` result has a per-tool output schema and this common envelope:

```json
{
  "success": true,
  "message": "condition matched",
  "data": {},
  "error": null
}
```

Failures retain the same shape. `error.code` is stable and machine-readable,
`error.retryable` indicates whether a later state change can help, and
`error.observation` contains the last relevant state. Invalid filters and
cursors are tool failures rather than transport-level JSON-RPC exceptions.

`OUTCOME_UNKNOWN` means a dispatched action has no confirmed completion, for
example because its callback wait expired or was interrupted. This is not
cancellation or proof of failure. Its observation contains
`completionConfirmed=false`, `operationMayContinue=true`, and `safeToRetry`.
Do not automatically replay creation, launch, Close Desktop, task toggles or UI
actions: inspect their state first. Repeating Close Desktop can target a newly
started workspace. Terminal-launch uncertainty retains `terminalId` for
`terminal.status`/`terminal.list`; application observations retain known task
or display identity. A transport disconnect is likewise not cancellation.

`remove_display` is safe to repeat with the **same** `displayId` and `uniqueId`.
Concurrent requests join the original cleanup; an absent display succeeds
without doing anything. A live display with a different identity is rejected,
as are live displays not owned by MagicDesk and built-in displays. Successful
release may precede Android's display-removal publication; use
`wait_for_state(condition="display_absent", displayId=...)` to observe it.
The 20-second callback deadline does not cancel removal, and its
`OUTCOME_UNKNOWN` explicitly permits an identical retry. Do not substitute a
new display's identity when retrying an old operation.

## State and Observation

The MCP catalog exposes unqualified tool names such as `get_state`; clients
add the configured server name, so documentation uses `magicdesk.get_state`.

`get_state.services` reports independent prerequisites, including `terminal`
(shell or authorized Termux), `x11` (authorized Termux) and `desktop`
(API 35+, shell/root access, configured windowing and no pending Android restart).
Desktop readiness uses the shared read-only setup observation, not process-local
startup authorization. Its missing reasons distinguish `desktop_setup_checking`,
`desktop_setup_unknown`, `desktop_setup` and `device_restart`. These are not
client grants or successful windowing probes; the normal start path still checks setup.
Local interactive launch without shell does not imply that background
MCP placement or global task observation is available.

Normal read tools include:

- `magicdesk.get_state`: workspaces, shared HOME lease, shell, platform, runtime, MagicDesk-owned UI,
  actual focused input windows, and system error dialogs;
- `magicdesk.list_displays`: display modes, dimensions, density, and work area;
- `magicdesk.list_tasks`: task focus, visibility, bounds, display, native and
  normalized window modes, rendered-window state, process health, and blocking
  system dialogs;
- `magicdesk.list_apps`: launchable Android activities;
- `magicdesk.get_app_presentation`: saved System or Custom interface scale and
  the density resolved for the active desktop display;
- `magicdesk.list_ui_elements`: live MagicDesk controls with stable semantic
  ids, roles, labels, state, supported actions, and display-coordinate bounds;
- `magicdesk.get_events`, `magicdesk.get_diagnostics`, and
  `magicdesk.get_self_test`;
- `magicdesk.capture_screenshot` and `magicdesk.sample_pixels`;
- `magicdesk.get_recording_status`.

Diagnostics separate `Compatibility defaults`, `Compatibility overrides`,
`Compatibility next session`, and `Compatibility active session`. Missing
overrides follow platform recommendations; `inactive` means no leased session.
Input diagnostics report direct physical-device routing, the virtual phone
pointer, and the key-only shortcut filter separately. The routing snapshot
includes owned ports, active associations, and missing or unexpected routes.
Changing a compatibility preference affects the next session, not current
ownership or pending cleanup. Native pointer statistics and routing snapshots
are collected on demand, without another periodic query.

Display-power diagnostics distinguish command declarations
(`display.power_off`, `display.power_restore`) from the active phone-screen
guard and its protected UIDs. On Nubia, `vendor.cpu_freezer` checks the actual
protection interface. These probes change neither display power nor UID state.

The `Windowing policy` report line separates shared focus verification,
optional stale-focus repair, and secondary display freeform-default preparation.
Disabling repair does not remove the workspace command's input postcondition.

Task and application lists accept filters plus `limit` and `cursor`. Returned
pages contain `count`, `total`, and a nullable `nextCursor`.

Each `get_state.workspaces[]` record has a local `ui` snapshot of the taskbar,
Start, popup, wallpaper and desktop plane. `get_state.runtime` reports the
shared touchpad and control-panel visibility. Bounds are included for surfaces
owned by each desktop host. Screenshot capture returns PNG bytes as MCP image content and
does not create a file. Pixel sampling reads up to 64 coordinates in one shell
capture operation and returns exact ARGB and component values.

`get_state.x11[]` exposes retained X session identities, names, allocated
`display`, lifecycle `state`, `error`, application ownership and the current
window catalog (`id`, `title`, `mapped`, `hostManaged`, and `fullscreen` with
`serial`, `requested`, `actual`). Requested fullscreen is not proof of a completed
Android transition. These are X11 identities, not Android
display/task IDs. Tokens, Xauthority cookies and startup commands are omitted.
Each session also reports its resolved X11 `dpi` and relative `scalePercent`.
`runtime.x11Sessions` is the live session count. The X11 built-in uses ordinary
tool placement and remains available without Desktop.

`get_state.windows` distinguishes Android's focused application record from
the actual focused input window on each display. This matters when a crash,
ANR, permission, or other system-owned window is above an application whose
task still looks active. Each task's `health` reports whether its application
window is rendered, whether it owns input focus, whether a replacement process
is alive, and whether a system dialog blocks it. Process failures come from the
existing shell task observer; input-window state is read through the shell
service and does not create another observer.

Semantic UI elements are registered by the controllers that own their real
Android `View` objects. For example, `taskbar.start`, `panel.start`,
`start.tab.apps`, and `open_tasks.task.<taskId>` identify controls without
screen coordinates. `magicdesk.invoke_ui_action` accepts an id returned by
`list_ui_elements` and one of that element's advertised actions. It calls the
same click or context-menu listener as user input; MCP does not contain a
parallel menu policy.

`list_ui_elements` also exposes visible fullscreen Start, including HOME and the
independent Apps screen. Its `start.*` IDs are local to that window;
`start.display` chooses its launch destination (initially **Current**).
The action's `displayId` addresses the window containing the control, not the
selected launch destination. Phone HOME
actions are `phone.controls`, `phone.touchpad` and `phone.close_desktop`.
The registry is removed when that Start stops; this does not create another
desktop session or a background UI observer.
A visible registered Start remains addressable when keyboard focus is on another
display. Within one display, a focused registered window takes priority; multiple
unfocused registered windows are not selected arbitrarily.

## Display Preparation

`get_state.workspaces` contains one record per admitted workspace. Each record
has a residency `id`, `displayId`, `outputDisplayId`, `active`, `starting`,
`hostTaskId`, `controlsInput`, and its local `ui` snapshot. The IDs remain
available during preparation before host registration. The residency ID survives
host recreation, not Close. A `target` contains `workspaceDisplayId` and an
`output` object with `displayId`, `kind`, `profileKey`, and `activationSource`.
The workspace target retains a direct binding to its Android display. A portable
workspace uses a separate Viewer presentation edge to another output; that edge
appears in `presentations`, not as a mutation of the workspace target.
Output kinds are `built_in`, `wired`, `wireless`, and `simulated`; the `phone`
launch/self-test target still means the system default display.
`get_state.homeLease` reports shared phase, closing display and membership;
it is not a fresh Android role-holder query. `get_state.inputControl` independently reports
`requestedDisplayId`, `readyDisplayId`, `transitioning` and `error`, including
manual control without Desktop or on a different display.
Launch, task, UI and injected-input commands continue to address logical Android
displays. Viewer presentation bindings are separate from these task targets.

`list_displays` publishes source, uniqueId, dimensions, densityDpi,
defaultDisplay, builtIn, canHostDesktop, requiresPortableDesktop, owned and canRemove. Default-display
identity is separate from built-in topology; additional built-in panels remain
ineligible for Desktop until verified. These are live display identities, not
desktop-session records. A wireless connection may already be listed before
MagicDesk starts on it.

`name` is the user-facing label, preferring Android's product name for external
devices when available. `systemName` retains the unmodified Android display
name. Product labels do not affect display identity or saved profile keys.

`profileKey` identifies each display's own settings; `originProfileKey` identifies
the transitive creation origin and does not follow Viewer attachment. `profile`
contains explicit saved `densityDpi` and virtual creation `width`/`height`, with
null for unspecified values. The top-level dimensions/density remain live Android
state. A child profile can be changed without writing to the originating profile.

Display metadata also reports `secure` (Android's output capability) and
`protectedContent` (the protection policy of a MagicDesk-owned virtual source).
`list_displays.canCreateProtectedDisplay` reports the current privileged service's
secure-output permission: true/false, or null when unavailable/unknown.
`protectedDisplayPermissionError` contains any permission-query failure.

`canHostDesktop=false` rejects only direct managed Desktop startup, not ordinary
tool placement or Viewer output. In particular, untrusted public displays may
accept a privileged Viewer launch but cannot host organizer-created task areas.
`start_desktop(displayId=...)` uses the portable launcher when
`requiresPortableDesktop=true`; `portable=true` explicitly requests it on other
outputs and requires `displayId`. It keeps the existing output Viewer, otherwise selects
an unassigned owned virtual source of the same resolution with the fewest managed
applications (ties: matching origin, then lowest ID), or creates one. Existing
DPI/origin are preserved. A new source uses the output's explicit saved DPI, or
the same resolution-based density recommendation as direct external Desktop
(1920x1080 defaults to 160 DPI); explicit System uses the output's live density.
Portable completion returns the logical source as `id`
and the requested output as `outputDisplayId`, plus `portable=true`. Desktop
startup and visible Viewer attachment complete before success; a wait timeout
does not cancel either. Inspect `workspaces` and `presentations` after uncertainty.
Input and subsequent Desktop commands address the source, not the output. The
explicit creation/start/`open_builtin(builtin="display_viewer")` workflow remains available.
Viewer launch and attachment errors remain authoritative.

- `create_display(width, height, densityDpi, type, protectedContent, sourceDisplayId, sourceUniqueId)` creates a `virtual`
  (headless, default) or `overlay` (phone preview) display without starting HOME.
  All parameters are optional. Without a reference the defaults are 1920x1080
  at 160 DPI. `sourceDisplayId` supplies a creation reference, including display
  0; optional `sourceUniqueId` rejects a stale reference and requires its ID.
  Omitted dimensions inherit the reference's current logical resolution; omitted
  density inherits its explicit saved DPI, otherwise its live density. Explicit
  dimensions/DPI override that snapshot without losing origin. This neither
  attaches a Viewer nor moves applications. The new display gets a separate
  profile with the reference's flattened origin, so deleting an intermediate
  source does not break future inheritance. A profile-save failure after
  allocation returns `created=true` and the retained display identity; inspect
  it rather than creating a duplicate.
  Several headless displays can coexist; only one Android overlay can be
  created without rewriting an existing overlay set.
  Owned virtual displays do not request system navigation decorations. Detaching
  or reattaching a viewer preserves the source's original creation flags.
  `protectedContent` defaults to false and is only supported for `virtual`.
  It requires `CAPTURE_SECURE_VIDEO_OUTPUT` in the current privileged service
  and creates a secure source with a protected detached sink. Creation fails
  explicitly if unavailable; it never elevates the service or falls back to
  an ordinary source. The UI exposes the same option as **Protected content**.
- `start_desktop(displayId, uniqueId)` starts on exactly the selected display.
  The optional uniqueId prevents stale selection after hotplug. Do not combine
  this form with the target convenience selector. Wait for `desktop_active`;
  command acceptance does not mean the host has appeared. The active/inactive
  conditions also wait for the shared startup/cleanup operation to finish,
  so a matched result permits the next session command.
  Repeating an explicit display request for an active workspace also returns
  its current or last Viewer output, selecting that source again when another
  source replaced it. This does not create a Viewer or change its fullscreen
  setting. `desktop_active` alone does not establish Viewer readiness; inspect
  `list_displays.presentations` and the output UI after this navigation request.
- `close_desktop(displayId)` closes only that workspace and leaves its display
  connected and reusable. HOME is returned when the last workspace closes.
  Omitting a Desktop destination is permitted only when exactly one workspace
  exists. Multiple workspaces require an explicit display ID; the server does
  not silently choose the input display.
- `remove_display(displayId, uniqueId)` only removes a MagicDesk-owned display.
  It first closes any session on that display, releases its selected input and
  waits for window transitions.
  Then wait for `display_absent`; a removal request is not a display-loss event.
- `open_builtin(builtin="display_viewer", displayId, placement, viewer)` uses
  the ordinary built-in launch command. The optional `viewer` object contains
  `sourceDisplayId`, `mode` and `immersive`; it is rejected for other built-ins.
  Without options, Viewer opens its source selector. An explicit source waits
  for Surface attachment, not the first rendered frame. Built-in sources use
  their individual IDs, including 0. Neither mode starts Desktop or claims input.
  `mode="mirror"` is the default and creates another Viewer window using normal
  managed or independent placement. It mirrors even owned virtual sources,
  without replacing another Viewer. `mode="output"` requires an explicit source
  and independently resolved placement (`display` or `phone` guarantees it). It reuses the output's
  fullscreen Viewer, connecting owned virtual sources directly and mirroring
  other sources through WindowManager. Repeating an output request reuses its
  binding and applies the requested immersive state. `immersive` hides Viewer
  controls and requests immersive system bars; it does not change task ownership.
  It requires a source and defaults to true in output mode, false in mirror mode.
  Output `uniqueId` has the same stale-selection check as other built-in launches.
  Protected sources require a secure output and use a secure SurfaceView.
  An incompatible exchange is rejected before detaching either old binding.
- `select_display_viewer(viewerId, sourceDisplayId)` changes only presentation.
  Output attachments exchange sources with another output attachment if needed;
  ordinary mirror windows never exchange bindings or change physical input. Omitting
  `sourceDisplayId` selects the previous source. Completion commits both bindings,
  waits for visible participants' attachments and any already-acquired input
  handoff. A hidden viewer attaches when shown; it does not block the visible
  peer and remains `ready=false`. Task IDs, display IDs,
  window bounds and density do not change.
- `close_task(taskId)` closes the Viewer window through normal task controls.
  Its Activity lifecycle releases the presentation, retaining both displays,
  applications and any Desktop. Direct sources return to their own sink;
  a mirror releases only its copied scene. Output-mode cleanup cancels pending
  Viewer input acquisition and releases its selected source without superseding
  a later explicit input selection. Mirror windows do not change physical input.
  Use `list_displays.presentations.taskId` or `list_tasks` to identify the window.
  Closure acceptance is not a synchronous Surface-release guarantee: observe
  `task_absent` and disappearance of its presentation before reusing its source.
  An already absent task retains `close_task`'s task-not-found result; callers
  can treat observed absence as their completed close condition.
- `control_display(displayId)` explicitly routes phone-attached physical mice
  and keyboards and enables the phone touchpad for an external display. Use
  `-1` to release input and restore prior routing. Completion confirms routing,
  not virtual pointer readiness. Observe `input_ready` with
  the same display ID (including `-1`); `pointer_ready` separately verifies the
  phone mouse transport. No HOME or Desktop is acquired. Desktop shortcuts are
  enabled only when the selected display hosts its prepared workspace.
- `move_task(taskId, displayId, placement, mode, uniqueId)` reuses an existing
  current-profile app task. `placement=auto` follows Desktop availability;
  `desktop` requires a workspace and `display` selects independent fullscreen.
  `mode=auto|windowed|fullscreen` controls managed placement; ordinary placement
  is fullscreen. Same-display requests can change ownership or mode, not just
  focus. Optional `uniqueId` checks the destination identity. The task and its
  source are revalidated; launch acceptance must be followed by task/UI
  observation. This action never claims input. Built-ins also retain `open_builtin`
  and terminal-session entry points using the same destination policy.

`list_displays.presentations` reports each viewer's binding UUID, Android `taskId`
(-1 before Activity registration), exact source/output
display identities, `mode` (`output` or `mirror`, matching launch options),
`transport` (`direct` or `mirror`), attachment `ready`, `immersive`, error and
`protectedContent`. Only output-mode Viewers
participate in output attachment navigation and Show Desktop return destinations.
Protected Viewer surfaces can be black
in ordinary screenshots/recordings; this does not make protected video available
through scrcpy. This is protected presentation support, not a guarantee that a
particular DRM application or external output will accept playback. Viewer
operations publish `display/presentation_changed` events. `OUTCOME_UNKNOWN`
means only the callback observation expired; it never cancels an operation.
Re-list presentations after an uncertain switch, especially before repeating
a previous-source action that would otherwise switch back again. Capturing a
viewer output captures its scaling and letterboxing; capturing the source
continues to use original logical coordinates.
Loss of the privileged viewer lease clears readiness and reports an error.
Selecting the same source retries the binding; stale errors from a previous
connection cannot invalidate a new one.

The control panel's **Show another display...** selects a source for the chosen
output; each source shows its name, ID and Desktop status. It and **Start portable
desktop here** both request fullscreen output without Viewer controls, reusing
the output's existing Viewer when present. **Display Viewer** is
an ordinary built-in application, opened through Start or
`open_builtin(builtin="display_viewer")` with the standard placement options.
By default it starts with a source selector and mirrors, without taking over an
output binding. Several windows may view the same source, subject to cycle and
protection checks. Automation requests `viewer.mode="output"` to show a retained
source like **Show another display...**. This does not reacquire released input.
**Stop showing**, available among the selected output's direct actions, ends only that
presentation, not its source display or Desktop, and does not disconnect the
output's HDMI/wireless transport. It is optional before unplugging or switching
sources. MCP and the built-in CLI use the same launch, source-selection and
task-close commands. For example, show source 12 on output 3:

```sh
magicdesk open_builtin --builtin display_viewer --placement display --displayId 3 \
  --viewer '{"sourceDisplayId":12,"mode":"output"}'
magicdesk list_displays
magicdesk close_task --taskId 123
```

Replace 123 with the returned Viewer's `taskId`. A launch observation timeout does
not cancel it. Re-list presentations before retrying, particularly in mirror
mode, where another launch intentionally creates another window.

Use the display ID from the panel or `list_displays` with the
[scrcpy example](../README.md#magicdesk-on-a-computer-with-scrcpy).
This views an existing display, not `--new-display`: disconnecting the viewer
does not remove MagicDesk's display. MagicDesk does not install a PC client,
start an ADB network listener, or implement another video/control protocol.

## Desktop Commands

Normal commands include:

- start or close a desktop session;
- launch, focus, close, or resize a task;
- set or reset an application's interface scale and apply the resolved density
  to its live desktop tasks;
- change a task's managed window mode through the production transition
  gateway, including stable per-task fullscreen-plane ownership where the
  selected desktop policy provides it;
- directly change a task's raw Android windowing mode for diagnostics without
  creating or preserving MagicDesk fullscreen-plane ownership;
- arrange a task left, right, maximized, or restored through the same window
  transition path used by MagicDesk shortcuts;
- show Start or the desktop and open Files, Console, Task Manager, Settings,
  Application Profiles, or Diagnostics;
- inspect and invoke live desktop controls semantically;
- discover Android Activity, receiver, and service handlers without executing
  them;
- launch typed or raw Android Activity intents, open URIs and files, share
  content, and collect asynchronous Activity results;
- list and invoke dynamic, pinned, cached, or manifest application shortcuts;
- inspect and invoke notification `PendingIntent` actions;
- discover and execute Android App Functions where the framework supports it;
- launch a supported `.desktop` file through the shared launch coordinator;
- start, stop, and inspect screen recording.

Use `tools/list` as the authoritative command and argument catalog.

`magicdesk.set_app_presentation` accepts an `appIdentity` from `list_apps` and a scale from
50 through 200 percent. The percentage is display-independent; MagicDesk
resolves it against the active target's density on every launch or move.
`magicdesk.reset_app_presentation` restores System mode (`densityDpi=0`,
inherit) for saved and running tasks. Responses include the saved mode,
resolved density, and active display when one exists.

`magicdesk.arrange_task` with `maximize` or `restore` is the normal managed
fullscreen/windowed command. It shares the production transition path with
taskbar and Alt+Tab. `magicdesk.set_window_mode` is intentionally raw and
exists for compatibility investigation: it changes Android's task mode
directly and can therefore reproduce firmware behavior that managed
fullscreen planes are designed to isolate.

The same `arrange_task` command accepts `left`, `right`, `top_left`, `top_right`,
`bottom_left`, and `bottom_right` for half- and quarter-window arrangements.
These use the Desktop work area, excluding the taskbar and system insets;
Android still enforces application minimum sizes. `restore` returns to the
pre-snap window geometry. MCP and the generated CLI share these arrangements.

Commands with input, content, or shell permissions include:

- `magicdesk.force_stop_app`
- `magicdesk.send_broadcast`
- `magicdesk.start_service`
- `magicdesk.clipboard.read_text`
- `magicdesk.clipboard.write_text`
- `magicdesk.clipboard.open`
- `magicdesk.clipboard.share`
- `magicdesk.clipboard.clear`
- `magicdesk.run_self_test`
- `magicdesk.send_key`
- `magicdesk.move_pointer`
- `magicdesk.click_pointer`

`move_pointer` injects a mouse hover at the requested display coordinates. It
does not reposition the hardware cursor; success means event injection was
accepted, not that the system cursor moved. The next `click_pointer` consumes
those coordinates once. With no pending coordinates, a click uses the session's
virtual mouse. `get_pointer_state` exposes `positionAvailable` and `x`/`y` only
when the observation identifies the requested display. Its separate
`observation` object retains raw coordinates with `displayId=null` if the source
cannot identify the cursor's display. Relay readiness does not prove that an
unscoped observation belongs to the desktop.

Clipboard automation uses Android's system clipboard through the same gateway
as Console and built-in UI copy actions. Reading is explicit, returns bounded
text plus MIME metadata, and may require a focused MagicDesk window under
Android clipboard privacy rules. Writing supports Android's sensitive-content
marker. These commands require the content permission; clipboard contents are
never exposed as an MCP resource, included in diagnostics, or declared as App
Functions.
The text limit is 262,144 UTF-16 code units. Read results retain the original
`textLength` and report `truncated`; the returned prefix never splits a valid
surrogate pair and may therefore be one code unit shorter than the limit.
Oversized writes are rejected, not truncated.
`clipboard.open` accepts one clipboard URI or an HTTP(S) link, while
`clipboard.share` sends text and bounded URI items through Android's chooser.
Both preserve `ClipData` URI grants and use the shared Android launch gateway.
They support ordinary displays without Desktop and managed Desktop placement.

File commands use the separate read/write grants:

- `magicdesk.files.list`, `magicdesk.files.stat` and downloads require file read.
- `magicdesk.files.create`, `magicdesk.files.rename` and uploads require file write.

Shell-granted commands are:

- `magicdesk.console.open`, `magicdesk.console.execute`,
  `magicdesk.console.status`, and `magicdesk.console.close`;
- `magicdesk.terminal.open`, `magicdesk.terminal.list`,
  `magicdesk.terminal.status`, `magicdesk.terminal.read`,
  `magicdesk.terminal.write`, `magicdesk.terminal.send_key`,
  `magicdesk.terminal.attach`, `magicdesk.terminal.detach`, and
  `magicdesk.terminal.close`;
- `magicdesk.tmux.list` and `magicdesk.tmux.open`.

`console.*` addresses headless command sessions. Each session is persistent,
has its own current directory and environment, and is owned by the shared
command runtime. At most eight such sessions may exist at once.
Console IDs are opaque and cannot select another session after a process restart.
`console.execute` normally returns bounded combined text output and exit status.
Its optional `stdout` object selects exactly one `terminalId` or `tmuxTarget`.
Omit `mimeType` to send raw terminal bytes; `image/png` encodes PNG as Kitty.
Redirected stdout is not copied into the response: `stderr`, `stderrTruncated`
and `stdoutDelivery` report diagnostics and PTY delivery separately from
`exitCode`. `console.close` cancels source jobs, never the destination terminal
or tmux server. See [streamed command output](terminal-integration.md#command-stdout)
for lifecycle, graphics and partial-delivery semantics.

`terminal.*` addresses retained Console sessions by opaque
`terminalId`. It can inspect task/display identity, shell PID, dimensions,
working directory, backend, OSC title, derived task label, and optional
foreground PID/process group/executable; read the textual viewport or bounded
scrollback; and write text or semantic key events directly to the PTY. These
operations do not use screenshots or synthetic pointer coordinates.
`terminal.list` is a fast registry snapshot; `terminal.status` refreshes the
reported working directory and foreground process from the live PTY.
Its `semantics` object also reports OSC command history, progress, the last
notification and bounded live-screen link spans. `terminal.read` accepts
`scope=command` with a `commandId` from that history; expired/missing output
returns `available=false` rather than a fabricated empty result. See
[terminal integration](terminal-integration.md) for coordinates and limits.

`terminal.open` accepts an optional `backend` of `shell` or `termux`. The
default is `shell`. A Termux terminal requires the installed Termux app, its
external-command setting, and the `RUN_COMMAND` permission; after launch all
other `terminal.*` operations are backend-independent.

`terminal.emit` is slave-side output, unlike the keyboard input of
`terminal.write`. It works for retained shell and Termux PTYs. `tmux.panes`
discovers live pane targets, and `tmux.emit` writes output before tmux parses it,
including for detached sessions. Both emit commands take exactly one of `text`
or `dataBase64` (up to 65,536 decoded bytes), return `bytesWritten`, and must not
be automatically retried after partial or unconfirmed completion. They use the
`shell` grant without Desktop. See [peer output](terminal-integration.md#peer-output)
for target lifetimes, graphics, terminal-state conflicts and CLI examples.

**Settings > Integrations** selects the Termux and Shizuku manager packages;
changes apply only at the next MagicDesk process startup. Compatible forks use
the same command APIs. `get_state.integrationPackages` reports active/configured
names and pending restart, `shell.managerPackage` names the selected manager,
and `termux` reports the resolved command service and any availability error.
MCP commands use the same selection as the UI, never an independent backend.

`terminal.open`, `terminal.attach`, `tmux.open` and `open_builtin` accept a
`placement`: `auto` (selected display, sole Desktop, or phone when none exists), `phone` (ordinary display 0),
`display` (ordinary fullscreen on an explicit `displayId`), or `desktop`
(the selected managed display). Multiple workspaces require an explicit
`displayId`. An optional `uniqueId` validates display identity.
An ordinary placement is independent even if Desktop runs on that display.
Reusing a Desktop-owned task explicitly releases its membership and fullscreen
plane before ordinary placement. These launches never start Desktop implicitly.

`terminal.detach` retains ordinary PTYs; repeating it on an already detached
ordinary session succeeds. For a managed tmux connection it releases the client
PTY instead, reporting `ptyRetained=false`; reconnect through `tmux.open`.
`terminal.attach` presents an existing window on the requested display or attaches
a window to the same PTY and emulator. `observed` requires a registered window
after successful presentation/launch. `terminal.close` ends the PTY and closes its window.
Detached sessions report `attached=false`, `taskId=-1` and `displayId=-1`.
They remain readable and writable; closing the MCP connection does not own their
lifetime. They survive window and display closure but not application process
death. At most 32 retained sessions may exist.

`get_state.services` describes automation, built-in UI, shell, Termux, virtual
display and Desktop prerequisites independently of client permissions. These
are not device probes. The APK minimum is Android 14; managed Desktop and its
self-tests require Android 15+. On Android 14, `readiness.selfTestReady` is false
with `selfTestUnavailableReason`, even when the phone is awake and unlocked.
This does not block independent automation, shell, Files or terminal operations.
`capture_screenshot` and `sample_pixels` also work without Desktop. An explicit
display id selects that display; omission selects Desktop when active, otherwise
display 0. Existing MCP authorization applies to both paths.

`capture_screenshot` accepts an optional `region` in display pixels at the
current rotation: `{"left":100,"top":200,"right":500,"bottom":600}`.
Left/top are inclusive, right/bottom exclusive. Omit `region` for the full
display. The rectangle must be nonempty and entirely inside the display;
invalid regions return `INVALID_ARGUMENT`, never silent clipping or scaling.
Output is an in-memory PNG at the region's exact pixel dimensions, with
`sourceBounds`, `displayWidth`, `displayHeight` and Android
`rotation` (0, 1, 2, 3). Image pixel `(x,y)` corresponds to display pixel
`(x + sourceBounds.left, y + sourceBounds.top)`.

For a window or UI element, read its bounds with `ui.inspect` and pass the
rectangle to this same command. It captures visible composition, not hidden
window contents; secure surfaces remain protected. UI inspection and capture
are separate observations: a window can move between them. An observed display
geometry change during capture returns retryable `CAPTURE_UNAVAILABLE` instead
of an image with stale coordinate metadata. Each image edge is limited to
8192 pixels and encoded PNG data to 32 MiB.

`tmux.list` performs one bounded query under the Termux UID. Its successful
result has `available=false` when tmux is not installed, so absence of the
optional package is not reported as a transport failure. `tmux.open` accepts
exactly one of an existing `sessionId` returned by `tmux.list` or a session
`name` to resolve or create before attaching a client. It opens an ordinary visible Termux
Console and returns its `terminalId`; the remaining `terminal.*` tools then
operate on that session. Ending it disconnects its tmux client while the tmux
server session continues. Closing a managed tmux window also disconnects that
client. Reopening the same tmux session reuses an existing MagicDesk connection;
the returned tmux id is resolved even for a newly created session.
These tools do not expose ordinary Termux application tabs.

## Android UI Automation

These commands work without Desktop on Android 14+. UI access and injected
input require a ready privileged service, not a root device or a new accessibility service.
Every UI observation and gesture specifies an Android `displayId`, including 0.
Prefer the existing semantic MagicDesk controls for Start, taskbar and menus.

| Tool | Contract |
| --- | --- |
| `ui.inspect` | Read accessibility windows/nodes, optionally scoped to a window or subtree and filtered by an exact selector. |
| `ui.read_text` | Read full text/description in bounded pages from a retained node's snapshot. |
| `ui.perform` | Click, long-click, focus, set/select text, scroll or reveal a node using an advertised action. |
| `ui.wait` | Wait for exact selector presence/absence, including expected text and state flags. |
| `ui.release` | Release the Android automation connection and cancel its pending UI waits. |
| `input.gesture` | Explicit-display touch tap, long press, swipe or drag through bounded point lists. |
| `input.key_chord` | Press ordered Android key names, release them in reverse order, including on failure. |
| `device.keep_awake` | Acquire or renew a bounded screen-awake lease; no privileged service or Desktop needed. |
| `device.release_awake` | Release the exact lease token. |

`ui.inspect`, `ui.wait` and `ui.read_text` require the `content` permission. They can read text
from other apps; password text and descriptions are redacted. The other commands
require `input_tests`. These grants are independent for local and network
clients. UI strings and entered text are not logged or added to diagnostics.
Content revoked during a wait is not returned to the client.

Example sequence after locating the intended editor (three separate tool calls,
shown as an array):

```json
[
  {"tool":"ui.inspect","arguments":{"displayId":0,"maxNodes":200}},
  {"tool":"ui.perform","arguments":{"elementId":"<returned handle>","action":"set_text","text":"First line\nSecond line"}},
  {"tool":"ui.wait","arguments":{"displayId":0,"selector":{"resourceId":"example.app:id/editor","text":"First line\nSecond line"},"timeoutMillis":5000}}
]
```

Selectors are exact conjunctions, not regexes or first-match heuristics. To wait
for focus/check/selection changes, include that boolean in the selector.
`ui.wait` returns `matched` and `timedOut`; a successful observation request does
not imply its condition matched. Each capture clears Android's accessibility
cache through the public API 34 `UiAutomation.clearCache`. `stable` means this
succeeded and no accessibility event was observed during capture; it is not a
guarantee that application rendering finished. `generationStart`/`generationEnd`
expose concurrent changes. An unstable observation cannot satisfy either wait
condition. `complete=false` means unstable or incomplete traversal, or redacted
values that could match the selector; it never proves absence. Canvas-only apps,
protected surfaces and missing accessibility events remain platform limitations.
Use screenshots or explicit gestures when semantic elements are unavailable.

Both inspect and wait accept `windowId` or `rootElementId` (mutually exclusive)
within their explicit `displayId`. A subtree handle is refreshed and identity
checked, never treated as a coordinate. `selector` in inspect returns matching
nodes only; wait uses the same traversal. A filter can therefore find a node
beyond the first 256 unrelated nodes. Search is bounded to 4096 visited/queued
nodes, depth 40 and a three-second traversal budget; `maxNodes` limits returned
nodes/matches to 1-256, not the number of candidates searched. `visitedNodes`
and `hierarchyComplete` describe traversal. Filtered results have `parentId`
only when their immediate parent was also retained.

Node text/description previews are limited to 512 UTF-16 units per field and
64 KiB in total. `textLength`, `textTruncated`, `descriptionLength` and
`descriptionTruncated` identify shortened fields; top-level `textTruncated`
reports any shortened preview. Preview truncation does not invalidate traversal
or exact matching: selectors compare full values, up to 32768 UTF-16 units.
`ui.read_text` takes `elementId`, `field` (`text` or `description`), `offset`
and `limit` (1-32768). Follow `nextOffset` until null. Page boundaries do not
split surrogate pairs; a limit of one may return a two-unit character. Password
values and lengths remain null. Pages belong to the same retained revision,
even if an action changes the live field. Inspect/wait again for current text.

Handles expire after 60 seconds and only four snapshots are retained. A changed/stale element
fails explicitly, never falling back to its old coordinates. `accepted=true`
means Android accepted the action, not that navigation or rendering finished.
Inspect/wait again to verify. Unicode and line breaks go directly through
Android's text action, not the clipboard or shell `input text` encoding.

The automation connection preserves existing accessibility services and is
released after 60 seconds without requests. An existing external automation
connection is not evicted. Explicit release, MCP shutdown or APK process death
also releases it. There is no persistent UI poller or Desktop dependency.

Gestures use screen pixels and at most 32 points. Movement/press duration is
0-5000 ms; drag can hold initially for 0-2000 ms. `input.key_chord` accepts 1-8
distinct key names, such as `["CTRL_LEFT", "A"]`. Neither changes the physical
mouse position. `click_pointer` also accepts `x` and `y` together for an atomic
primary/secondary mouse click without a preceding hover command.

An awake lease lasts 1000-1800000 ms (default five minutes). Acquire only after
the user wakes and unlocks the device. Renew with the returned `leaseId`, and
release it when finished. `get_state.automationAwake` reports the current token
and remaining lifetime. The system screen-timeout setting is never modified;
the lock expires automatically and is released when the MCP runtime stops.

The debug-only `DebugUiAutomationActivity` supplies a harmless editor, long-text
action, password, mutable button identity and 320-row list for verification through
these same APIs. It does not register elements in MagicDesk's UI registry and
is not included in release APKs.

## File Transfers and Updates

`files.upload_begin` takes a client-generated `transferId`, absolute destination
`path`, exact byte `size`, expected `sha256`, and explicit `overwrite` (default
false). `files.upload_chunk` writes base64 chunks of at most 128 KiB at the
acknowledged offset. Identical retries are accepted, gaps or different bytes
are rejected. `files.upload_status` resumes after reconnect or process restart.
`files.upload_commit` checks length and digest before publishing; retrying a
completed commit never rewrites the destination. `files.upload_abort` removes
only an incomplete upload, not a published file. The destination must be
shell-writable; arbitrary private application files do not become accessible.

`files.download_begin` snapshots an ordinary shell-readable path and its
SHA-256. `files.download_chunk` reads bounded ranges, detecting changed file
identity, size or modification time. The client must verify the final digest
and call `files.download_finish`; it can also finish an abandoned download.
At most 16 active transfers exist. Finished receipts are pruned when needed;
unfinished uploads require explicit abort. There is no periodic cleanup worker.
Transfers currently address filesystem paths, not arbitrary content-provider URIs.

`app.update(path, sha256, updateId)` replaces only MagicDesk with a same-signer
APK of an equal or greater version code. It requires a closed desktop and no
active self-test. It neither uninstalls the package nor clears application data.
The `update` permission is separate from upload permissions. Android's
`PackageInstaller` owns the committed installation and reports its result to the
independent privileged worker, which writes a durable receipt before starting the
new automation runtime. `app.update_status(updateId)` returns that exact operation's
state; a lost response during replacement is not evidence of installation
failure and must not cause a new update request.

The host client uploads/downloads binary data directly, without filling the AI
conversation with base64. Its `update` command closes desktop through production
cleanup, uploads the APK, submits once, reconnects, and checks installer status
and the resulting app version. Interrupted operations print their id for resume:

```sh
python scripts/mcp-client.py --token-file /private/mcp-token upload build.apk /data/local/tmp/build.apk
python scripts/mcp-client.py --token-file /private/mcp-token download /sdcard/Download/report.txt report.txt
python scripts/mcp-client.py --token-file /private/mcp-token update app/build/outputs/apk/debug/app-debug.apk
```

For a network listener, add `--endpoint http://PRIVATE_IP:PORT/mcp` and
`--allow-plaintext-network` only on the approved LAN/VPN. `--transfer-id` resumes
uploads/downloads; `--update-id` resumes observation of an update. Expiring the
client deadline leaves the operation's outcome pending, not cancelled.
The uploaded source APK remains at the returned path; Android owns its separate
installer staging session. An installer requesting user intervention is reported
as `user_action_required`, never silently bypassed or auto-approved.

Host client protocol fixtures run with:

```sh
python -m unittest discover -s scripts/tests -p test_mcp_client.py
```

## Android Integration

Task rows include the actual Android `userId` (`-1` when unavailable).
`list_apps` remains scoped to the current profile and includes `userId`,
`profileSerialNumber`, and the durable `appIdentity` key on each row. These
are explicit identities, not a claim that cross-profile launches are supported.
Application-specific tools (`launch_app`, `list_app_actions`,
`invoke_app_action`, presentation get/set/reset, and `force_stop_app`) require
`appIdentity`, not a bare package. The `app-details` action and Android
App Function `launchApp` use the same parameter. A component, when supplied,
must belong to that application. Unknown profiles fail before dispatch.
Package filters and generic Intent routing retain their separate semantics.
An Android Desktop Entry that explicitly references a different profile fails
before execution instead of falling back to the current profile.

`list_desktop_entries` discovers launchable `.desktop` applications with
`source=desktop|termux`, an optional `query` and a bounded `limit`. `desktop`
reads the Desktop folder; `termux` refreshes the installed application catalog
through the selected Termux endpoint's `RUN_COMMAND`. Both require the MCP
`shell` grant, not a running Desktop. Unavailable Termux access is an error,
not an empty catalog or a fallback to another identity.

Pass a returned `source` and `desktopPath` to `launch_desktop_entry`, with
optional `files`, `placement`, `displayId`, `mode`, `instance` and `bounds`.
Termux paths must match its current catalog; private files are never read through
Android shell. Start and automation share the recipe coordinator and destination
policy. Ordinary placement does not acquire HOME or start Desktop. An accepted
recipe is not proof of application readiness: observe `list_tasks` and, for X11,
the session/window catalog in `get_state`. A callback timeout is an uncertain
launch outcome and must not trigger an automatic duplicate launch.

`list_android_actions` and `invoke_android_action` expose the same bounded
semantic action catalog used by desktop UI and Android App Functions. It
currently includes document open/create, application details, notification
access, wireless settings, and sound settings. Action metadata declares
required, optional, presentation, and Activity-result parameters rather than
requiring a caller to reconstruct an Intent.

`query_intent_handlers`, `launch_intent`, `open_uri`, `open_file`, and `share`
enter that same typed Android integration gateway. `launch_intent` accepts either
structured action, data, MIME type, target, categories, extras, and symbolic
flags, or a raw `intentUri` as the base with structured fields applied on top.
The raw form is a mode of the same gateway, not a separate launch path.

Activity commands also share built-in tools' `placement` contract: `auto`
selects the supplied `displayId`, otherwise the sole Desktop or phone when no
Desktop exists. Multiple workspaces require an explicit destination; `phone`
selects ordinary display 0; `display` requires a display id; `desktop` requires
an active session. Ordinary placement can coexist with Desktop on the same
display without joining it. This applies to application launches, Intents, URI/file/share
and clipboard actions, published shortcuts and notification Activity actions.
Ordinary launches use fullscreen Activity options and do not acquire HOME,
provision Desktop, or initialize its organizer/input/session coordinators.
They reject windowed mode, relative bounds and exact managed-task reuse before
dispatch. Reusable explicit Activities are matched by application identity,
preferring the destination display; an existing managed task is released by its
owner before ordinary placement. Android's manifest and Intent semantics remain
authoritative for creation and reuse.

`move_task` reuses the exact task and accepts `placement=auto|desktop|display`
and `mode=auto|windowed|fullscreen`, including ownership changes on the same
display. It never routes input. `list_tasks` reports `ownership` as `desktop`,
`independent`, `system` or `unknown`; window mode alone does not identify ownership.
`close_desktop` releases only managed applications to independent fullscreen on
their existing display. Only display-loss recovery moves them to the phone.

An ordinary launch returns `accepted=true`, `taskObserved=false` and an
observation hint, without inventing a task id or a reuse result. It means
Android accepted dispatch, not that the requested page appeared. Use `ui.wait`
on the selected display to confirm the actual interface. No additional task
observer, polling loop, or guessed delay is started for ordinary launches.

Activity presentation has four independent inputs: `mode`, relative `bounds`,
`instance`, and optional `preferredTaskId`. Bounds use a `0..10000` scale
within the desktop work area and require `mode=windowed`. `instance` is exactly
`reuse` or `new`; document-task flags are not accepted as a substitute.
Known manifest restrictions (`singleTask`, `singleInstance`, or
`documentLaunchMode=never`) reject `new` before dispatch when a matching task
already exists in the same user profile on any display; use `reuse` instead.
The first instance is allowed.
A preferred task id addresses one existing managed task and therefore requires
`instance=reuse` plus its explicit current mode. A missing or mismatched task
fails instead of creating another window. `bounds` cannot accompany an exact
task id because delivery does not move or resize that task.

Activity intents targeting Desktop use its production launch coordinator. Every managed
application task is requested as `ACTIVITY_TYPE_STANDARD`; its result includes
the exact observed task id, display, activity type, mode, bounds, and reuse
state. Observation reuses the existing task event journal and one-shot typed
task snapshots, so Android integration adds no periodic task query. Public
direct intents retain their full Parcelable form through the privileged-service boundary,
which preserves `ClipData` and typed extras. Intents carrying read/write URI
grants, choosers, required system resolvers, and allowed targets requiring the MagicDesk app identity use
an immutable one-shot `PendingIntent` created by the app. Shell sends that
creator-authorized token with the requested display, STANDARD activity type,
mode, and bounds. Android therefore evaluates target access and URI grants as
the app while privileged task placement remains shell-owned. A focused adapter
selects the compatible creator and sender background-start modes for Android 14, 15
and 16.

Only Activity-result requests need a relay lifecycle. They keep their nested
target in a bounded app-process store and send an opaque relay id through shell.
Claiming an id is atomic and idempotent because Android may create the
short-lived relay Activity twice during task handoff. The first instance owns
the payload and a duplicate exits without executing it again.

System selection surfaces use package-scoped task identity because Android may
replace their published launcher component during handoff. A result relay uses
its own exact transport identity and always receives a distinct transient task;
it never reuses or moves an existing task belonging to the result target.
Direct same-package intents continue to use exact-component task actions.
`expectResult=true` returns a `requestId`;
if a result request was allocated before a launch observation failure, its
`requestId` is returned in the failure observation as well. A retryable launch
failure does not by itself prove that the external Activity cannot still reply.
`get_intent_result` reads or waits up to 60 seconds for its bounded,
process-local, event-driven state. `consume=true` removes a terminal result and
releases any persistable URI grants retained for it, including when response
serialization fails. Each read returns an independent data snapshot; editing
it cannot affect later reads or URI-grant ownership. The result projection
preserves the primary URI and inspects at most 32 `ClipData` items;
`clipUrisTruncated` reports additional uninspected items. URI addresses and
identity fields are limited to 8192 UTF-16 code units and rejected rather than
shortened. An oversized returned URI makes the request `failed` before any
persistable grants are acquired. Scalar extras inspect at most 32 keys,
including unreadable and unsupported values. `extrasTruncated` reports omitted
fields, an unreadable Bundle, or text shortened to the 8192-unit limit. Text
truncation never splits a valid surrogate pair; keys and URI-valued extras are
omitted rather than shortened. Non-finite numbers are omitted without losing
other fields. These are projection bounds, not limits on Android's Bundle
unmarshalling. The event journal records result metadata counts, not the URI
addresses or extras themselves.
Implicit targets are resolved by the shell-side package manager so MCP
discovery and execution use the same package-visibility scope. Resolution is
typed as one concrete handler, a required system resolver, or no handler. A
separate authorization result checks component enabled/exported state and any
required permission against the MagicDesk application identity before shell
receives placement work. Shell authority never converts a denied application
launch into an allowed one. A public concrete target with no required
permission or URI grants uses the direct shell path; a permitted target that requires the app
identity uses the app-created token. MagicDesk can likewise authorize its own
non-exported Activity, while an external non-exported component remains denied.
A required resolver and chooser remain implicit inside the same app-created
token rather than exposing an internal resolver component to shell.
Content-grant launches retain the app as their grantor through the same token,
including when a concrete editor was selected by Open With or a saved default.
Android binds those grants to the receiving Activity's task lifetime; the
gateway does not issue separate package-wide URI permissions before launching.

Concrete launches confirm the identity and topology of the exact task reported
by the production launch path. Choosers and required resolvers confirm that
same task's STANDARD/display/mode topology without guessing which final target
the user will select. A topology failure removes a task only when its id was
reported by the framework's task-created callback and was absent from the
global pre-launch task snapshot; reused or moved tasks are never removed by
launch rollback. An indeterminate outer timeout is reported as retryable and
does not trigger an unsafe task deletion.

`open_file` accepts either one shell-visible absolute path or an existing
content URI. Shell paths use MagicDesk's existing bounded file-grant provider.
`share` supports text and one or more shell paths or content URIs. Grants are
read-only unless `open_file` explicitly requests writable access and the
source is writable. No file bytes are copied into an MCP cache.
The complete request is validated before its prepared shell-file URIs enter
the provider registry. A failed preparation therefore cannot publish only part
of a selection; after dispatch, observation timeouts do not revoke a possibly
active recipient's access. `share` validates the complete file array against
the common 64-item content limit and preserves the shared text's indentation,
spaces, and trailing newlines. MIME selection uses the same content model as
the clipboard and drag-and-drop.

`get_activity_history` returns the newest actual Activity launches from the
same bounded evidence included in compatibility diagnostics. It adds no probe,
listener, or periodic task query and omits full content URIs and Intent extras.
The built-in developer Activity Explorer uses the same handler query,
authorization decision, presentation model, production launch coordinator,
and history; it is not an automation-only execution path.

`list_app_actions` reads Android's published shortcut service under the
authorized shell identity. Static manifest metadata may enrich an action's
icon, but it is never an executable fallback. Each result identifies its
published source. `invoke_app_action` resolves the current system
`PendingIntent` for `package + shortcut id`. Ordinary placement sends the
token through the shared shell Activity transport; Desktop placement uses the
same window pipeline as Start and application context menus. Managed shortcut task observation and reuse are
package-scoped because the optional published metadata Activity may redirect
to another Activity in that app.
MagicDesk never reconstructs the shortcut's private Intent. Notification tools use
only opaque keys and `PendingIntent` objects already held by the connected
notification listener; they do not synthesize an equivalent Intent.

`search_app_functions` uses the framework search service available from API
37. `execute_app_function` is available from API 36 and accepts a typed
`GenericDocument` JSON representation. Both calls are callback-driven with a
bounded timeout and execute under the authorized shell service identity. They
do not add a background observer or polling loop. Parameter documents also
have bounded encoded size, nesting depth, property count, string length, and
array length before they cross the shell Binder boundary.

Visible Activity, chooser, shortcut, notification, and App Function tools are
part of the authenticated catalog. `send_broadcast` and `start_service` can mutate
application state invisibly, so executing them requires the shell permission.
They remain visible in the catalog while disabled.

## Events and Waits

`magicdesk://events` and `magicdesk.get_events` expose a bounded, process-local
journal. Events come from the existing production observers and include:

- task add/remove, display move, focus, top activity, visibility, window mode,
  and bounds;
- display add/remove/change;
- taskbar, popup, wallpaper, touchpad, and control-panel state;
- pointer bridge loss and restoration;
- observed desktop application crash or ANR;
- recording and self-test lifecycle;
- MagicDesk process and MCP server lifecycle plus action outcomes.

The `wallpaper_rendered` event includes the selected source (`bundled`, `custom`,
or `fallback`), bitmap/drawable/view dimensions, and bitmap/display density.
These values are captured once after the selected frame commits.

The journal keeps at most 256 entries and contains no keyboard text or user
file contents. Compatibility reports include a 24 KiB bounded tail of at most
64 events so reports from remote devices retain task, focus, display, and input
ordering. It is observability, not persistent telemetry.

`magicdesk.wait_for_state` observes the condition and waits on the shared event
journal. Scoped task waits reuse the active session's task publication;
unknown observation never proves task absence. Unscoped absence requires a
global query, and requests outside that publication's display scope retain
fresh queries. Android `View` and input-window state can change without a
journal event, so these conditions and fresh-query scopes retain a bounded
200 ms recheck only while the explicit wait is running. Conditions include desktop
active/inactive, task present/absent/mode/focus/bounds, pointer readiness,
application ready/crashed/not-responding state, blocking system-dialog
visibility, MagicDesk UI visibility, taskbar, wallpaper, and self-test
completion.

`ui_element_state` waits for exact visibility and optional enabled, focused,
or selected state by semantic id. `popup_state` waits for popup visibility and
can require an exact popup title. These conditions cover appearance and
disappearance without synthetic pointer coordinates.

`magicdesk.begin_trace` records an event-sequence baseline. A matching
`magicdesk.end_trace` returns the intervening bounded journal events, a
separate failure/crash/ANR list, and final runtime and task snapshots. Traces
are process-local, keep no additional event history, and at most 16 may remain
open. `truncated` explicitly reports whether the shared 256-event journal
evicted the beginning. Traces are intended to wrap one reproducible operation
rather than provide persistent telemetry.

Asynchronous commands return when MagicDesk accepts the request. Use
`wait_for_state` to establish the required postcondition instead of assuming a
fixed delay.

Observation expiration returns `success=true`, `matched=false`, and
`waitExpired=true`. It does not cancel or fail the observed operation. A matched
condition has `waitExpired=false`; invalid requests and observation errors are
still tool failures. Always inspect `matched`, not only the common envelope.

Read-only resources are available at `magicdesk://state`,
`magicdesk://displays`, `magicdesk://tasks`, `magicdesk://apps`,
`magicdesk://events`, `magicdesk://diagnostics`, and
`magicdesk://self-test`.

## Security Boundary

- Loopback and optional network listeners have independent bindings, tokens
  and live permission policies. Network binds one selected private IPv4 address,
  not a wildcard, and its HTTP traffic is unencrypted.
- A 256-bit token authenticates every request with constant-time comparison.
- Supplied browser origins are validated against the listener's binding policy;
  network requests must use the exact bound origin.
- Request lines, headers, bodies, workers, queues, screenshots, list pages,
  event history, shell sessions, and Terminal reads are bounded.
- ADB forwarding is an optional transport, not authorization. Any client that
  can reach a listener must still provide its token. Treat it as a password.
- MCP permissions do not elevate the service identity. With an unrestricted root service,
  shell-gated operations consequently have root privileges by the user's
  explicit choice.
- Android handler discovery reports the selected visibility scope, exported
  state, required permission, and exact component. Actual execution remains
  subject to Android's component and permission checks.
- A valid token permits observation; mutations and sensitive content require
  their respective grants. Shell and input grants allow broad device control.
  Revocation affects subsequent requests, not accepted operations. Keep unused
  listeners disabled.

## Android App Functions

On Android 16 and newer, MagicDesk publishes App Functions for reading desktop
state, starting or closing a desktop, launching an Android application,
opening Settings, listing and invoking semantic Android actions, and reading
their Activity results. Action parameters and results use the same JSON
contracts as the authenticated automation gateway.

The platform protects the service with
`android.permission.BIND_APP_FUNCTION_SERVICE`. Ordinary applications cannot
bind directly. Android 14 and 15 keep the component disabled. App Functions omit
force-stop, synthetic input, self-test, direct filesystem operations, and
shell execution. This published service is independent of the MCP tools that
discover and invoke App Functions exported by other applications.

## Self-Tests

Diagnostics shows live test progress from the same `DesktopSelfTestRunState`
snapshot exposed by MCP: current stage code/label, last completed check/result,
PASS/WARN/FAIL/NOT_TESTED counts, cancellation and cleanup state. Visible UI
subscribes to changes; there is no progress polling or repeated compatibility
report collection. Preparation and execution outlive Activity recreation.

During simulated, wired and wireless tests, the phone's Diagnostics task also
acts as the input guard. It keeps the screen awake, records unexpected touches,
keys and disappearance, and permits the Stop self-test button. Planned phone
task transfers retain their existing displacement checks. Phone tests never
keep Diagnostics above the workspace under test. Cancellation still runs cleanup
and preserves the previous saved result.

Interactive self-tests require an awake, unlocked device and a visible target.
Phone and external test preparation waits for the selected workspace's registered
HOME host and completion of the production start operation. Target selection alone
does not admit window checks or cleanup. The wait observes existing host and
transition events; the normal window, render and input assertions still follow.
`get_state.readiness` reports awake, lock, and privileged-service prerequisites and explicit
required actions; unknown lock observations never mean ready. `get_state.app`
includes a source build identity, process instance id, and installation time,
so builds sharing a version number and restarted processes can be distinguished.

`get_self_test` separates `currentRun` from `lastCompletedResult`. Each has its
own run id, target, build identity, and structured checks/failures. Current-run
checks retain the last 512 entries and preserve the first failure separately;
the saved result retains the first 512 checks and up to 512 failures with
explicit truncation flags. `includeReport=true` adds a bounded text report from
that saved result only. A cancelled run does not replace the last saved result.
`wait_for_state(condition=self_test_finished, runId=...)` matches only that run,
including its saved result after a process restart. It never attaches an older
report to a new request. Outcome and cancellation reason are distinct fields.
Expected fixture-display removal is owned by the exact test run and does not
mark successful cleanup as user/session cancellation.

MCP can start and observe phone, simulated, wired, and wireless tests without
weakening their assertions or changing their production cleanup path. The
optional `mode` is `full` by default. `fail_fast` stops the workflow after the
first recorded FAIL, while still running task/display cleanup, restoring the
phone orientation policy, and writing final transition diagnostics.

An independent scenario may contain required, dependent steps. In `full` mode,
a required failure skips the rest of that scenario, retaining its original FAIL.
The application-fullscreen scenario records `WINDOW-APP-REMAINING` when this
happens. `WINDOW-APP-CLEANUP` then closes temporary fixtures through production
task cleanup and verifies the retained primary window's mode, bounds and input
focus before later window/input checks can run. Cleanup is observed under its
own stage, not the failed assertion. Failed cleanup aborts the remaining workflow;
`fail_fast` and cancellation proceed directly to the global finalizer instead.

`WINDOW-022` recreates the immersive browser fixture through Android's Activity
lifecycle. Its saved state restores the application request and client layout;
the check requires a fresh frame acknowledgement, fullscreen geometry and input
focus. Hardware configuration changes must not silently reset the fixture's
toolbar while a previous instance's fullscreen marker remains on disk.

The instrumented phone round-trip (`WINDOW-009`, `WINDOW-008`, `WINDOW-014`)
tests native task placement and surface continuity without Desktop admission.
`WINDOW-OWNERSHIP-001` and `002` require the task to remain independent on the
phone and after the direct freeform return. `WINDOW-OWNERSHIP-003` explicitly
admits it through the production Desktop gateway and verifies managed focus
before continuing the window workflow. Moving a task is not implicit ownership.

`INPUT-VIRTUAL-001` checks the production phone-pointer device and route readiness
on external targets using existing input lifecycle events. Synthetic window-input
checks bypass this device, so they cannot establish its readiness. This does not
claim coverage of physical mouse input or finger gestures on the phone touchpad.

The pre-run transition health entry records a one-shot WMShell queue snapshot,
separately from WindowManager's transition-performance sessions. A window-setup
query explicitly selects the WMShell dumpable: SystemUI's default critical-only
dump may omit this normal-priority section. Missing queue data remains unknown,
not idle. A window-setup
idle timeout includes the final pending tokens, ready-during-sync queue, and
active tracks. The same bounded snapshot is included in Compatibility reports.
An old pending token is not ignored: the idle assertion remains strict. Comparing
the before-test snapshot with the timeout distinguishes pre-existing SystemUI
state from a transition created during the run, without background observation.

Every self-test session uses an isolated workspace policy: it neither restores
the saved user window stack nor persists test window state. The phone rotation
is locked at its current value for the run and restored exactly afterward. If
the tested desktop session closes, its existing lifecycle event cancels the run
and cleanup begins; no background session polling is added.

The native-caption snap scenario (`NATIVE-SNAP-001` through `003`, with dependent
`FOCUS-006` and `007`) runs only when the platform diagnostics provider defines
that scenario. Currently only the Nubia windowing profile opts in. Other
platforms record these checks as NOT_TESTED with an explicit coverage reason;
the test does not guess caption actions from another firmware's button layout.
This does not declare native snap unsupported. Common placement, focus,
Alt+Tab, maximized and fullscreen checks still run on every platform. Failures
inside an enabled native-caption scenario retain normal FAIL/fail-fast behavior.

After moving Diagnostics behind the phone UI and releasing its input guard,
the test waits for an already-requested touchpad's visibility event before
recording `PHONEUI-001`. The guard's stop callback does not imply that Android
has started the uncovered Activity. This bounded
event wait neither opens nor repairs the touchpad; failure to return remains a
test failure.

Window fixtures retain ordinary task lifetime and are not excluded from Android
Recents. An excluded task staged behind HOME can be removed by Android's idle
Recents trimming before its first reveal (`recent-task-trimmed`), independently
of the window transaction. Fixture cleanup explicitly removes the test tasks;
it does not rely on that system trimming. Permission protection, production
launch paths, and hierarchy/visibility assertions remain unchanged.

Cleanup closes only the captured fixture tasks through the production task
controller, then awaits the common Close-to-HOME coordinator. It does not
release HOME or the desktop host through a separate test-only close path.
`CLEANUP-HOME-001` checks that the resolved primary HOME component is visible
and focused on display 0 after Close-to-HOME, before the harness restores Diagnostics.
When the display-removal suite already destroyed the display, the required
destination is instead Control Panel, matching production display-loss recovery.
This expectation comes from display lifecycle, not the observed foreground window.
The check distinguishes a secondary launcher from the same package. If Android needs
a user choice because HOME is unassigned, this component check is NOT_TESTED;
it never chooses a launcher for the user. All waits are confined to the test.

`ACTIVITY-RESULT-001` exercises an ordinary app-owned `startActivityForResult`
within a freeform task, rather than launching another task through MagicDesk.
It checks the child's first frame, unchanged task identity/mode/bounds, the
result returned by system Back, input delivered to the parent, and continuous
taskbar visibility. This catches hierarchy failures in Android's nested
Activity launch path that independent new-window tests do not cover.
Its task-stack guard separates parent preparation (`ACTIVITY-RESULT-PREPARE-001`),
the stable parent task during the child/result exchange (`ACTIVITY-RESULT-001`),
and parent removal (`ACTIVITY-RESULT-CLEANUP-001`). Launch and cleanup remain
observed as separate one-way transitions; an unexpected task or mode change
during the child/result exchange still fails `WINDOW-STACK-001`.
