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

- display drivers describe phone, wired, wireless, and simulated activation,
  capabilities, and display-removal behavior;
- platform drivers describe firmware capabilities and workarounds;
- session orchestration composes the two for one active target and exclusively
  owns close and mirror transitions.

SoC-specific display services are optional operation backends, not firmware
platforms. For example, a Qualcomm `IDisplayConfig` backend is discovered from
its Binder service and may augment output-mode diagnostics or control on any
compatible device. Its absence must be inert, and Nubia, generic Android, and
future firmware drivers must not be multiplied into SoC-specific variants.

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

Task recovery follows the same mechanism/policy split. Parsing and normalizing
the SystemUI WMShell desktop repository is shared Android machinery. Whether a
firmware needs that recovery, stale phone-side freeform cleanup, or a caption
refresh is selected by `PlatformWindowingDriver`. Phone input panels and Home
reconciliation remain in `PlatformPhoneUiDriver`.

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

Session ownership is now explicit through the runtime service, immutable
session registry, display-scoped task state, and `DesktopTaskRuntime` contract.
No desktop task operation discovers a mutable active controller through a
process-global lookup.

Parked desktop tasks use the same ownership boundary. Their records, pending
target, and restore operation belong to `RuntimeDesktopTaskCoordinator` through
the `DesktopTaskParkingRuntime` contract. Closing and reopening a desktop can
preserve live tasks while the runtime service remains active, but a full exit
or runtime teardown invalidates queued parking work and clears the records.

Window launches have one operation lifecycle. UI status and recovery are
owned by `AppTaskController`, fresh launch/reuse selection by
`WindowedAppLauncher`, and existing-task normalization by
`ExistingTaskController`. One transient launch lease owns startup windowing
and touchpad preservation across those layers.

The shared fullscreen, focus, self-test cleanup, and pointer-recovery paths no
longer apply Nubia behavior unconditionally. Existing platform contracts select
caption repair, focus synchronization, phone task recovery, and the pointer
driver. Source-isolation tests reject fully qualified implementation references
and known vendor runtime identifiers outside platform adapters.

Self-test window stages now share an event-driven task-stack invariant guard.
The shell observer records bounded snapshots only on Android task callbacks and
stage boundaries; a platform-neutral analyzer detects transient display or
windowing-mode detours and visibility gaps that final-state assertions miss.
Cross-display fullscreen return has one shared shell primitive: it hides the
task and commits its target mode on the source display, moves the hidden root,
then reveals it on the destination. The guard accepts only that hidden source
preparation and the hidden default-mode state of a newly created task before
its requested launch mode is committed. A visible mode detour or any freeform
task on display 0 remains a failure.

True-fullscreen Alt+Tab uses a shared Android task-display-area mechanism, not
a platform workaround. The ownership invariant and the failed alternatives are
recorded in [Fullscreen Alt+Tab](fullscreen-alt-tab.md). The self-test contract
also covers releasing and closing individual tasks, survivor input focus, and
abrupt simulated-display removal; it must pass before changing task-focus or
task-area lifecycle operations. Closing the active member first hands focus and
visibility to the top surviving member, waits for the observed task state, and
only then removes the now-background task. This prevents a wallpaper-only
frame without relying on delayed repair.

The session transition path now has one owner. Display drivers cannot close a
session or call back into `ConsoleModeSwitcher`; the coordinator receives the
selected projection and feature contracts and performs direct-session close or
platform-owned mirror teardown on the same serialized queue. This removes the
former facade -> driver -> facade loop.

Desktop UI commands follow the same ownership rule. Shortcuts and session
orchestration enter through `MagicDeskRuntime`; only the runtime backend talks
to the live-host gateway. Platform adapters cannot read that gateway. For
example, the Nubia phone-display guard receives its desktop display ID from
orchestration and reports a generic platform-state change instead of reading
session state or refreshing desktop controls itself.

Architecture tests preserve these boundaries: platform implementations may
not reference `DesktopRuntimeBridge`, console commands may not bypass the
runtime, and the session coordinator may not re-enter its facade or resolve a
new platform dependency.

Each step must leave the branch buildable and independently reviewable.
