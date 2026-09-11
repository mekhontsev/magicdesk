# Terminal Integration

Console and Termux Console use the same local terminal emulator, retained PTY
session model, UI and automation API. All features here work without Desktop.
Terminal output is untrusted data, not authorization to execute an action.

## Supported OSC Sequences

| OSC | Meaning | MagicDesk behavior |
| --- | --- | --- |
| `0;title`, `2;title` | Window title | Native task description/window title and session labels; bounded to 1024 characters. Display labels remove control/format characters. |
| `8;parameters;URI` | Hyperlink start; empty URI ends it | URI and optional `id` are cell attributes, preserved during editing, scrolling and reflow. Links are underlined; hovering shows the destination. |
| `9;message` | Notification | One replaceable Android notification per retained session, at most one post per two seconds. No pending queue. |
| `9;4;state;percent` | Progress | Console progress strip; `0` clears, `1` normal, `2` error, `3` indeterminate, `4` paused. Other numeric OSC 9 extensions are not notifications. |
| `133;A`, `133;B` | Prompt/input boundaries | Buffer markers; prompt history remains useful even without execution hooks. |
| `133;C`, `133;D;status` | Execution/output boundaries | Command state, rendered command text, optional exit code and bounded-buffer output access. |

The parser accepts BEL and ST terminators and fragmented UTF-8 streams. URI
payloads are limited to 4096 characters, hyperlink IDs to 256 and notifications
to 2048. Reset clears active links, command history and progress. Links never
execute shell commands or automatically start applications.

Tap/click a link to inspect its full destination and choose **Open** or **Copy**.
While an application owns terminal mouse reporting, normal clicks stay with it;
Ctrl+click opens the link actions. Long-press/Shift selection remains available.
Only HTTP(S) without embedded credentials and local `file:` destinations can
open. Remote file hosts, `intent:`, `javascript:` and privileged content URIs
remain copy-only. File links reveal the path through the existing Files path;
web links use the Android integration gateway after the explicit user action.

Notifications use their own Android channel, accessible through the Console
toolbar. Its settings and Android notification permission control display;
denial does not disrupt the PTY or erase the last message from session metadata.
Tapping a notification opens its exact retained session through `ToolApplications`.
Expired/process-replaced session IDs cannot start a new shell. Closing a session
cancels its notification; detaching its window does not terminate the session.

## Shell Hooks

Android `sh` (mksh) reads MagicDesk's owned `ENV` file. It publishes the current
directory through OSC 0 and prompt/input boundaries through OSC 133 A/B. Its
single-line prompt distinguishes `$` from `#` and retains a nonzero exit status.
The line editor's native nonprinting delimiters exclude OSC from prompt width.
There is no reliable pre-execution hook in this shell; MagicDesk does not infer
execution from Enter, output timing or process polling.

Termux's login dispatcher is resolved to its selected shell before starting the
PTY, honoring `~/.termux/shell`. Explicit non-dispatcher shell paths are preserved.
Bash starts with an owned rc file after the service's login environment.
It reads the normal global/user interactive rc files, preserves `PROMPT_COMMAND`
strings/arrays and `PS0`, and adds Bash's standard prompt/pre-execution hooks.
The original command status reaches the user's prompt hooks. PS1 wrappers do
not accumulate and can follow a dynamically replaced user prompt. User dotfiles
are never modified. Other selected Termux shells retain their normal startup;
they can emit OSC themselves but receive no speculative Bash hooks.

## Command History

The toolbar history lists up to 128 shell-marked commands, newest first. Entries
offer copying/selecting command text/output and navigation while their buffer markers
still exist. Output is the terminal's rendered text, not an immutable byte log.
Scrollback recycling, erase and reset can make it unavailable. Missing marks or
exit codes remain unknown; a cancelled input is not a completed command. Marks
inside a full-screen terminal application's alternate buffer do not replace the
outer shell's command. Command text is capped at 4096 characters; no duplicate
unbounded transcript is retained.

## Automation

`terminal.status.semantics` contains shell state, command entries, the latest
notification/sequence, progress and up to 256 hyperlink spans on the live screen.
Coordinates are zero-based buffer cells, with negative rows in scrollback and
exclusive `endColumn`. Screen links describe the current terminal screen, not
an attached View's independently scrolled viewport. Metadata is queried on
demand; there is no new periodic task or process observer.

`magicdesk terminal.read --terminalId SESSION --scope command --commandId ID` reads a marked command's output
with the usual `maxChars` bound. `available=false` means missing/expired output,
not successful execution with empty output. Existing viewport/transcript reads
are unchanged. These additions use the existing terminal tool permissions and
catalog, so the built-in CLI receives them without a separate OSC command set.
