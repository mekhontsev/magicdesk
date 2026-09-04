# Repository Instructions

## Device Support Work

MagicDesk development targets Android 15 / API 35 and newer. Android 15 is the
minimum framework compatibility baseline: changes developed on newer releases
must preserve the Android 15 path unless the project baseline is explicitly
raised.

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

Keep Android-release differences in `FrameworkRuntime` and its focused
adapters. `FrameworkWindowingApi` is the only owner of hidden
`WindowContainerTransaction` primitives; `FrameworkWindowingCompat` owns
release-dependent semantics and polyfills; `HiddenTaskApi` owns raw task
members; `FrameworkTaskSnapshotSource` publishes typed Binder snapshots; and
`FrameworkInputWindowObservationSource` owns SurfaceFlinger input-window
commit events, while `FrameworkInputSnapshotSource` owns one-shot
InputDispatcher snapshots. Callers must
not reflect these APIs, pass raw framework member names, or introduce text task
queries themselves. An unavailable framework observation remains unknown; do
not synthesize a value that can be mistaken for an application request. Use
the debug-only
`MAGICDESK_FRAMEWORK_OVERRIDE` profile to exercise older semantics on a newer
device. Framework task observation belongs to
`FrameworkTaskObservationSource`; policy consumers may reuse its typed
snapshots but must not add another periodic task query.

Every wait must identify its mechanism and reason. State polling belongs in
`BoundedStateAwaiter`, event-driven monitor waits in `EventDrivenWaits`, and
intentional protocol/backoff/gesture delays in `RuntimeDelays`. Do not call
`Thread.sleep`, `SystemClock.sleep`, or `Object.wait` directly. Prefer an
existing callback or observer; the 150 ms framework task snapshot is the one
documented active-session fallback, not a general polling interval.

Use semantic MCP actions and event-driven waits when available. Interactive
self-tests require an awake, unlocked device and must retain production paths
and assertions. Do not weaken a test to make a device pass. Close an active
desktop through its production cleanup path before installing another APK.

## Local Device Automation

Never reboot the phone without asking the user and receiving explicit
confirmation immediately before the reboot command. An earlier discussion or
agreement that a reboot may be needed later does not count as confirmation.

After a phone reboot, start Shizuku with
`./scripts/start-shizuku-shell.sh` before privileged testing. This is the
canonical launcher: it starts the service as UID 2000 in the adb-shell SELinux
domain with the verified supplementary groups. Run it only after a reboot or
when Shizuku is unavailable; do not replace it with ad hoc `su` or `adb`
commands or restart a healthy service.

The MagicDesk MCP connection is already configured in `~/.codex/config.toml`.
Do not add a duplicate server or replace its endpoint or token unless the user
has reset the MCP token or application data. The server lives in the MagicDesk
process. After a reboot, open MagicDesk once from its launcher; the process
exposes MCP even before Shizuku becomes ready and promotes the same runtime
after Shizuku starts. If MCP tools are absent, first search the complete tool
catalog for the deferred `mcp__magicdesk__` tools; they may be omitted from the
short tool declaration shown in the session. If the complete catalog contains
them, MCP is available immediately: call those tools directly. If that search
is empty, verify that MagicDesk is open and **Local MCP automation server**
remains enabled, then ask the user to run `/mcp reload` when the current Codex
client supports it. Slash commands are user-side TUI actions and cannot be
invoked by the agent. After the user confirms the reload, search the catalog
again. Request one `resume` only when live reload is unavailable or the
refreshed catalog still omits MagicDesk. Reopening MagicDesk after an APK
reinstall reuses the existing MCP configuration.

## Fullscreen and Focus Work

Before changing fullscreen, task focus, Alt+Tab, taskbar activation, or task
display-area ownership, read `docs/fullscreen-transitions.md`. Taskbar,
Alt+Tab, overview, and MCP focus must continue through the same
`DesktopTaskController` gateway. Activation and demotion change only z-order;
they never hide, minimize, reparent, resize, or change an application's
windowing mode.

Use the same ownership model on every target. The HOME host and freeform tasks
remain in Android's standard root workspace; every fullscreen task retains one
stable organizer plane for its complete fullscreen residency. Every shell
transaction has one explicit owner, and steady-state focus only reorders the
existing hierarchy. Do not reintroduce a session-wide application task area or
remove an owned desktop display until its transitions are quiescent. Low-level
transition boundaries and rejected approaches are documented in
`docs/fullscreen-transitions.md`.

Before completing such a change, run the focused unit tests and the simulated
and phone desktop self-tests. Changes to shared task-area ownership or display
lifecycle also require the wired self-test. Required self-tests must retain
zero failures; never weaken their assertions to accommodate an implementation
change.

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
