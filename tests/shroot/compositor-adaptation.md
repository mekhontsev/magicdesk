# Testing Distribution Compositor Packages

The MagicDesk graphics recipe builds Mesa and, where needed, a compositor.
These paused experiments ask whether a stock distribution compositor can be
used with a small locally built addition instead. They operate on private
copies: nothing here replaces an installed library or changes an installer
default. This is not a Shroot runtime feature.

## What Has Been Tested

On NX809J (Android 16, AArch64), with the recipe's Turnip/Mesa profile:

| Recipe environment | Stock component | Passing checks | Artifacts under `build/` |
| --- | --- | --- | --- |
| SteamOS | KWin 6.2.5 | Qt window, animated EGL, clean exit | `kwin-binary-module/steamos` |
| Debian | KWin 6.3.6 | Qt window, animated EGL, original BTI/PAC properties retained | `kwin-binary-module/debian-turnip` |
| NixOS | KWin 6.6.6 | Qt window, animated Vulkan, clean exit | `kwin-binary-module/nixos-recipe` |
| Ubuntu, Manjaro | Weston 13.0.0 | Stock popup/focus failure reproduced; guarded popup visible, clean exit | `weston-child-guard/ubuntu-fuse`, `weston-child-guard/manjaro-turnip` |

## Reproduction

Use the [integration test setup](README.md). KWin requires matching development
headers and toolchain. The [allocator probe](test_kwin_binary_adaptation.py)
tests the pinned SteamOS build; the [module driver](test_kwin_binary_module.py)
builds selected fragments and a Qt platform plugin. The
[desktop driver](test_kwin_binary_desktop.py) checks the actual selected module,
renderer and changing pixels on an owned virtual display. For example:

```sh
export SHROOT_TEST_EXECUTOR=shell
python tests/shroot/test_kwin_binary_module.py --name steamos --adapt --keep
python tests/shroot/test_kwin_binary_desktop.py --name steamos --directory WORK --case qpa
python tests/shroot/test_kwin_binary_desktop.py --name steamos --directory WORK --case egl --cleanup
```

Use the retained directory printed by the module driver for `WORK`. On NixOS
use the Vulkan case instead of EGL. No other launch of the tested environment
or competing display lease should be active.

The Weston interposer uses public APIs and needs a C compiler and `libdl`, not
private structure offsets. The [popup driver](test_weston_child_desktop.py)
compares stock (`--stock`) and guarded launches. The [guard probe](test_weston_child_guard.py)
checks the stock library without a desktop. Both use [local guest fixtures](fixtures/);
only the shared execution harness comes from Shroot.

## Limits

The KWin prototype admits exact builds. Locating a symbol does not establish
C++ layout or behavioral compatibility. To change the recipe, an analyzer
would need to recognize supported layouts across package updates, reject
ambiguous inputs and keep the relocated dispatch table read-only. A full
desktop, screencasting and pointer lock/focus still need their own tests.
Weston's popup control does not qualify a whole desktop or other releases.
