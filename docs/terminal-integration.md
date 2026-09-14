# Terminal Integration

Console and Termux Console use the same local terminal emulator, retained PTY
session model, UI and automation API. All features here work without Desktop.
Terminal output is untrusted data, not authorization to execute an action.

## Implementation Boundaries

- `ConsoleTerminalSession` owns the transport, byte processing, emulator and
  session metadata. Neither an Activity nor an MCP connection owns its lifetime.
- `TerminalWindowAttachment` owns the optional window binding and its generation.
  Stale window cleanup cannot revoke a replacement's input or resize ownership.
- `TerminalViewport` owns window-local geometry, fractional history and selection.
  `ConsoleTerminalView` adapts Android gestures and layout;
  `ConsoleTerminalInputConnection` adapts IME composition.
- `TerminalFrame` supplies one synchronous render read with frozen palette,
  cursor and image-placement metadata. Cell rows are borrowed until the next
  emulator edit; retained moving rows are snapshots, never a second transcript.
- `TerminalScrollRegion` reconciles cell edits independently of Android drawing.
  `TerminalScrollAnimation` owns motion and departing-row Pictures.
- `ConsoleTerminalWindow` builds the toolbar/status surface.
  `ConsoleTerminalActions` owns explicit content operations, not terminal state.

Frame construction copies only bounded metadata and row references, not image
rasters or scrollback. The parser, presentation and renderer remain on the same
owning thread; no additional rendering thread or periodic refresh is introduced.
Model tests exercise geometry, selection, scroll reconciliation and render reads
directly. Android instrumentation additionally verifies real font/Canvas pixels.

## Interaction

The console layout reserves system-bar, cutout and visible keyboard insets.
Showing or hiding the phone keyboard resizes the existing terminal grid and PTY,
including a tmux client, without recreating its session. Toolbar actions use
bundled 24dp Lucide vectors with the same stroke weight and accessible labels.
The first button opens the session picker; its tooltip identifies the backend and
shell UID. Root sessions tint that button amber. Only startup, access or error
messages occupy a status row; ready sessions have no persistent backend label.
Finger swipes continue with Android's native fling physics after release. The
same scroll route handles local history, tmux mouse reporting and alternate-screen
arrow-key navigation. New input, selection, zoom, resize, focus loss or detach
stops the animation; terminal mode changes prevent stale scroll input reaching
a different screen. Idle terminals do not schedule scroll animation frames.
Local history follows finger and high-resolution wheel movement in pixels,
including partial rows. Text, images, links and selection handles share that
offset. tmux and other fullscreen terminal programs still receive discrete
wheel events or arrow keys and control their own scrolling.

Explicit terminal region edits (IL/DL, SU/SD and reverse index) animate in the
shared renderer, including tmux's full-width panes and vertically stacked panes.
The emulator reports the exact region and displacement before changing cells;
parsing, PTY input and transcript reads remain immediate. Only departing rows
are briefly retained as Android Pictures, bounded by the region height, with
existing image rasters shared rather than copied. Repeated edits preserve both
the current fractional offset and velocity. An analytically integrated, critically
damped follower approaches the committed position without overshoot, instead of
restarting a deceleration curve for each packet. The response follows the cadence
of wheel/key commands sent by the view, not PTY packet boundaries. Until that
cadence is known, the display frame interval provides the initial response;
finishing a touch gesture/fling returns to that response for prompt catch-up.
There is no fixed per-packet animation duration. The integration is independent
of rendering cadence and never extrapolates output, delays parsing, or accumulates
an unbounded queue. Status bars and neighboring panes stay stationary. Buffer changes, clearing, resize,
selection and direct interaction settle presentation to the committed grid.
Different regions/directions start a new animation. Plain repaints, including
tmux's side-by-side pane redraws and PageUp in the tested configuration, do not
invent scrolling; ordinary streaming output remains immediate. No tmux options
or application-specific detection are involved.
This also limits scrolling with multiple tmux clients. If a pane is taller than
another attached client's viewport, tmux can replace line edits with a pane
redraw for all clients, including one where the pane fits. Hiding the IME can
cross that boundary by increasing this console's height. The renderer receives
only repaints in that case; resizing did not disable its animation. Client
dimensions and raw PTY commands must be checked together when diagnosing this
case. MagicDesk does not detach other clients or alter tmux sizing options to
force a particular output encoding.
Writes between scroll operations are tracked separately from cell transport and
its blanking. Ordinary updates, including changed letters in existing text,
follow their logical row's displacement. A write alone does not identify a
stationary panel. Viewport-local restoration is inferred from a fully restored
row band or a complete style span restored while its displaced copy is repaired
(or leaves the region). Partial writes can retain an already established span,
not discover a new panel from coincidentally matching letters. Comparing complete
spans keeps a counter or panel together. Identical adjacent history rows alone
are not evidence of a stationary band. Ambiguous ordinary updates stay moving.

For each scroll edit, the pre-edit viewport and post-transport moving grid remain
immutable. Presentation is rebuilt from those snapshots and the accumulated
writes before drawing or another scroll, never from an earlier intermediate
frame. Drawing between a clear and a rewrite cannot permanently reclassify text
or change the outcome of later writes. Only the reconciled moving grid is
transported by the next scroll; outgoing Pictures also use that grid. Restoration
works in either update order and both scroll directions, including output-driven
edits and kinetic scrolling. Snapshots and write masks are bounded to the active
region; frames without new writes reuse the reconciled rows.
In-place updates draw only at their current cells, never across a swept-path mask that
could split a moving line. Cell snapshots retain styles, links and image placement
identities without moving live command markers or duplicating image pixels.
The mechanism does not depend on PTY read boundaries or application identity.
Debug builds can trace edit regions, viewport geometry, presentation resets and
the first following frame with `setprop log.tag.MDTerminalScroll DEBUG`.
This opt-in log contains no terminal text. Disable it with
`setprop log.tag.MDTerminalScroll INFO`.

Touch selection has Android-themed start/end handles. Dragging either endpoint
adjusts the same terminal-cell selection used by mouse selection and copying.
The handles are attached application subpanels, not application overlays.
The Copy button offers exact text or **Copy as paragraph** for a selection.
Paragraph copying heuristically joins single line breaks and removes continuation
indentation, retaining blank lines, list starts and obvious code/table blocks.
It cannot recover semantic paragraphs from every TUI redraw. Keyboard copy and
terminal clipboard protocols remain exact; no-selection Copy reads the transcript.

## Sessions

Phone Control Panel and both console toolbars use one **Terminal sessions** picker.
The control panel has one terminal entry point; all session types are created in the picker.
It combines retained MagicDesk PTYs with tmux sessions discovered in the selected
Termux package. A tmux session and its MagicDesk client appear once; live tmux
client PIDs identify the current session even after switching inside tmux.
Ordinary shells that happen to run tmux are not reclassified as managed clients.
Discovery runs on demand, and missing Termux access does not hide local terminals.

Selecting a row shows its existing window or attaches a window on the selected
display. **New session** offers Android shell, Termux shell and tmux. Row actions
include Rename, Detach and explicit, confirmed termination. A local name belongs
to the retained terminal and takes precedence over OSC titles; tmux names belong
to the tmux server. Ordinary Termux app tabs are not exposed by its command API.

Managed tmux clients attach with `tmux -T hyperlinks attach-session` so tmux
forwards OSC 8 links to the console even though `TERM=xterm-256color` does not
advertise them. This declares a capability of that client only; it does not
change the user's tmux configuration or the features of other attached terminals.

Closing an ordinary console window retains its PTY, emulator and programs.
Ending the terminal releases its entire UNIX session, including foreground and
background jobs that ignore hangup. Processes that created an independent UNIX
session, such as a tmux server, are not terminated by PTY cleanup.
Closing a managed tmux window disconnects only its client by releasing that
client's controlling PTY; the tmux server retains the session and its programs,
subject to the user's tmux configuration. Configuration recreation and transfer
to a replacement window do not disconnect a client. Explicit termination of a
tmux session in the picker warns that all its windows and clients are affected.

## Font and Cell Rendering

Both consoles bundle JetBrains Mono NL Nerd Font Mono with real regular, bold,
italic and bold-italic faces. There is no font download, picker or dependency on
Termux settings. Font size remains a per-window sp value with a new-window default;
pinch and Ctrl+wheel resize the existing terminal grid and PTY.

The four faces share integer-pixel cell metrics. Nerd Font icons stay inside the
cells assigned by the emulator; Android supplies fallback glyphs where needed.
Combining text and double-width characters retain their logical terminal columns.
Ligatures and icons spanning adjacent blank cells are not enabled.

`TerminalCellGeometry` draws box lines (U+2500-257F), blocks/shades (U+2580-259F),
Braille (U+2800-28FF) and Powerline triangle/semicircle separators (U+E0B0-E0B7)
directly on that grid. Neighboring borders and blocks have no font-side bearings.
Other Nerd Font symbols use the bundled font. Colors, inverse/dim text, selection,
cursor, underlining and OSC 8 links use the same presentation path for both kinds
of glyph. Geometry never changes parsing, text width, hit testing or PTY output.

The debug-only `com.termux.terminal.TerminalRenderingInstrumentation` exercises
actual Android font faces and Bitmap rendering across cell sizes, including
pixel-exact scrolling and clipping of text, selection and graphics, then writes
`cache/terminal-rendering.png` for visual inspection. Run it with Android's
`am instrument -w` when Desktop is closed and no terminal sessions need retaining:
instrumentation restarts the application process. Host tests validate the bundled
font resources and the View-to-PTY resize contract.

## Static Images

Both consoles render static Sixel and inline Kitty graphics using the local Java
emulator and Android Bitmap/Canvas, without another library or a Termux renderer.
Images belong to the retained terminal session, not the window. Reattaching a
retained PTY does not re-decode them; a new tmux client relies on tmux/app redraw.
Direct placements track buffer scrolling and reflow;
alternate-screen images are cleared on alternate-screen entry, independently of
the main screen. Ordinary text erases Sixel cells but not Kitty placements;
clear-screen, graphics deletion and reset have their protocol-specific effects.

- **Sixel:** raster attributes, RGB/HLS palettes, repeat runs, transparent
  backgrounds, scrolling and cursor-right modes. Raster pixels are square;
  non-square pixel-aspect emulation and shared palettes are not implemented.
- **Kitty:** inline RGB/RGBA/PNG, base64 chunks, optional zlib compression,
  queries, named images/placements, source crop, cell sizing/offsets, z-order,
  cursor policy and deletion by image/placement ID or all visible placements.
  Unicode placeholder placements (`U=1`) support multiplexers. Animation,
  relative placements, image-number addressing, and file/shared-memory
  transports are not implemented. Unsupported commands return a protocol error
  when a response is requested; payloads never execute commands or open files.

The session retains one Android bitmap per image. Its raster budget is one quarter
of the application heap limit, clamped to 64-128 MiB, with at most 128 images and
256 placements/fragments. A raster is limited to 4096 pixels per side and 16 million
pixels (64 MiB RGBA). Incoming Kitty transfers/decompressed data are bounded to
64 MiB; Sixel input is bounded to 16 MiB and 32 million pixel writes. Decoding needs
temporary buffers in addition to retained raster storage. Quota eviction prefers
unplaced images, then the oldest image, removing its placements at the same time.
Rendering has no animation timer, disk cache or background image worker. Text-only
terminals keep their single drawing pass; graphics are drawn on normal invalidation.

Tap/click an image or long-press it with a finger to open **Save in Files**, **Open**
or **Share**. When a terminal application owns mouse reporting, ordinary clicks
remain terminal input; Ctrl+click or touch long-press opens the image actions.
Hit testing follows buffer clips, scrollback and Kitty placeholder cells, including
tmux redraws. The selected immutable raster remains valid if its program subsequently
clears the screen. Exports contain the original PNG pixels, not a screenshot or a
scaled/cropped placement.

PNG encoding runs only after an explicit action, off the UI thread.
`GeneratedContentProvider` exposes app-private temporary exports through read-only
URI grants. Files expire after 24 hours; publication cleans expired entries and
limits storage to 256 MiB/128 files, without evicting a still-valid export. Android
may reclaim the cache earlier. **Open** and **Share** use the shared Android content
gateway. **Save in Files** opens a new Files window; navigate to the destination
and choose **Save here**. Files uses its existing transactional import and collision
naming, with no overwrite and no Desktop prerequisite. Sharing/opening the PNG
does not require privileged file access; saving through Files uses its usual backend.

`TERM` stays `xterm-256color`. Sixel capability/geometry queries and Kitty graphics
queries describe support; clients may also select a format explicitly. For example,
with `chafa` installed in Termux:

```sh
chafa --probe off --animate off -f sixels -s 40x12 image.png
chafa --probe off --animate off -f kitty -s 40x12 image.png
```

Termux packages can produce these formats even if Termux's own terminal view
cannot display them. The terminal receiving the PTY output is MagicDesk.

### Images Inside tmux

A tmux build with Sixel enabled can handle Sixel natively. For Kitty Unicode
placeholders, enable tmux passthrough and use a client that emits placeholders:

```sh
tmux set -g allow-passthrough on
chafa --probe off --animate off --passthrough tmux -f kitty -s 30x10 image.png
```

MagicDesk reads the unwrapped graphics protocol, while tmux moves/repaints its
placeholder cells as text. Image prototypes survive clear-screen redraws and
window switches. If a pane becomes narrower than an already printed image row,
tmux can wrap its cells into strips. The producing application must redraw the
preview for its new dimensions; MagicDesk does not guess or rearrange tmux cells.
Native Sixel retention across pane changes depends on tmux's implementation.

The rendering instrumentation also exercises decoded RGB, alpha, PNG, Sixel,
layer order, clearing, Unicode placeholders and view-independent raster reuse,
writing `cache/terminal-graphics.png`. Host tests cover fragmented/malformed
streams, quotas, scrolling, reflow, buffer isolation and placement lifetimes.
Graphics are not included in text transcript/selection output.

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
prompt includes the current path, distinguishes `$` from `#` and retains a nonzero
exit status. Paths are inserted as text, with terminal control characters removed.
The line editor's native nonprinting delimiters exclude OSC from prompt width.
OSC titles update the Android task and session label, not a separate line above the terminal.
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

### Peer Output

`terminal.write` and `terminal.send_key` send **input** to a program. In contrast,
`terminal.emit` writes **output** into the slave PTY of a retained shell or
Termux terminal. It also works after detaching an ordinary window. The bytes
pass through the existing PTY reader, emulator and renderer, including ANSI,
OSC, Sixel and Kitty graphics. There is no renderer injection or overlay.

For output inside a running tmux pane, use `tmux.panes`, then `tmux.emit` with
the returned opaque `target`. An optional `sessionId` filters pane discovery;
without it, discovery lists live writable panes across the selected Termux
package's default tmux server. Detached tmux sessions need no MagicDesk window.
Window/pane active flags help the caller select a destination, but emission
never silently changes the selection to the currently active pane. Dead panes
have no live output target. Custom tmux sockets are not exposed by this API.

```sh
magicdesk terminal.emit --terminalId terminal-1 --text 'Peer output'
magicdesk tmux.panes
magicdesk tmux.emit --target 'TARGET_FROM_PANES' --text 'Output inside the pane'
magicdesk tmux.emit --args - < request.json
```

Both emit operations accept exactly one of UTF-8 `text` or raw `dataBase64`,
with 1 to 65,536 decoded bytes per request. For larger graphics streams, split
the bytes into chunks, preserve their order and await each receipt. `bytesWritten`
acknowledges slave-side writes, **not** parsing, visibility or display retention.
Terminal line-discipline output processing still applies, just as for the
application's own writes. Emission uses the PTY owner's existing execution
identity; Termux output never falls back to shell/root access.

The producer shares the application's terminal state. Programs can repaint or
erase emitted content; concurrent writers can interleave control sequences;
terminal queries/replies go to the running program rather than the caller.
Avoid reply-requesting sequences unless that program expects them. tmux graphics
still depend on its build and configuration, as described above. Sending through
`terminal.emit` to a tmux client PTY targets its outer terminal, not its pane:
use `tmux.emit` for the peer-writer path before tmux parsing.

Targets include the process birth identity and controlling PTY; the native
helper verifies them before writing. A reused PID, recycled PTY or closed pane
cannot redirect an old target into a new program. Writes have a bounded
backpressure deadline and report any partial byte count with an error. Never
automatically retry a partial write or `OUTCOME_UNKNOWN`: missing acknowledgement
does not undo bytes or guarantee cancellation. There is no replay queue.
All three commands use the existing `shell` automation grant and require neither
Desktop nor a visible window. CLI and MCP use the same catalog and executor.
Ordinary terminal IDs are unique across MagicDesk process restarts, so a stale
input/output request cannot select a newly created console with a reused number.

### Command Stdout

`console.execute` can route a command's stdout directly to a peer terminal.
The source is the existing non-PTY Android shell, with its retained environment
and working directory. It does not add prompts, echo or PTY newline conversion.
No temporary output file, Termux installation or external encoder is needed for
the shell-to-shell path. An arbitrary external utility remains responsible for
producing the content (for example, rendering a formula into PNG).

```json
{
  "sessionId": "SOURCE_CONSOLE",
  "command": "render-formula --format png",
  "stdout": {
    "terminalId": "DESTINATION_TERMINAL",
    "mimeType": "image/png"
  }
}
```

Use `tmuxTarget` instead of `terminalId` to select a target from `tmux.panes`.
Raw stdout needs no MIME type and passes through unchanged, including ANSI or
already encoded graphics. PNG uses bounded
[Kitty chunks](https://sw.kovidgoyal.net/kitty/graphics-protocol/#transferring-pixel-data),
without decoding or buffering the complete image. PNG to a tmux pane uses its
DCS passthrough protocol and requires `allow-passthrough`; MagicDesk does not
change that server setting. Plain Kitty placement may be erased by tmux redraws;
it is not a persistent placeholder-based image. Raw preformatted streams remain
under the producing application's control. PNG responses are suppressed so
they do not become unexpected keyboard input to the recipient program.

Without `stdout`, the result contains the usual bounded combined `output`.
With it, there is no stdout copy in the result. Instead it contains:

```json
{
  "exitCode": 0,
  "stderr": "",
  "stderrTruncated": false,
  "stdoutDelivery": {
    "completed": true,
    "sourceBytes": 12345,
    "bytesWritten": 16560,
    "writeUnconfirmed": false,
    "errno": 0
  }
}
```

The example byte counts differ because `bytesWritten` includes presentation
framing. `completed` acknowledges all PTY writes, not successful rendering.
A nonzero command exit status is independent of output delivery. If execution
does not reach both channel boundaries, `exitCode` is unknown (`null`). stderr
on failure contains only diagnostics observed before interruption.

Backpressure propagates through bounded buffers. A failed recipient write stops
source execution and resets that command shell; closing the console does the
same. Recipient identity is rechecked on each write, not by a background watcher.
Cancelling while a write awaits its acknowledgement cannot retract that block.
Previously confirmed bytes remain counted; `writeUnconfirmed` marks a block
whose final byte count could not be confirmed. Never replay the command or its
output automatically. Do not run background producers beyond the command's
completion boundary; keep the pipeline in the foreground or use its own console.

The generated CLI exposes this same object as `--stdout '{...}'` or through
`--args`; it has no separate streaming implementation or MIME-specific tool.
