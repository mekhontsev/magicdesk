# MagicDesk 1.8 Architecture Work

This document defines the migration rules for the `next/1.8` branch. The
current production structure remains documented in [architecture.md](architecture.md).

## Scope

MagicDesk 1.8 will make the existing desktop runtime easier to extend across
Android versions, display transports, and firmware families without replacing
working behavior. The branch is not a rewrite.

The migration follows these rules:

1. Keep one APK, one codebase, and runtime platform selection.
2. Keep Android tasks, WMShell, and native captions as the windowing authority.
3. Keep display transport, firmware integration, and shell execution as
   separate boundaries.
4. Move behavior only after its current contract is covered by tests.
5. Add an interface only for an external boundary or multiple real
   implementations.
6. Prefer event callbacks and verified state over polling and guessed delays.
7. Do not copy third-party implementation code. External projects are used to
   discover Android contracts and compare design decisions.

Stability fixes continue on `main` for 1.7 and are merged or cherry-picked into
`next/1.8`. Architecture-only changes stay on `next/1.8` until the branch is
ready to replace `main`.

## Dependency Direction

The intended direction is:

```text
activities and desktop UI
        |
controllers and session orchestration
        |
task, display, input, storage, and capture contracts
        |
Android shell adapters + selected platform driver
```

`PlatformDrivers` is the sole composition point allowed to construct a
firmware implementation. Shared code may depend on platform contracts, but not
on classes or runtime identifiers from `platform/nubia` or future vendor
packages. A platform driver may refine Android behavior; it must not own the
desktop UI or duplicate session state.

Display drivers remain independent from platform drivers:

- display drivers describe phone, wired, wireless, and simulated lifecycle;
- platform drivers describe firmware capabilities and workarounds;
- session orchestration composes the two for one active target.

## External Project Review

The following projects are useful references, not architecture templates:

| Project | Useful evidence | Decision for MagicDesk |
| --- | --- | --- |
| [Extend](https://github.com/jqssun/android-display-extend) | Input-device association by descriptor, port, and display unique ID; display mode and IME policy APIs | MagicDesk already owns equivalent routing, hot-plug refresh, IME policy, and cleanup. Keep the existing typed sessions; do not adopt its process-global mutable state or dumpsys-first fallbacks. |
| [Smart Dock](https://github.com/axel358/smartdock) | Mature dock interactions and broad launcher customization | Use it to evaluate user workflows. Do not make an Accessibility overlay or one large service the task/window authority. |
| [DroidOS](https://github.com/Katsuyamaki/DroidOS) | Tiling workflows, explicit focus operations, keyboard-aware margins, and AR input ideas | Evaluate individual workflows against native WMShell behavior. Do not copy its two-app coupling or broad shell service. |
| Taskbar and SecondScreen | Established launcher and per-display profile workflows | Preserve MagicDesk's display profiles and automation-friendly boundaries without recreating their launcher stack. |
| AOSP Desktop Windowing | Native task, caption, and display-area contracts | Remains the primary source of truth whenever firmware exposes the required path. |

The first review confirmed that a second capability registry would duplicate
`PlatformDriver`, `PlatformFeatures`, `DesktopDisplayDriver`, and diagnostics.
Capabilities should stay close to the operation that proves or consumes them.

## Migration Order

1. Enforce platform-source isolation in tests.
2. Consolidate duplicated wired/wireless session policy without changing
   transport behavior.
3. Inventory firmware-specific decisions that still live in shared task and
   session classes; move them behind existing platform contracts one at a time.
4. Make session ownership explicit where process-global lookups currently make
   teardown or recovery ambiguous.
5. Keep self-tests running on phone, simulated, wired, and wireless drivers as
   the behavioral contract for each migration.

Each step must leave the branch buildable and independently reviewable.
