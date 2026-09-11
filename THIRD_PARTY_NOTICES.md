# Third-Party Notices

## Lucide icons

MagicDesk controls use Lucide's `sliders-horizontal`, `settings`, `volume-2`,
`volume-x`, `plus`, `minus`, `cast`, `log-out`, and `list` icons, converted
to Android VectorDrawables.
The applicable ISC and Feather MIT notices are included in the APK:
[`lucide.txt`](app/src/main/assets/licenses/lucide.txt).

- Upstream: https://github.com/lucide-icons/lucide

## Termux terminal-emulator

MagicDesk uses the `terminal-emulator` module from Termux app version
`v0.118.3` to parse terminal byte streams and maintain terminal screen state.
MagicDesk owns the PTY process lifecycle, Binder transport, input integration,
selection, and rendering; it does not package Termux's native `libtermux.so`.

- Upstream: https://github.com/termux/termux-app/tree/v0.118.3/terminal-emulator
- Copyright: The Termux Authors
- License: Apache License 2.0; see
  [`third_party/termux-terminal-emulator/LICENSE`](third_party/termux-terminal-emulator/LICENSE)

## JetBrains Mono Nerd Font Mono

Console bundles the unmodified regular, bold, italic and bold-italic faces of
JetBrains Mono NL Nerd Font Mono, from Nerd Fonts `v3.5.1` (JetBrains Mono `2.304`).
The font is included in the APK; it does not require downloading fonts or installing Termux.

- Upstream: https://github.com/ryanoasis/nerd-fonts/releases/tag/v3.5.1
- Font license: SIL Open Font License 1.1. The upstream font license, Nerd Fonts
  license and included icon-set attribution are packaged under
  [`third_party/jetbrains-mono-nerd-font`](third_party/jetbrains-mono-nerd-font).

## Unicode Character Data

The terminal's box-drawing topology follows the character names in Unicode 17.0
`UnicodeData.txt`. The applicable Unicode License v3 is included in the APK:
[`third_party/unicode/LICENSE.txt`](third_party/unicode/LICENSE.txt).
