# Chrome Custom-Caption Input Investigation

Initial investigation: 2026-08-01

Follow-up experiment: 2026-08-24

Status: unresolved firmware integration defect. All experimental runtime code
was removed. This document records the evidence and rejected approaches; it
does not describe a feature present in the current MagicDesk build.

## Goal

Allow applications such as Chrome to place interactive controls in Android's
native desktop caption and receive normal mouse input on every desktop display.
The solution must preserve native caption dragging and resizing without
package-specific logic, coordinate maps, replacement captions, duplicate
clicks, or changes to ordinary application input.

Chrome tabs remain usable through `Ctrl+Tab`; the defect affects pointer input
inside the custom caption.

## Expected Android Behavior

Chrome uses Android's customizable-caption path. It draws tabs in the caption
area and calls `setSystemGestureExclusionRects()` for regions that must remain
interactive application content.

In the corresponding WMShell path, the native caption observes drag gestures
as a `SPY` input window while application-declared exclusion regions remain
available to the application below it. The application should receive the
original mouse stream directly; no component should replay the click.

## Confirmed Firmware Failure

The defect was reproduced on Nubia Android 16 firmware with Chrome on a
secondary desktop display.

- InputDispatcher placed `Embedded{Caption of Task=...}` above Chrome.
- The caption input window had a touchable region covering the complete caption
  strip.
- Chrome's caption bounding rectangles were present and Chrome contained the
  expected `setSystemGestureExclusionRects()` call.
- The display's effective `mSystemGestureExclusion` region was empty.
- Nubia `DesktopModeWindowDecorViewModel` registered its
  `SystemGestureExclusionListener` for `mContext.getDisplayId()`, which was
  display `0`, and did not maintain the Chrome region for the secondary
  display.
- The system caption consequently received the complete mouse click. Chrome
  received no `MotionEvent`.

This explains why keyboard tab switching works while pointer tab selection
does not. The failure is in the firmware's multi-display WMShell integration,
not in Chrome's placement of its tabs and not in MagicDesk window bounds.

The inspected `SystemUI_MFV.apk` contained the relevant modern implementation
symbols, including `ENABLE_ACCESSIBLE_CUSTOM_HEADERS`, `caption-touch`,
`mSystemGestureExclusionRects`, and `INPUT_FEATURE_SPY`. Their presence shows
that the firmware contains much of the expected path, but no supported switch
was found that completes it for a secondary display. A feature flag alone was
therefore not established as a solution.

## Generic Router Prototype

A temporary generic router was built to determine whether MagicDesk could
repair the missing handoff without patching SystemUI.

The prototype deliberately had no Chrome package check. It used:

- the existing shell UserService rather than another Shizuku process;
- `WindowInfosListener` and real `InputWindowHandle` data for window Z-order,
  display identity, input-channel tokens, and touchable regions;
- WMS gesture-exclusion data to identify application-owned caption content;
- the authoritative focused-task snapshot, avoiding asynchronous focus-event
  ordering races;
- the existing native input bridge as the physical primary-click trigger;
- event-driven activation only while the focused top window had a matching
  custom-caption exclusion region.

A one-shot probe under shell UID 2000 successfully observed all required
windows, the focused task, the real exclusion region, and Binder tokens without
files or polling. This established that detection was possible. It did not
establish authority to reroute the gesture.

## Transfer Experiments

### 1. Transfer After Mouse ACTION_DOWN

The normal Android mouse sequence is:

```text
ACTION_DOWN
ACTION_BUTTON_PRESS
ACTION_BUTTON_RELEASE
ACTION_UP
```

The prototype requested a transfer after `ACTION_DOWN`. InputDispatcher
returned `false`: the mouse stream was not considered a transferable touch
gesture at that point.

### 2. Transfer After ACTION_BUTTON_PRESS

Moving the request after `ACTION_BUTTON_PRESS` also returned `false`. On the
tested firmware, the mouse stream never entered the touch-gesture transfer
state expected by this API.

### 3. Android 15 And Android 16 Binder Routes

The Android 15-compatible hidden `IInputManager` route was investigated first.
The tested Android 16 firmware did not expose a usable mouse-transfer operation
through that Binder path.

Android 16 also exposes transfer through WindowManager using
`InputTransferToken` values. The observer was corrected to retain the actual
input-channel token from `InputWindowHandle`; using a generic window token was
not sufficient.

Correct tokens did not remove the authority boundary. Android verifies that
the caller owns the source embedded window. The source caption belongs to
SystemUI, while MagicDesk and its shell UserService do not. The standard
`WindowManager.transferTouchGesture()` route therefore cannot transfer this
caption gesture on behalf of SystemUI.

Root-backed shell identity would not by itself provide ownership of the
SystemUI input window. This is a window-token ownership check, not merely a
filesystem or ordinary Binder permission check.

### 4. Concurrent Synthetic Touch Fallback

The prototype next created a short touch stream only inside a confirmed
application exclusion region:

```text
mouse: DOWN -> BUTTON_PRESS ...
touch: DOWN -> transfer -> UP
mouse: ... BUTTON_RELEASE -> UP
```

This conflicted with the still-active mouse stream. The subsequent mouse
`BUTTON_RELEASE` was rejected during testing, leaving the physical click
sequence inconsistent. Such a failure can produce a stuck button, corrupt the
next click, or split drag and double-click state between two recipients.

### 5. Synthetic Touch After Mouse Completion

The fallback trigger was moved after the original mouse stream had completed.
This avoided overlapping active streams, but changed one physical action into
two separate actions:

1. a real mouse click delivered to the SystemUI caption;
2. a delayed synthetic touch tap intended for the application.

Even if a tab changes, this is not equivalent to the original input:

- Chrome receives touch rather than mouse semantics;
- button identity and keyboard modifiers are lost;
- middle-click, context click, tab dragging, hover, and double-click ordering
  cannot be preserved;
- focus, bounds, Z-order, or exclusion regions can change between the two
  actions;
- ordinary coordinate injection is hit-tested again and still reaches the
  caption unless the prohibited transfer succeeds.

This fallback was rejected as behaviorally incorrect and race-prone.

## Self-Test Findings

A temporary generic fixture placed an interactive control in a custom caption
and declared its exclusion region. The regular desktop assertions continued to
pass, while the production-routed custom-caption click consistently failed.
This confirmed the product defect without depending on Chrome coordinates or
package behavior.

During the same work, the debug smoke script was found to use `am instrument`
against the application package. Android replaced the running MagicDesk
process, which also killed MCP and looked like a self-test crash. That unrelated
test-harness defect was fixed and retained in commit `a2d5461`: the smoke test
now uses the debug self-test Activity, and ordinary self-test clicks use the
production pointer route.

The custom-caption fixture and transfer router were removed because no
supported production route could satisfy their assertion. The final simulated
self-test completed with 112 PASS results, no failures, one expected warning,
and a live MagicDesk/MCP process.

## Rejected Runtime Changes

The following changes must not be reintroduced as a fix:

- Chrome package checks or inferred tab coordinates;
- replaying a physical click as a second touch event;
- continuous window, exclusion-region, or task polling;
- an extra shell process or a Binder call for every ordinary click;
- direct coordinate injection that ignores Android hit testing;
- a replacement MagicDesk caption overlay;
- disabling the complete native caption and losing drag, resize, or menus;
- relying on root identity to bypass input-window ownership.

The removed prototype leaves no process, listener, per-click Binder call, or
runtime overhead in `main`.

## Acceptable Solution Contract

A correct solution must satisfy all of these conditions:

1. The application receives the original mouse stream, not a replayed touch.
2. Native caption dragging, resizing, menus, and transition ownership remain
   intact.
3. Exclusion regions are associated with their actual display and task.
4. Ordinary windows and background custom-caption windows add no input work.
5. Focus and display changes update the route from authoritative events rather
   than fixed delays.
6. The implementation is generic across applications and display drivers.
7. No SystemUI restart or persistent global patch is required during a desktop
   session.

The preferred fix is in WMShell/SystemUI: register exclusion listeners for
each active display, retain regions by display, and configure the caption input
surface so application-declared custom-header regions receive the original
stream. An upstream ROM or vendor fix can implement that at the correct owner.

For MagicDesk, a future platform fallback is acceptable only if the firmware
exposes a supported way to activate its existing accessible-custom-header or
`SPY` path. It must be capability-driven and isolated behind the platform
boundary, not tied to Chrome or Nubia model names.

## Future Validation

On another firmware or after a SystemUI update:

1. Compare gesture-exclusion regions on display `0` and the desktop display.
2. Inspect the caption input configuration and effective touchable region.
3. Verify that the focused application receives the original mouse
   `ACTION_DOWN`, button press/release, and `ACTION_UP` sequence.
4. Test tab selection, middle-click close, tab dragging, caption dragging, and
   native window controls.
5. Restore the generic self-test fixture only when a supported production route
   exists to make it pass.
