# External Commands

Android applications can execute commands through MagicDesk on Android 14+.
This API uses an explicit Android service Intent, not MCP, HTTP or a token.
Desktop is optional. It does not acquire HOME, create displays or claim input.

## Authorization

The caller declares and requests the dangerous runtime permission
`io.github.mekhontsev.magicdesk.permission.RUN_COMMAND`. The user also enables
**External command access** in MagicDesk Settings > Automation; it is off by default.
Android enforces the permission at the exported service boundary. No caller-package
extra grants authority. APK-exported application launchers do not receive this permission.

Grant this access only to trusted applications: it permits arbitrary commands
under the configured shell/root or selected Termux identity, not a restricted subset
of MCP operations. Shroot environment selection is not a security sandbox.
User/Shell/Shroot requests must name the expected actual UID; a mismatch fails.
`app` selects MagicDesk's own UID. `shell` permits 2000 or 0; `shroot` selects
the library of the named app or service UID.
Termux requests must name the currently selected package. MagicDesk neither starts
a new privilege backend nor switches identities to satisfy a request.

## Request

Start `io.github.mekhontsev.magicdesk/.RunCommandService` using
`startForegroundService()`, with action `io.github.mekhontsev.magicdesk.RUN_COMMAND`.
The caller remains subject to Android background-service and Activity-launch rules.
A visible caller Activity is the simplest entry point. The service is not restarted
after process death and requests are not replayed.

Extras:

- `request`: a JSON string, at most 64 KiB UTF-8.
- `result`: optional mutable `PendingIntent` targeting an explicit receiver in the caller.
  Use a different request code/action for concurrent calls. An immutable callback
  cannot receive result extras. The receiver can remain unexported. Omit this extra
  for fire-and-forget automation, including clients that only send service Intents.
  Without a callback there is no result or error delivery.

```json
{
  "version": 1,
  "backend": "shell",
  "executorUid": 2000,
  "program": "/system/bin/sh",
  "arguments": ["-c", "printf 'hello\\n'; printf 'diagnostic\\n' >&2"],
  "cwd": "/data/local/tmp",
  "mode": "background",
  "timeoutMillis": 60000
}
```

| Field | Meaning |
| --- | --- |
| `version` | Required; `1`. Unknown versions/fields fail. |
| `backend` | Required: `app`, `shell`, `termux`, or `shroot`. |
| `executorUid` | Required for app/Shell/Shroot: the actual executor UID. Shell accepts `2000` or `0`; app accepts only MagicDesk\'s UID. Omit for Termux. |
| `termuxPackage` | Required only for Termux, e.g. `com.termux`. |
| `environmentId` | Required only for Shroot; stable UUID from `shroot list --json`. |
| `program` | Required absolute executable path in the selected environment. |
| `arguments` | String array, at most 256 items. No implicit shell expansion. |
| `environment` | Up to 128 string-valued environment overrides for the program. |
| `cwd` | Optional absolute working directory in the selected environment. |
| `stdin` | Optional text, background mode only; omitted input is EOF. |
| `mode` | `background` (default), `terminal`, `x11`, or `wayland`. |
| `displayId` | Interactive modes only; defaults to the active Desktop or phone. Multiple Desktops require an explicit display. |
| `timeoutMillis` | Exit/launch deadline, 1000..86400000; default 60000. Not a delay. |
| `keyboardDirectory` | Optional absolute host XKB directory for shell/Termux graphics. Shroot obtains keyboard data from its selected environment. |

Shell syntax is opt-in via an explicit shell program and `-c`. All strings must be
NUL-free. The example explicitly selects Android's system shell; MagicDesk does
not rewrite requested executable paths. Its own Android command wrappers and
interactive consoles use the bundled mksh, not `/system/bin/sh`.
For Shroot, paths and environment variables apply inside the selected
installed environment; no implicit distribution is used. Its ordinary launch
recipe supplies graphics, audio and file integration. Graphical Termux commands
use the existing Termux X11/Wayland setup. Raw Android shell GUI commands still
need suitable Linux programs and keyboard data; selecting a graphical mode does
not install them. Interactive commands also obey the shared Desktop Entry
expanded-command limit of 4096 characters.

## Result And Lifetime

The callback receives `requestId`, `status`, `stdout`, `stderr`,
`stdoutTruncated` and `stderrTruncated`, plus `error` on failure.
Each output retains at most 16 KiB of source bytes, decoded as UTF-8. Large output
is drained continuously, not accumulated until exit. Binary output should be
written to a file instead. A nonzero program exit is an ordinary `exited` result,
not a transport failure.

- `exited`: background command ended; `exitCode` is present.
- `launched`: terminal/graphical launch handed off. No exit code or output is
  promised for these independently owned sessions; a PTY combines stdout/stderr.
- `failed`: validation, unavailable executor, startup or transport failure.
- `timed_out`: deadline expired; resources not yet handed off are cancelled.
- `cancelled`: service stopped, including access being disabled or MagicDesk Exit.

At most four requests run concurrently. Background commands own a cancellable
pipe relay, shared by shell and Termux; closing it terminates its process session.
The API does not promise supervision of programs that deliberately escape that
session. After a successful interactive handoff, the terminal/graphics services
own the session, not the result callback or requesting app. Callback cancellation
never replays a command. Process death can lose the callback; clients must not
assume a missing reply means the command did not execute.

## Device Fixture

`./gradlew -PcommandClientFixture :run-command-client:assembleDebug` builds a
separate-UID test APK, not included in MagicDesk. Its `ClientActivity` takes
`case` (letters/digits/underscore/hyphen) and `request` extras, calls the production
API from a visible Activity, and saves the callback to its private `files/<case>.json`.
Launch it on an explicitly selected test display. Grant/revoke the declared
runtime permission to test both authorization outcomes; enable/disable MagicDesk's
setting through Settings. Read results with `run-as` on the debug fixture.
Remove the fixture and test display after testing.
