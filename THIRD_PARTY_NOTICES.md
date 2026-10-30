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

## Android Shell

MagicDesk's Android shell commands use upstream mksh R59c, built from hash-verified,
unmodified sources with persistent history enabled. The executable is packaged
in the APK; it does not replace Android's system shell or Termux/guest shells.
Copyright and license notices, including embedded Unicode notices, are collected
from the source files into `assets/licenses/mksh/NOTICE.txt`.

- Upstream: https://github.com/mirabilos/mksh-cvs2git
- Original release archive mirror: https://deb.debian.org/debian/pool/main/m/mksh/mksh_59c.orig.tar.gz
- Build adapter: [`native/android-shell`](native/android-shell).

## D-Bus Client Library

The shared Linux settings/automation helper statically links libdbus 1.16.2
for appearance, AT-SPI2 and D-Bus operations, from hash-verified upstream sources.
A build-only patch omits test fixtures in the client-only
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
SQLite 3.51.3. The amalgamation archive and SHA-256 are pinned in
[`native/shroot/CMakeLists.txt`](native/shroot/CMakeLists.txt).
SQLite is public domain; its dedication is packaged under
`assets/licenses/guest-runtime/sqlite.txt`. It is not linked into the bootstrap
or syscall handler. Debian fixture packages are development inputs and are not
included in the APK.

## Guest Packet Stack

Shroot statically links lwIP 2.2.1 under its BSD-style license. The source
archive at commit `77dcd25a72509eb83f72b033d219b1d40cd8eb95` and its SHA-256
are pinned in [`native/shroot/lwip/CMakeLists.txt`](native/shroot/lwip/CMakeLists.txt).
The adjacent `context.patch` moves mutable protocol state into independent
namespace contexts. Upstream's copyright and license text is packaged at
`assets/licenses/guest-images/lwip.txt`. This native packet stack is not loaded
into ART or the freestanding syscall bootstrap.

## Guest Image Import

The native guest image utility links libarchive 3.8.9 (BSD-style licenses),
zlib 1.3.2 (zlib license), Zstandard 1.5.7 (BSD license), and the SHA-256 component
of Mbed TLS 3.6.7 (Apache-2.0). Hash-verified upstream source archives are pinned in
[`native/shroot/image-dependencies.cmake`](native/shroot/image-dependencies.cmake).
Their license texts are packaged under `assets/licenses/guest-images`. Archive,
compression and digest code is not linked into the guest syscall adapter.

## Embedded Wayland

The `wayland-runtime` module builds pinned upstream wlroots, Wayland,
wayland-protocols, Pixman, libdrm, libxkbcommon and libffi. Source archive URLs
and SHA-256 hashes are recorded in
[`native-deps/CMakeLists.txt`](wayland-runtime/native-deps/CMakeLists.txt).
The build preserves their license notices under `assets/licenses/wayland` in
the APK, including libdrm's per-file notices. The local Android shared-memory
adapter is applied as a checked source patch; wlroots is not a fork.

## PipeWire Audio

`audio-runtime` builds PipeWire 1.4.9 from a hash-verified upstream archive.
Bionic compatibility and executor-owned FD admission are local source patches.
The APK contains the native and Pulse protocol modules, audio adapters and SPA
support/converters, not PulseAudio, WirePlumber, ALSA drivers or a D-Bus daemon.
PipeWire's MIT license notices are packaged under `assets/licenses/pipewire`.
Source URL, checksum and patches are in
[`audio-runtime/native-deps`](audio-runtime/native-deps).

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
