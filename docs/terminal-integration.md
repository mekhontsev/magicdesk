# Terminal Integration

Console and Termux Console use the same local terminal emulator, retained PTY
session model, UI and automation API. All features here work without Desktop.
Terminal output is untrusted data, not authorization to execute an action.

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
actual Android font faces and Bitmap rendering across cell sizes, then writes
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
