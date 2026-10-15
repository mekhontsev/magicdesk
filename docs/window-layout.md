# Application Window Layout

`WindowLayoutPolicy` computes application geometry without Android APIs, Binder,
focus operations or task-area ownership. Its immutable workspace contains the
display work area, ordered task identities, observed bounds, layout eligibility,
concealment, floating restore bounds and observed focused task. Coordinates are
logical display pixels. `ShellLayout` remains the owner of panel reservations
and the work area supplied to this policy.

## Planning And Execution

Requests distinguish workspace observation, reveal before activation, explicit
placement and layout release. A plan contains unique task placements and may
carry an immutable policy continuation. Planning must not mutate policy state:
the queue can recompute a plan from a fresher snapshot. A continuation becomes
current only after successful submission, never after cancellation or failure.

`WindowLayoutSession` owns the current policy, observation deduplication and
continuation acknowledgement without Android dependencies. The workspace
dispatcher serializes mutations; input adapters can read its published immutable
policy snapshot to check whether tiling is enabled. Layout release uses the core floating policy,
not the selected policy, so restoration does not depend on that policy's planner.
Replacing a policy clears its consumed observation; a continuation retains it.

`FloatingWindowLayoutPolicy` preserves native floating behavior. Explicit
resize, snap and maximize requests use its placement plan; ordinary activation
needs no geometry change. Floating policy does not request periodic layout
evaluation. Other policies consume the existing task publication, not another
observer. Identical observations are consumed once, including rejected plans.

`DesktopWindowLayoutController` adapts the policy to Android tasks and the
existing `DesktopTaskRuntimeRegistry`. `DesktopTaskController` serializes
placement, policy replacement and reveal preparation in `DesktopWorkspaceQueue`.
Each queued operation reads its live ownership-filtered workspace before
planning. Taskbar, Alt+Tab, overview and MCP activation share reveal preparation,
then use the unchanged z-order-only focus gateway.

`DesktopWindowAction` is the typed command vocabulary shared by keyboard input,
touchpad controls, task menus, Linux panels and automation. Input adapters map
their own keys or protocol values to these commands; numeric identifiers are
used only in diagnostics. Fullscreen eligibility belongs to the command, while
live target selection and ownership checks remain in `DesktopTaskController`.
`DesktopWindowTransitionController` executes the selected action through the
existing geometry and fullscreen owners. A layout plan cannot issue these
actions or acquire their authority implicitly.

The complete plan is validated before acquiring geometry ownership. Missing,
foreign and non-freeform targets reject the plan. `TaskWindowingCommand`
revalidates live display and mode at the shell boundary. One placement uses
Android's native task resize. Multiple placements use one bounds-only WMShell
`CHANGE` through `FrameworkWindowingApi` and the existing transition executor.
Neither operation changes density, mode, task parent or z-order.

Submission acknowledgement and observed geometry are different facts. An
acknowledged command releases its pending state even when Android constrains
the rectangle. The existing task observer publishes actual bounds; success
does not manufacture an exact-rectangle observation. A policy must reconcile
application constraints from these observations rather than assume unlimited
offscreen placement. There is no retry timer or exact-bounds polling in the
layout controller. Native transition barriers and focus confirmation retain
their existing owners.

## Geometry Ownership

Placements identify ordinary floating bounds, native/explicit arrangements,
temporary layout bounds or release of layout ownership. Each task's
`WindowLayoutOwnership` retains its pre-layout floating rectangle across
reflows. Native maximize/restore history and fullscreen restore remain separate
fields of the same task runtime state.

Temporary layout coordinates do not replace saved application positions or
parked floating geometry. Parking consumes restore bounds from the existing
task publication, including the display-loss snapshot. Ordinary fullscreen
parking retains fullscreen mode. Policy replacement restores freeform windows
before publishing the new policy; failure retains the previous policy.

Fullscreen tasks and tasks crossing a fullscreen transition are ineligible for
layout placement. Their planes, mode and focus remain owned by the fullscreen
subsystem. Releasing a policy during fullscreen changes only its saved freeform
destination, not the live task. Android applications and individually hosted
X11/Wayland toplevels share this task contract. Hosted dependent surfaces remain
under [shell layout](shell-layout.md), not independent layout columns.

## Verification

Unit tests cover pure planning, batch validation, geometry ownership, failure,
late callbacks, policy continuations, parking and reveal ordering. Native
caption/maximize tests execute through the same geometry controller.
Desktop self-test checks `LAYOUT-001` and `LAYOUT-002` install a temporary test
policy, place two real tasks before activation and restore their original
floating geometry through production policy replacement.

The optional [Scrollable tiling](scrollable-tiling.md) mode implements this
contract. Its controls and preferences are separate from the geometry executor.
