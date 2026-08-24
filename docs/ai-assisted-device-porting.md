# AI-Assisted Device Support

This workflow is for a contributor who has access to an unsupported or partly
supported Android device and wants to investigate it with an AI coding agent.
It applies whether the agent runs on the phone, in Termux, or on a computer
connected through ADB.

The goal is not to produce a device-specific MagicDesk build. The result must
remain part of the single APK and codebase, with generic Android behavior kept
shared and optional firmware behavior isolated behind an existing platform or
SoC boundary.

## Choose The Contribution Track

### Evidence-only track

Use this track when the device owner cannot build MagicDesk. The owner and AI
can still produce a useful, bounded handoff:

1. Install the current signed development APK.
2. Complete Device Setup using the normal shell UID 2000 runtime first.
3. Reproduce one problem and collect the refreshed compatibility report.
4. Run the available self-tests and complete the relevant manual checklist.
5. Open one GitHub issue with the report, exact steps, expected result, actual
   result, and any comparison with the firmware's own projection UI.

Do not infer a vendor implementation from a model name or a short log excerpt.
The schema-versioned JSON in the full report is the machine-readable handoff
that a coding agent or maintainer can turn into fixtures and capability probes.

### Contributor track

Use this track when the device owner can clone and build the repository. Follow
the evidence-only steps first, then reproduce the failure on the current
`main` branch before changing code.

The AI should read these files in order:

1. `AGENTS.md`
2. `CONTRIBUTING.md`
3. `docs/architecture.md`
4. `docs/compatibility.md`
5. `docs/automation.md`
6. the source package that owns the affected behavior

Reading only the nearest failing class is not enough for window, display,
input, launcher, and cleanup changes. Those behaviors deliberately share
session-level invariants.

## Establish A Baseline

Before editing:

1. Record the exact MagicDesk build, Android fingerprint, shell service UID,
   active platform composition, display target, and reproduction steps.
2. Refresh **Tools > Diagnostics** after the failure. Use **Extended vendor
   probe** only after explicit user confirmation and only when the standard
   report lacks evidence for a firmware feature.
3. Run each available phone, simulated, wired, and wireless self-test while
   the device is awake and unlocked. Record unavailable targets rather than
   treating them as failures.
4. Check `git status`. Preserve unrelated owner changes and do not commit
   reports, captures, `local.properties`, or device-specific secrets.

UID 2000 is the portable baseline. A user-selected root-backed shell service
may be used as a differential probe: if an operation works only as UID 0, that
is evidence about the firmware permission boundary, not a reason to make root
the normal MagicDesk path.

Do not install an APK over a live desktop session. Close the session through
the production **Close Desktop** path first so task state and system settings
are restored.

## Use MCP As The Observation Plane

When the AI can connect to MagicDesk, enable **Settings > Automation > Local
MCP automation server**. A client on the same phone uses the loopback endpoint
directly. A computer uses the documented `adb forward`; never expose the MCP
listener on a LAN. Treat its bearer token as a password.

Start with semantic observations and actions:

- `magicdesk.get_state`, `magicdesk.list_displays`, and
  `magicdesk.list_tasks` for the current topology;
- `magicdesk.get_diagnostics` for capability and provider evidence;
- `magicdesk.begin_trace` immediately before one reproduction and
  `magicdesk.end_trace` immediately after it;
- `magicdesk.get_events` for bounded task, display, focus, pointer, crash, and
  UI ordering;
- `magicdesk.list_ui_elements` and `magicdesk.invoke_ui_action` for real
  MagicDesk controls;
- `magicdesk.run_self_test`, `magicdesk.get_self_test`, and
  `magicdesk.wait_for_state` for test execution and completion;
- `magicdesk.capture_screenshot` or `magicdesk.sample_pixels` only when task
  and window state cannot establish the visual result.

Use `tools/list` as the authoritative command schema. Commands are
asynchronous when Android is asynchronous; establish their postcondition with
`wait_for_state` instead of sleeping for a guessed duration.

Prefer semantic MCP actions over pointer coordinates. Synthetic key or pointer
input is appropriate only when the behavior under test is itself an input path
or no production semantic action exists. Self-tests must use production
controllers whenever the route affects the result.

MCP is an observation and control adapter, not a second desktop policy. A fix
must work from the normal user interface without MCP.

## Classify Before Changing Code

Assign the failure to the narrowest owner that explains the evidence:

| Evidence | Owner |
| --- | --- |
| Behavior common to compatible Android firmware | Shared Android runtime |
| Phone, wired, wireless, or simulated lifecycle | Existing display driver |
| Firmware-only interface outside Android or SoC APIs | `PlatformExtension` component |
| Chipset display service or mode discovery | `SocDisplayModeBackend` |
| Window mode, bounds, focus, or organizer transition | Existing transition gateway and executor |
| Missing shell permission or inaccessible path | Diagnostic result and graceful fallback |

Platform selection happens once from runtime evidence. Firmware profiles
describe confirmed compatibility; they do not select drivers or execute
configuration. Package names, model names, fingerprints, and arbitrary delays
must not become substitutes for capability ownership.

If classification is still uncertain, improve bounded diagnostics first. Do
not simultaneously rewrite several providers to see which change appears to
help.

## Build The Regression Evidence

Add the smallest durable reproduction before or with the fix:

- a compatibility JSON fixture and parser/selection test for report-driven
  platform composition;
- a typed capability observation and stable diagnostic code for an inaccessible
  or malformed firmware interface;
- a self-test assertion when the workflow can be reproduced safely through
  production controllers;
- a focused unit or instrumentation test for deterministic shared logic.

Do not weaken an existing self-test to make a new device pass. First determine
whether the product regressed, the device violates a documented prerequisite,
or the test accidentally bypasses a production route.

Visual checks should first use task stack, window, focus, and UI state. Pixel
sampling is useful for failures such as a gray background or missing caption,
but must remain optional when the platform cannot provide pixels. Avoid frame
capture loops and persistent telemetry.

## Implement Through Existing Boundaries

- Put generic behavior in the shared path.
- Add a vendor package only for a focused `PlatformExtension`, and declare only
  the `PlatformComponent` values that it replaces.
- Keep phone, wired, wireless, and simulated lifecycle in the four shared
  display drivers. Do not multiply vendor and display-mode implementations.
- Keep SoC behavior behind `SocDisplayModeBackend`.
- Route semantic window policy through `DesktopWindowTransitionGateway` and
  shell/WMShell transactions through their existing executors.
- Extend `PlatformSourceIsolationTest` when adding a vendor package.
- Preserve cleanup and restoration ownership for every changed system setting,
  task state, input route, and long-lived shell helper.

Do not add a product flavor, separate APK, model-specific fork, root command in
MagicDesk, competing task observer, polling loop, fixed-coordinate runtime
action, or package-specific window exception unless the architecture document
explicitly defines that boundary.

## Verify The Change

Run local verification:

```sh
./gradlew verifyDevelopment
```

Then verify on the target device:

1. Re-run the exact reproduction inside an MCP trace when available.
2. Run phone and simulated self-tests.
3. Run each affected wired or wireless self-test on a real connected target.
4. On hardware with a vendor extension, build once with
   `-PMAGICDESK_PLATFORM_OVERRIDE=android` and verify that the standard driver
   remains isolated and functional where its declared capabilities permit.
5. Refresh Diagnostics and compare platform providers, displays, task/window
   state, input routing, launcher health, failures, and cleanup with the
   baseline.
6. Exercise normal UI entry points. Passing through MCP alone is insufficient.

Tell the device owner before an interactive UI test. Keep the device awake and
unlocked until the test reaches its real completion state; self-tests commonly
take more than one minute. Restore normal screen-sleep behavior afterwards.

An exact firmware fingerprint belongs in `firmware-profiles.json` only after
the relevant target matrix is confirmed by the device owner. A later OTA is
unverified until its changed fingerprint is tested.

## Keep The Handoff Reviewable

Prefer small commits separated by concern:

1. regression fixture or diagnostic evidence;
2. implementation;
3. confirmed firmware profile and documentation.

Amend small corrections that belong to the immediately preceding concern.
Before handing off, report the commands and device tests that actually ran,
remaining unavailable targets, and any residual firmware limitation.

Use this prompt to start another coding agent without transferring chat
history:

```text
Read AGENTS.md, CONTRIBUTING.md, docs/ai-assisted-device-porting.md,
docs/architecture.md, docs/compatibility.md, and docs/automation.md before
changing code.

Device and firmware:
<paste the Device and Runtime profile report sections>

Compatibility JSON/report:
<attach the complete refreshed report>

Exact reproduction:
1. ...

Expected result:
...

Observed result:
...

Available test targets:
phone=..., simulated=..., wired=..., wireless=...

First establish the current-main baseline and classify the owning architecture
boundary. Do not change code until the failure is reproduced or the missing
evidence is identified. Keep one APK and avoid model checks, fixed delays,
coordinate-based runtime actions, and device-specific forks.
```
