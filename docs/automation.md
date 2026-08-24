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

## Desktop Commands

Normal commands include:

- start or close a desktop session;
- launch, focus, close, resize, or change the mode of a task;
- arrange a task left, right, maximized, or restored through the same window
  transition path used by MagicDesk shortcuts;
- show Start or the desktop and open Files, Console, Task Manager, or Settings;
- inspect and invoke live desktop controls semantically;
- list and invoke an application's manifest actions;
- launch an Android specification or a supported `.desktop` file through the
  shared launch coordinator;
- start, stop, and inspect screen recording.

Use `tools/list` as the authoritative command and argument catalog.

Developer-only commands are:

- `magicdesk.force_stop_app`
- `magicdesk.run_self_test`
- `magicdesk.send_key`
- `magicdesk.move_pointer`
- `magicdesk.click_pointer`

Shell-gated commands are:

- `magicdesk.files.list`, `magicdesk.files.stat`,
  `magicdesk.files.create`, and `magicdesk.files.rename`;
- `magicdesk.console.open`, `magicdesk.console.execute`,
  `magicdesk.console.status`, and `magicdesk.console.close`.
- `magicdesk.terminal.open`, `magicdesk.terminal.list`,
  `magicdesk.terminal.status`, `magicdesk.terminal.read`,
  `magicdesk.terminal.write`, `magicdesk.terminal.send_key`, and
  `magicdesk.terminal.close`.

The historical `console.*` tool name denotes a headless command session: each
session is persistent, has its own current directory, returns bounded output
and exit status, and is bounded by the server lifetime. At most eight such
sessions may exist at once.

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

`magicdesk.get_termux_x11_status` performs a bounded, non-destructive probe of
the configured display. It reports the matching Termux process, reconnect
listener, and Android viewer task as separate fields. The same typed snapshot
is included under `runtime.termuxX11` in `get_state`; that embedded copy is
cached and does not launch an external command during a state read.

`magicdesk.reconnect_termux_x11` sends the standard viewer handshake to the
configured running display. It never starts or stops the X server. Both tools
require Termux, Termux:X11, the Termux external-command setting, and the
`RUN_COMMAND` permission.

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

## Android App Functions

On Android 16 and newer, MagicDesk publishes App Functions for reading desktop
state, starting or closing a desktop, launching an Android application in an
optional mode, and opening Settings.

The platform protects the service with
`android.permission.BIND_APP_FUNCTION_SERVICE`. Ordinary applications cannot
bind directly. Android 15 keeps the component disabled. App Functions omit
force-stop, synthetic input, self-test, direct filesystem operations, and
shell execution.

## Self-Tests

Interactive self-tests require an awake, unlocked device and a visible target.
MCP can start and observe phone, simulated, wired, and wireless tests without
weakening their assertions or changing their production cleanup path.
