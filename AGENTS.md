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
