# Documentation

Choose a guide by task. User guides describe the current product; architecture
references describe ownership and invariants; fixture guides describe how to
verify them. The [validation matrix](testing-backlog.md) contains outstanding
coverage, not a chronological development log.

## Use MagicDesk

| Task | Guide |
| --- | --- |
| Install, configure and launch applications | [Getting started](getting-started.md) |
| Files, content, task management and independent tools | [Workstation tools](workstation-tools.md) |
| Consoles, retained sessions and tmux | [Terminal integration](terminal-integration.md) |
| Install Linux, run applications and containers | [Shroot](shroot.md) |
| Compare Shroot, PRoot and native execution | [Benchmarks and measurement scope](shroot-benchmarks.md) |
| Launch recipes and shortcuts | [Desktop entries](desktop-entries.md) |
| Themes, panels, wallpapers and customization | [Native appearance](appearance.md) |
| Diagnose firmware behavior and report a problem | [Compatibility](compatibility.md) |
| Community support and test builds | [Telegram support](telegram-support.md) |

## Integrate And Automate

| Interface | Guide |
| --- | --- |
| MCP, CLI, permissions, observations and self-tests | [Automation](automation.md) |
| Android applications invoking shell/Termux commands | [External command API](run-command.md) |
| X11 applications and desktops | [X11](x11.md) |
| Wayland applications, desktops and panels | [Wayland](wayland.md) |
| Shared rendering and GPU boundaries | [Graphics](graphics.md) |
| PipeWire/Pulse clients and Android playback | [Audio](audio.md) |
| Android gamepads in Shroot | [Gamepads](gamepads.md) |
| Linux theme/settings propagation | [Linux appearance](linux-appearance.md) |
| Native and Linux shell surfaces | [Shell layout](shell-layout.md) |

## Develop And Review

Start with [contributing](../CONTRIBUTING.md) and the
[architecture overview](architecture.md). Detailed ownership references:

- [Guardrails](architecture-guardrails.md)
- [Application and shell UI](architecture-applications.md)
- [Automation and diagnostics](architecture-automation.md)
- [Framework and platform adapters](architecture-framework.md)
- [Shared services](architecture-services.md)
- [Displays and sessions](architecture-displays.md)
- [Windows and transitions](architecture-windows.md)

The focused contracts remain authoritative for
[Android API levels](runtime-api-levels.md), [privilege boundaries](privilege-modes.md),
[fullscreen/focus](fullscreen-transitions.md), [window layout](window-layout.md),
and [Shroot native internals](../native/shroot/README.md).
Use [Shroot fixtures](../native/guest-exec-lab/README.md) for Linux checks and
the [device validation matrix](testing-backlog.md) for Android coverage and the
[distribution matrix](../native/guest-exec-lab/validation-matrix.md) for per-OS
Linux acceptance. Its [evidence ledger](../native/guest-exec-lab/distribution-evidence.md)
keeps exact application receipts separate from the status tables. Desktop
self-tests do not replace protocol, installer or guest-runtime workflows.

## Evidence And Project Policy

- [Device-porting workflow](ai-assisted-device-porting.md)
- [Paused graphics-adaptation research](../native/guest-exec-lab/README.md#graphics-adaptation-paused)
- [Nubia vendor API audit](nubia-vendor-audit.md)
- [Chrome custom-caption investigation](chrome-custom-caption-investigation.md)
- [Independent XR resolution add-on](xr-resolution-fix.md)
- [Artwork](artwork.md), [licensing](licensing.md),
  [third-party notices](../THIRD_PARTY_NOTICES.md), [security](../SECURITY.md)

Version-specific notes live in `docs/releases/VERSION.md`. Published notes retain
their historical scope; drafts are explicitly marked as pending acceptance.
Neither replaces the current feature or compatibility contract.

Keep reproducible fixture instructions, current evidence and the reasons for
safety constraints in their owning guides. Fixed-bug stories, retired mechanisms
and step-by-step experiments belong in Git or run artifacts, not maintained
guides. Remove resolved items from backlogs instead of appending a fix history.
