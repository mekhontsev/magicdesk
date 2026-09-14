# Terminal Emulator

MagicDesk's local terminal-emulation module is based on
[termux/termux-app v0.118.3](https://github.com/termux/termux-app/tree/v0.118.3/terminal-emulator),
commit `5b657c6adf4304e5198951ce815fe0205dcac29c`.

## Ownership

This module owns escape-sequence parsing, screen and scrollback buffers, cell
styles, Unicode widths, and terminal key encoding. It does not own processes,
PTY transports, Android windows, Desktop sessions, clipboard policy, or input
routing. MagicDesk supplies those through its existing session and UI layers.
There is no JNI library or dependency on an installed Termux application.

The upstream regression suite is retained. MagicDesk extends the parser and
cell buffers with OSC 8 hyperlinks, OSC 9 notification/progress events and OSC
133 shell command boundaries. Link attributes follow cells through editing and
reflow; bounded command records reference buffer-owned markers rather than a
second output transcript. OSC 0/2 titles use the existing title callback.
An optional `ScrollListener` reports explicit vertical region edits before cell
mutation, observes cell writes separately from scroll transport/blanking, and
invalidates transient presentation on screen resets. Animation and
departing-row drawing remain in the application; the parser never delays output
or infers scrolling from arbitrary repaints.
Unknown SGR codes use locale-independent diagnostic formatting.
Static Sixel and inline Kitty decoders publish immutable `TerminalImage` rasters
through a host-supplied factory. `TerminalGraphics` owns the raster quota and
buffer-scoped placements. Unicode placeholder coordinates and placement colors
travel with text cells through tmux repainting, copying and reflow. Android PNG
decoding, native Bitmap storage and Canvas drawing belong to the application,
not this module. Parser tests use a Java-array factory and require no codec library.
The `TerminalSessionClient` callback interface excludes callbacks referencing the
upstream process-owning `TerminalSession`. The unused `TerminalSession`, `JNI`,
`ByteQueue`, and `ByteQueueTest` are not part of the module. Its Gradle build uses
MagicDesk's Android toolchain and API 34 minimum, with the existing JUnit version.
Host tests retain upstream's default-value Android stubs for logging; Android
clipboard Base64 behavior still requires device verification.

`ConsoleTerminalSession` supplies terminal output callbacks;
`MagicDeskTerminalRenderer` and `ConsoleTerminalView` remain in the application.
Changes to parsing or buffer semantics belong here, not in a second parser
around the PTY byte stream. Changes to this module's runtime sources contribute
to the APK build identity.

`TerminalFrame` exposes a viewport-bounded synchronous read for renderers:
palette/cursor and immutable image-placement metadata are frozen, cell rows are
borrowed until the next edit, and rasters are shared. Retained presentation cells
must be explicit snapshots. Window geometry/selection and scroll reconciliation
live in the app's `TerminalViewport` and `TerminalScrollRegion`; the emulator does
not acquire a Canvas, animation clock, Android window or PTY owner.

Opening links, posting notifications and shell startup hooks belong to the app.
The module cannot execute an OSC payload or launch an Activity. See
[terminal integration](../docs/terminal-integration.md) for supported protocols,
UI behavior, limits and shell-specific capabilities.

## Verification

```sh
./gradlew :terminal-emulator:testDebugUnitTest :terminal-emulator:lintDebug
./gradlew verifyDevelopment
```

Development verification and release CI include the emulator tests. Preserve
the upstream regression suite when changing buffer or protocol behavior.

## License

The upstream repository identifies `terminal-emulator` under its Apache-2.0
exception in [LICENSE.md](https://github.com/termux/termux-app/blob/v0.118.3/LICENSE.md).
The [license](../third_party/termux-terminal-emulator/LICENSE) and
[attribution](../third_party/termux-terminal-emulator/NOTICE) are also packaged
in the MagicDesk APK. Preserve source copyright notices when updating the module.
