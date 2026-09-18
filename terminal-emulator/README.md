# Terminal Emulator

MagicDesk's terminal parser and screen model, based on
[termux/termux-app v0.118.3](https://github.com/termux/termux-app/tree/v0.118.3/terminal-emulator),
commit `5b657c6adf4304e5198951ce815fe0205dcac29c`.

## Scope

The module owns escape-sequence parsing, screen/scrollback buffers, cell styles,
Unicode widths, key encoding and terminal graphics placement. MagicDesk maintains
it locally, including OSC 8/9/133, Sixel and Kitty support.

PTY processes, Android windows, rendering, clipboard policy and user actions
belong to the application. The module has no JNI library or dependency on an
installed Termux app. See [Terminal integration](../docs/terminal-integration.md)
for protocols, limits and the host interfaces.

## Verification

```sh
./gradlew :terminal-emulator:testDebugUnitTest :terminal-emulator:lintDebug
```

The upstream regression suite and MagicDesk's extensions run in development
verification and release CI. Preserve that coverage when changing parser or
buffer semantics.

## License

The upstream module uses the Apache-2.0 exception in Termux's
[LICENSE.md](https://github.com/termux/termux-app/blob/v0.118.3/LICENSE.md).
Its [license](../third_party/termux-terminal-emulator/LICENSE) and
[attribution](../third_party/termux-terminal-emulator/NOTICE) are packaged in the
APK. Preserve source copyright notices.
