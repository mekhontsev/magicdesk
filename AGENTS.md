# Repository Instructions

## Device Support Work

Before changing platform, display, window, input, launcher, or cleanup behavior,
read `CONTRIBUTING.md`, `docs/ai-assisted-device-porting.md`, and the relevant
sections of `docs/architecture.md`, `docs/compatibility.md`, and
`docs/automation.md`.

Establish a current-`main` baseline from a complete compatibility report and an
exact reproduction before editing. Classify the owning boundary first. Keep one
APK and one codebase: prefer shared Android behavior and runtime capabilities;
isolate genuine firmware behavior in a focused `PlatformExtension`, SoC
behavior in `SocDisplayModeBackend`, display lifecycle in the existing four
drivers, and window policy in the existing transition gateway. Do not add model
checks, fixed runtime delays, coordinate-based production actions, root
requirements, or device-specific forks.

Use semantic MCP actions and event-driven waits when available. Interactive
self-tests require an awake, unlocked device and must retain production paths
and assertions. Do not weaken a test to make a device pass. Close an active
desktop through its production cleanup path before installing another APK.

## Fullscreen Focus Work

Before changing fullscreen, task focus, Alt+Tab, taskbar activation, or task
display-area ownership, read `docs/fullscreen-1.8-reference.md`. Taskbar,
Alt+Tab, overview, and MCP focus must continue through the same
`DesktopTaskController` gateway. Preserve the activate/demote contract in
`docs/fullscreen-transitions.md`: activation and demotion only change z-order;
they never hide, minimize, reparent, resize, or change an application's
windowing mode.

For `DEFAULT` ownership, a fullscreen task must retain a stable ordering plane
for its entire fullscreen residency. Steady-state activation must not collapse
peers into one parent, move a task between parents, use
`startNewTransition`, or repeat the reorder as a synthetic focus transition.
Those operations expose a visible WMShell handoff and browser-style apps can
interpret it as an immersive exit. Do not use `applySyncTransaction` for
steady-state focus: a stopped target can have `NO_SURFACE`, making BLAST wait
for its draw timeout. `SESSION` ownership keeps all phone desktop tasks in its
one existing parent and reorders children only.

All shell WCT submission must have one explicit owner. Ordinary reorder, mode,
bounds, and parent operations are atomic WCTs, not raw transitions. A cold
freeform launch starts behind the desktop host and, after its task ID is known,
uses one complete WMShell `OPEN` transition for mode, bounds, and order. Do not
retain a raw opening token across the launch boundary. Structural fullscreen
plane anchors also launch behind the foreground task and never participate in
the user's focus transition. Never remove an owned desktop display while a
WindowManager performance session still references it.

The established surface-producing fallback is centralized as
`ShellWindowTransitionExecutor.startForShellAdoption`. It creates a transition
in WMCore without registering it in SystemUI's process-local pending list;
current WMShell versions adopt it when it becomes ready. Treat the returned
token as opaque: do not finish it from MagicDesk. Experiments may
replace this boundary, but must not change existing WCT contents, transition
types, ordering, or call sites until equivalent surface behavior is proven.

Before completing such a change, compare the transaction sequence directly
with tag `v1.8.0`, run the focused unit tests and the simulated and phone
desktop self-tests, and verify repeated fullscreen switching on a wired display
with a browser-style application. Both self-tests must retain zero failures;
never weaken their assertions to accommodate an implementation change.

## Releases

When the user asks to publish a MagicDesk release, treat the release tag and
the next development cycle as one task.

1. Verify that the tag matches `magicDeskVersionName` in `gradle.properties`.
2. Build, test, tag, push, and verify the signed GitHub release.
3. Choose the next development version with the user if it was not already
   specified.
4. Run `scripts/start-next-version.sh VERSION VERSION_CODE`, commit the version
   change, and push it.

Do not report the release task complete while the version on `main` still
matches the newest release tag. Do not increment the version for ordinary
pushes; CI adds a unique development-build suffix automatically.
