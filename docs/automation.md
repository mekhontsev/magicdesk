# MagicDesk Automation

MagicDesk exposes one typed desktop automation gateway through two adapters:

- a local Model Context Protocol (MCP) server for user-authorized clients;
- Android App Functions for authorized system agents on Android 16 and newer.

Both adapters use the production session, task, window, input, capture, and UI
controllers. Automation does not implement a second desktop policy or a
parallel task observer.

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

### Starting After a Phone Reboot

When the MCP server is enabled, opening MagicDesk from its normal launcher icon
starts the server before the Shizuku compatibility audit. This intentionally
does not start desktop, input, task-observer, or vendor runtime components.
An automation client can therefore connect first and bring up Shizuku later;
the same MagicDesk process then promotes to the full runtime after the normal
audit succeeds, without replacing the MCP connection.

For Codex on the phone, open MagicDesk once after a reboot. A client with live
MCP reloading can then use `/mcp reload`; otherwise restart or resume the client
once so it discovers the server. No automatic boot receiver is installed.

## Access Levels

The tool catalog has three explicit levels:

- Normal tools read and operate the desktop through ordinary MagicDesk
  workflows.
- **Developer automation tools** adds self-tests, synthetic pointer and key
  input, and package force-stop.
- **Files, shell, and Terminal automation tools** separately adds direct
  access to the shell-visible filesystem, persistent headless
  `/system/bin/sh` sessions, and visible interactive PTY windows. This level
  can read, modify, and execute data with the connected shell identity.

The two optional levels are independent and disappear from `tools/list` while
disabled. Turning off the MCP server also turns off both optional levels and
closes all MCP-owned headless shell sessions. User-opened Terminal windows keep
their normal desktop lifecycle.

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

## State and Observation

The MCP catalog exposes unqualified tool names such as `get_state`; clients
add the configured server name, so documentation uses `magicdesk.get_state`.

Normal read tools include:

- `magicdesk.get_state`: session, shell, platform, runtime, MagicDesk-owned UI,
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

Task and application lists accept filters plus `limit` and `cursor`. Returned
pages contain `count`, `total`, and a nullable `nextCursor`.

`get_state.ui` reports the taskbar, Start, popup, wallpaper, desktop plane,
touchpad, and phone control panel. Bounds are included for surfaces owned by
the desktop host. Screenshot capture returns PNG bytes as MCP image content and
does not create a file. Pixel sampling reads up to 64 coordinates in one shell
capture operation and returns exact ARGB and component values.

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

During an external desktop session, `list_ui_elements(displayId=0)` also
exposes the visible phone HOME Start. Its `start.*` IDs are local to that
display, so `invoke_ui_action` with display 0 launches on the phone while
the same ID on the desktop display retains desktop behavior. Phone HOME
actions are `phone.controls`, `phone.touchpad` and `phone.close_desktop`.
The registry is removed when that HOME stops; this does not create another
desktop session or a background UI observer.

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

`magicdesk.set_app_presentation` accepts an Android package and a scale from
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

Developer-only commands are:

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

Clipboard automation uses Android's system clipboard through the same gateway
as Console and built-in UI copy actions. Reading is explicit, returns bounded
text plus MIME metadata, and may require a focused MagicDesk window under
Android clipboard privacy rules. Writing supports Android's sensitive-content
marker. These commands require Developer automation; clipboard contents are
never exposed as an MCP resource, included in diagnostics, or declared as App
Functions.
`clipboard.open` accepts one clipboard URI or an HTTP(S) link, while
`clipboard.share` sends text and bounded URI items through Android's chooser.
Both preserve `ClipData` URI grants and enter the normal desktop Intent launch
coordinator.

Shell-gated commands are:

- `magicdesk.files.list`, `magicdesk.files.stat`,
  `magicdesk.files.create`, and `magicdesk.files.rename`;
- `magicdesk.console.open`, `magicdesk.console.execute`,
  `magicdesk.console.status`, and `magicdesk.console.close`;
- `magicdesk.terminal.open`, `magicdesk.terminal.list`,
  `magicdesk.terminal.status`, `magicdesk.terminal.read`,
  `magicdesk.terminal.write`, `magicdesk.terminal.send_key`, and
  `magicdesk.terminal.close`;
- `magicdesk.tmux.list` and `magicdesk.tmux.open`.

`console.*` addresses headless command sessions. Each session is persistent,
has its own current directory, returns bounded output and exit status, and is
bounded by the server lifetime. At most eight such sessions may exist at once.

`terminal.*` addresses actual user-facing Console windows by opaque
`terminalId`. It can inspect task/display identity, shell PID, dimensions,
working directory, backend, OSC title, derived task label, and optional
foreground PID/process group/executable; read the textual viewport or bounded
scrollback; and write text or semantic key events directly to the PTY. These
operations do not use screenshots or synthetic pointer coordinates.
`terminal.list` is a fast registry snapshot; `terminal.status` refreshes the
reported working directory and foreground process from the live PTY.

`terminal.open` accepts an optional `backend` of `shell` or `termux`. The
default is `shell`. A Termux terminal requires the installed Termux app, its
external-command setting, and the `RUN_COMMAND` permission; after launch all
other `terminal.*` operations are backend-independent.

`tmux.list` performs one bounded query under the Termux UID. Its successful
result has `available=false` when tmux is not installed, so absence of the
optional package is not reported as a transport failure. `tmux.open` accepts
exactly one of an existing `sessionId` returned by `tmux.list` or a session
`name` to open or create with tmux `-A`. It opens an ordinary visible Termux
Console and returns its `terminalId`; the remaining `terminal.*` tools then
operate on that window. Closing the terminal detaches the client while the tmux
session continues. These tools do not expose ordinary Termux application tabs.

`magicdesk.get_termux_x11_status` performs a bounded, non-destructive probe of
the configured display. It reports the matching Termux process, reconnect
listener, and Android viewer task as separate fields. The same typed snapshot
is included under `runtime.termuxX11` in `get_state`; that embedded copy is
cached and does not launch an external command during a state read.

`magicdesk.reconnect_termux_x11` sends the standard viewer handshake to the
configured running display. It never starts or stops the X server. Both tools
require Termux, Termux:X11, the Termux external-command setting, and the
`RUN_COMMAND` permission.

## Android Integration

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

Activity presentation has four independent inputs: `mode`, relative `bounds`,
`instance`, and optional `preferredTaskId`. Bounds use a `0..10000` scale
within the desktop work area and require `mode=windowed`. `instance` is exactly
`reuse` or `new`; document-task flags are not accepted as a substitute. A
preferred task id addresses one existing managed task and therefore requires
`instance=reuse` plus its explicit current mode. A missing or mismatched task
fails instead of creating another window. `bounds` cannot accompany an exact
task id because delivery does not move or resize that task.

Activity intents use the production desktop launch coordinator. Every managed
application task is requested as `ACTIVITY_TYPE_STANDARD`; its result includes
the exact observed task id, display, activity type, mode, bounds, and reuse
state. Observation reuses the existing task event journal and one-shot typed
task snapshots, so Android integration adds no periodic task query. Public
direct intents retain their full Parcelable form through the Shizuku boundary,
which preserves `ClipData`, URI grants, and typed extras. Choosers, required
system resolvers, and allowed targets requiring the MagicDesk app identity use
an immutable one-shot `PendingIntent` created by the app. Shell sends that
creator-authorized token with the requested display, STANDARD activity type,
mode, and bounds. Android therefore evaluates target access and URI grants as
the app while privileged task placement remains shell-owned. A focused adapter
selects the compatible creator and sender background-start modes for Android 15
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
`get_intent_result` reads or waits up to 60 seconds for its bounded,
process-local, event-driven state. `consume=true` removes a terminal result and
releases any persistable URI grants retained for it. Its
diagnostic projection keeps only bounded scalar extras and `ClipData` URIs, so
an external Activity cannot grow the registry or event journal without limit.
Implicit targets are resolved by the shell-side package manager so MCP
discovery and execution use the same package-visibility scope. Resolution is
typed as one concrete handler, a required system resolver, or no handler. A
separate authorization result checks component enabled/exported state and any
required permission against the MagicDesk application identity before shell
receives placement work. Shell authority never converts a denied application
launch into an allowed one. A public concrete target with no required
permission uses the direct shell path; a permitted target that requires the app
identity uses the app-created token. MagicDesk can likewise authorize its own
non-exported Activity, while an external non-exported component remains denied.
A required resolver and chooser remain implicit inside the same app-created
token rather than exposing an internal resolver component to shell.
When a direct shell launch carries content URIs, MagicDesk grants the resolved
package from its app identity before handing the Parcelable Intent to shell.

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
`PendingIntent` for `package + shortcut id`; the visible MagicDesk process
sends that token through the same desktop window pipeline as Start and
application context menus. Shortcut task observation and reuse are
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
part of the normal authenticated catalog. `send_broadcast` and `start_service`
can mutate application state invisibly, so they are present only while
Developer automation tools are enabled. Turning that setting off removes them
from `tools/list` and the shared action boundary rejects direct calls as well.

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

The journal keeps at most 256 entries and contains no keyboard text or user
file contents. Compatibility reports include a 24 KiB bounded tail of at most
64 events so reports from remote devices retain task, focus, display, and input
ordering. It is observability, not persistent telemetry.

`magicdesk.wait_for_state` is event-driven. It observes the condition, waits
on the shared event journal, and uses a bounded 200 ms recheck only for Android
`View` properties that do not emit a journal event. Conditions include desktop
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

Read-only resources are available at `magicdesk://state`,
`magicdesk://displays`, `magicdesk://tasks`, `magicdesk://apps`,
`magicdesk://events`, `magicdesk://diagnostics`, and
`magicdesk://self-test`.

## Security Boundary

- The listener binds only to literal IPv4 loopback, never Wi-Fi, USB
  networking, or an external interface.
- A 256-bit token authenticates every request with constant-time comparison.
- Supplied browser origins must identify a literal loopback host.
- Request lines, headers, bodies, workers, queues, screenshots, list pages,
  event history, shell sessions, and Terminal reads are bounded.
- ADB forwarding grants access to the host process that owns that forwarding
  connection. Treat the token as a password.
- MCP permissions do not elevate the shell identity. With root-backed Shizuku,
  shell-gated operations consequently have root privileges by the user's
  explicit choice.
- Android handler discovery reports the selected visibility scope, exported
  state, required permission, and exact component. Actual execution remains
  subject to Android's component and permission checks.
- The bearer token authorizes visible application actions as well as desktop
  actions. Keep the MCP server disabled when it is not in use.

## Android App Functions

On Android 16 and newer, MagicDesk publishes App Functions for reading desktop
state, starting or closing a desktop, launching an Android application,
opening Settings, listing and invoking semantic Android actions, and reading
their Activity results. Action parameters and results use the same JSON
contracts as the authenticated automation gateway.

The platform protects the service with
`android.permission.BIND_APP_FUNCTION_SERVICE`. Ordinary applications cannot
bind directly. Android 15 keeps the component disabled. App Functions omit
force-stop, synthetic input, self-test, direct filesystem operations, and
shell execution. This published service is independent of the MCP tools that
discover and invoke App Functions exported by other applications.

## Self-Tests

Interactive self-tests require an awake, unlocked device and a visible target.
MCP can start and observe phone, simulated, wired, and wireless tests without
weakening their assertions or changing their production cleanup path. The
optional `mode` is `full` by default. `fail_fast` stops the workflow after the
first recorded FAIL, while still running task/display cleanup, restoring the
phone orientation policy, and writing final transition diagnostics.

Every self-test session uses an isolated workspace policy: it neither restores
the saved user window stack nor persists test window state. The phone rotation
is locked at its current value for the run and restored exactly afterward. If
the tested desktop session closes, its existing lifecycle event cancels the run
and cleanup begins; no background session polling is added.

`ACTIVITY-RESULT-001` exercises an ordinary app-owned `startActivityForResult`
within a freeform task, rather than launching another task through MagicDesk.
It checks the child's first frame, unchanged task identity/mode/bounds, the
result returned by system Back, input delivered to the parent, and continuous
taskbar visibility. This catches hierarchy failures in Android's nested
Activity launch path that independent new-window tests do not cover.
