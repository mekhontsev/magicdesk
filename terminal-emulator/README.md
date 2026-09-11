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

The core parsing and buffer algorithms and their tests retain the upstream
implementation. Unknown SGR codes use locale-independent diagnostic formatting.
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
