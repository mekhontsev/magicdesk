# Third-Party Notices

## Termux terminal-emulator

MagicDesk uses the `terminal-emulator` module from Termux app version
`v0.118.3` to parse terminal byte streams and maintain terminal screen state.
MagicDesk owns the PTY process lifecycle, Binder transport, input integration,
selection, and rendering; it does not package Termux's native `libtermux.so`.

- Upstream: https://github.com/termux/termux-app/tree/v0.118.3/terminal-emulator
- Copyright: The Termux Authors
- License: Apache License 2.0; see
  [`third_party/termux-terminal-emulator/LICENSE`](third_party/termux-terminal-emulator/LICENSE)
