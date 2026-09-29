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

## D-Bus Client Library

The Linux appearance helper statically links libdbus 1.16.2 from hash-verified
upstream sources. A build-only patch omits test fixtures in the client-only
configuration; the library sources are unchanged. The APK includes neither
`dbus-daemon` nor the D-Bus command-line tools. Copyright and license texts are packaged under
`assets/licenses/dbus`.

- Source: https://dbus.freedesktop.org/releases/dbus/dbus-1.16.2.tar.xz
- License: AFL-2.1 OR GPL-2.0-or-later; MagicDesk uses the GPL option.
- Build and adapter: [`native/linux-settings`](native/linux-settings).

## Embedded Termux:X11

MagicDesk embeds the native X server and protocol/graphics adapters from its
[MagicDesk X11 fork](https://github.com/mekhontsev/magicdesk-x11), pinned by
the `vendor/magicdesk-x11` submodule. It does not require the standalone
Termux:X11 Android application. Java, Binder and JNI integration is owned by
MagicDesk's local `x11-runtime` module. X11 and Wayland use MagicDesk's shared
`hosted-runtime` graphics backend and Android frame presenter.

- Upstream: https://github.com/termux/termux-x11
- License: GNU GPL version 3; see
  [`vendor/magicdesk-x11/LICENSE`](vendor/magicdesk-x11/LICENSE).
- X server and graphics dependencies retain their individual copyright and
  license notices. The library packages their `COPYING` and `LICENSE` files
  under `assets/licenses/magicdesk-x11` in the APK.
- Corresponding source archives include the fork, recursively pinned native
  dependencies, modifications and build scripts; see [licensing](docs/licensing.md).

## Guest Runtime SQLite

The experimental native guest filesystem service statically links unmodified
SQLite 3.50.4. The amalgamation archive and SHA-256 are pinned in
[`native/guest-runtime/CMakeLists.txt`](native/guest-runtime/CMakeLists.txt).
SQLite is public domain; its dedication is packaged under
`assets/licenses/guest-runtime/sqlite.txt`. It is not linked into the bootstrap
or syscall handler. Debian fixture packages are development inputs and are not
included in the APK.

## Embedded Wayland

The `wayland-runtime` module builds pinned upstream wlroots, Wayland,
wayland-protocols, Pixman, libdrm, libxkbcommon and libffi. Source archive URLs
and SHA-256 hashes are recorded in
[`native-deps/CMakeLists.txt`](wayland-runtime/native-deps/CMakeLists.txt).
The build preserves their license notices under `assets/licenses/wayland` in
the APK, including libdrm's per-file notices. The local Android shared-memory
adapter is applied as a checked source patch; wlroots is not a fork.

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
