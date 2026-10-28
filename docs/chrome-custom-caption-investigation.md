# Chrome Custom-Caption Input

Status: unresolved firmware integration defect on the observed Nubia Android 16
secondary-display path. MagicDesk has no custom-caption input workaround.
Keyboard tab switching through `Ctrl+Tab` remains usable.

## Expected Behavior

Applications draw interactive controls in the native desktop caption and
declare their regions through `setSystemGestureExclusionRects()`. WMShell's
caption observes drag gestures as a `SPY` input window while those regions
receive the application's original mouse stream. No component should replay
the click or replace the native caption.

## Observed Firmware Boundary

Inspection on Nubia Android 16 established:

- InputDispatcher places `Embedded{Caption of Task=...}` above Chrome with
  a touchable region covering the whole caption.
- Chrome's caption rectangles and exclusion API call are present, but the
  secondary display's effective `mSystemGestureExclusion` is empty.
- Nubia `DesktopModeWindowDecorViewModel` registers its exclusion listener for
  `mContext.getDisplayId()`, display 0, without maintaining the secondary
  display's application region.
- The caption consequently receives the complete click; Chrome receives no
  `MotionEvent`.

The inspected SystemUI contains accessible-custom-header and `INPUT_FEATURE_SPY`
symbols. Their presence alone does not establish a usable activation switch or
a working secondary-display path. This evidence does not certify other firmware.

## Authority And Input Constraints

Shell UID 2000 can observe the relevant window, task, exclusion region and
input-channel tokens. Observation does not grant ownership of SystemUI's caption.

`WindowManager.transferTouchGesture()` requires ownership of the source
embedded window. Neither MagicDesk nor its shell service owns that window;
root identity alone does not supply the missing token ownership. The tested
mouse stream is also not transferable through the touch API after
`ACTION_DOWN` or `ACTION_BUTTON_PRESS`.

Synthetic touch is not an equivalent fallback. Concurrent injection can break
the physical button sequence; a delayed tap sends a second action, loses mouse
buttons/modifiers and changes drag, hover and double-click semantics. Hit testing
still reaches the caption unless the ownership boundary is resolved.

A production implementation must not add package checks, inferred tab
coordinates, click replay, continuous task/window polling, an extra shell process,
per-click Binder work for ordinary windows, replacement caption overlays or
disabled native window controls.

## Solution Contract

1. Deliver the original mouse stream to application-owned regions.
2. Preserve native caption drag, resize, menus and transition ownership.
3. Associate exclusions with the actual display and task.
4. Keep ordinary/background windows free of additional per-click work.
5. React to authoritative focus/display events, not fixed delays.
6. Use shared application/display policy, with genuine firmware behavior behind
   the platform boundary.
7. Require neither SystemUI restart nor a persistent global patch during a session.

WMShell/SystemUI owns the preferred correction: per-display exclusion listeners
and caption input surfaces that leave declared content regions accessible.
A MagicDesk fallback requires a demonstrably supported firmware capability,
not a Chrome package or Nubia model check.

## Validation

On each target firmware:

1. Compare exclusion regions on display 0 and the desktop display.
2. Inspect caption input configuration and effective touchable regions.
3. Verify application delivery of the original mouse `ACTION_DOWN`,
   button press/release and `ACTION_UP`.
4. Check tab selection, middle-click close, tab dragging, caption dragging and
   native controls.
5. Require a generic custom-caption fixture through the production input route.

Ordinary desktop self-test success does not prove custom-caption input.
