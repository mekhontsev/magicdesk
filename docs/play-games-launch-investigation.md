# Google Play Games Windowed Launch Investigation

Investigation date: 2026-08-22

Status: deferred. This document records experimental results and does not
describe the current architecture.

## Goal

Launch Google Play Games directly in a freeform window, including its first
launch with the system notification permission dialog, without an application
crash, flicker, temporary fullscreen state, or subsequent geometry correction.

The solution must be generic across applications. It must not check for the
Play Games or PermissionController package name.

## Observed Failure

- Without the permission dialog, Play Games normally launches in a window.
- After its data is cleared, the first launch redirects the initial Activity to
  the main Activity and requests `POST_NOTIFICATIONS`.
- At that point, Play Games either crashes or visibly flickers under the system
  dialog. The outcome is nondeterministic.
- Sometimes the application remains alive but its task becomes fullscreen.
- An old system crash dialog can survive an application force-stop or desktop
  restart and interfere with evaluation of the next attempt.
- MCP and manual launches initially produced different visual results, but the
  underlying race was reproducible through both routes.

The exception inside Play Games is:

```text
NullPointerException: Attempt to invoke virtual method
'java.lang.Class java.lang.Object.getClass()' on a null object reference
```

## Confirmed Android Sequence

1. MagicDesk launches
   `com.google.android.gms.games.ui.destination.main.MainActivity`.
2. The application redirects the launch to
   `com.google.android.gms.games.ui.v2.MainActivity`.
3. PermissionController opens `GrantPermissionsActivity`.
4. At the same time, WMS records `wm_relaunch_resume_activity` with a
   `d80/c00` configuration change.
5. Play Games crashes or enters a relaunch/flicker loop.

The `d80` flags correspond to changes in orientation, screen layout, screen
size, and smallest width. This is consistent with the Activity moving from its
initial display configuration to a freeform configuration.

The primary hypothesis is that PermissionController itself does not cause the
crash. Play Games starts cold in one configuration and is then relaunched in
another during its internal redirect and permission request.

## Approaches Tested

### 1. Original Launch Transition

At `HEAD`, MagicDesk launches a PendingIntent, identifies its `taskId` early,
and joins the opening task to a freeform transition through
`ShellPreparedTaskTransition.joinOpenAsFreeform()`.

This works well for ordinary applications, but the Play Games Activity is
created with a fullscreen-like configuration and then receives a freeform
relaunch. During the first permission request, that is sufficient to trigger
the crash.

### 2. Disabling The Resize Pulse

The transient resize pulse was disabled completely for this test.

Result:

- Play Games continued to crash intermittently;
- ordinary window workflows, Miracast, and the external display showed no
  visible regression;
- the pulse was independent of the crash and was removed in a separate,
  already committed change.

### 3. Temporary Fullscreen Launch

Allowing the task to remain fullscreen temporarily sometimes prevented the
crash. However, the task could remain fullscreen permanently, while the system
dialog hid the taskbar.

This approach violates the Windowed contract and was rejected.

### 4. Special Permission Dialog Handling

The investigation considered exemptions while the system dialog was visible,
pre-granting the permission, and coupling the transition to
PermissionController.

All variants were rejected:

- MagicDesk must not bypass the user's permission decision;
- the window transition core must not depend on a specific system package;
- the dialog only amplifies the general configuration race.

### 5. Early Task ID Detection Through The Shell Service

The existing `IActivityController` observer and task callbacks can identify a
new task very early.

That is not sufficient: correcting the mode after Activity creation still
causes a configuration relaunch. Early detection is useful for observation and
identity, but it does not control the initial configuration.

### 6. Organizer Root And Direct Target Launch

The experiment created an organizer freeform root before launching the
application, then tried to launch the target directly into it through
`setLaunchTaskId`.

WMS ignored or did not support using the organizer root as an ordinary launch
task. The target was created separately, sometimes fullscreen, or was not
found by the observer.

### 7. Organizer Root With A Child Task

The target was launched under a prepared root, after which the child task was
released into the normal desktop task area.

Without an explicit complete root/child configuration, the application still
received the wrong initial configuration and crashed.

### 8. Explicitly Copying The Complete Configuration

The experiment atomically set bounds, app bounds, `screenWidthDp`,
`screenHeightDp`, `smallestScreenWidthDp`, orientation, and screen layout for
the root and child task.

This was the only approach in which Play Games reliably appeared in a window,
the system permission dialog remained in the same task, and no crash occurred.

It was rejected as a permanent solution. The specified fields became
task-level overrides and were not cleared automatically. After a normal
resize, the task bounds changed while the Activity continued to observe the
old Configuration. That breaks dynamic resize, snap, and fullscreen
transitions.

### 9. deferConfigToTransitionEnd

The experiment used
`WindowContainerTransaction.deferConfigToTransitionEnd()`.

It did not solve the problem: the Permission Activity appears after the
boundary of the original transition, and the target configuration change still
intersects the new lifecycle.

### 10. Transparent MagicDesk Seed Activity

The experiment added a separate transparent `WindowLaunchSeedActivity`. The
idea was to create a normal MagicDesk task, bring it to its final freeform
state, and then open the target inside the stable task.

The seed task was created with the correct freeform bounds. Dumpsys confirmed
its final window configuration.

### 11. Shell Launch Of The Target Into The Seed Task

The shell launched the target with `setLaunchTaskId(seedTaskId)`.

The target entered the same task and the permission dialog also remained in
that task. However, the target's initial ActivityRecord still received the
display configuration, followed by a `d80` relaunch and crash.

### 12. Target Launched By The Seed Activity

The seed Activity attempted to open the target through an ordinary app-side
`startActivity()` call.

The first implementation passed a nested `Intent` through the shell. Android
16 Intent Redirect Hardening rejected it because its creator token was
missing:

```text
INTENT_REDIRECT_EXCEPTION_MISSING_OR_INVALID_TOKEN
```

Passing a URI and safely parsing the Intent again inside the application
eliminated that error. Task-boundary flags were removed and the target remained
in the seed task. However, the target still received its own `d80` relaunch.

### 13. Waiting For The Seed Activity To Stabilize

Initially, the command to launch the target reached the seed during its own
configuration relaunch. The launch was moved to `onPostResume()` after seed
recreation, without fixed delays.

The seed became stable before opening the target, but the target still received
`d80`. The seed race was real, but it was not the primary cause of the crash.

### 14. App-Side ActivityOptions

The experiment evaluated:

- public `setLaunchBounds()`;
- hidden `setLaunchWindowingMode()` through reflection;
- a Bundle containing privileged ActivityOptions, created by the shell and
  passed to the application.

`setLaunchBounds()` was insufficient. Hidden API enforcement blocks the hidden
method in the ordinary MagicDesk process. WMS evaluates and sanitizes the
passed Bundle according to the actual application caller UID, so the shell
privileges do not carry over.

### 15. Typed Callback And Source Token

A temporary AIDL callback was added. After `onPostResume()`, the seed passed
its `getApplicationWindowToken()` to the shell, which launched the target with
that source token and privileged freeform options.

WMS still added `NEW_TASK` because the shell call has no real
`IApplicationThread` for the calling Activity. The target received `d80` again.
The AIDL prototype was removed.

### 16. startActivityAsCaller

The firmware's `IActivityTaskManager` was inspected locally. The
`startActivityAsCaller` method could use the source Activity and its caller
identity, but requires `android.permission.START_ACTIVITY_AS_CALLER`.

UID 2000 does not have this permission. The method is available to SystemUI but
is not suitable for MagicDesk's normal Shizuku shell mode.

### 17. startActivityWithConfig

The experiment used the hidden `startActivityWithConfig` method with a
single-use launch Configuration.

On this firmware, the method updates the global configuration in
`RootWindowContainer` instead of supplying a safe task-local initial
configuration. It causes an activity-type conflict in
`WindowConfiguration.setActivityType()` and
`ConfigurationContainer.onConfigurationChanged()`.

The approach is dangerous to the whole system and was removed completely.

### 18. Prepared Normal Task And PendingIntent Transition

The last unfinished prototype performed these steps:

1. Create an organizer root.
2. Create a normal transparent seed task within it.
3. Release the task into the desktop task area with its final bounds.
4. Start the target PendingIntent transition with `setLaunchTaskId`, freeform
   mode, and a flexible launch size.

Unlike the original mechanism, this variant creates a normal task in its final
freeform state before the target Activity. The transition should therefore not
need to change its mode after Activity creation.

The variant was built after the final change but was not tested. It affected
the main launch path and remained a large experimental diff, so it was not
kept on `main` without confirmation.

## Conclusions

The strongest evidence supports this cause:

> The Play Games cold-start Activity is not created in its final freeform
> configuration. A subsequent relaunch that changes screen size and layout
> coincides with the redirect and permission flow, after which the application
> crashes.

PermissionController, the resize pulse, and late task identification are not
independent causes.

## Acceptable Solution Contract

A future generic launch pipeline must ensure that:

1. The target Activity is created directly with the final freeform mode and
   bounds.
2. No temporary fullscreen state or after-the-fact mode correction occurs.
3. MagicDesk does not cause a cold-start configuration relaunch.
4. No persistent overrides remain for dp, app bounds, orientation, or screen
   layout.
5. Native resize, caption snap, fullscreen, and restore continue to work after
   launch.
6. No package-specific or PermissionController-specific branches exist.
7. One authoritative transition path owns the launch.
8. A third-party application failure does not disrupt the taskbar, input sink,
   or subsequent MagicDesk operation.

## Approaches Not To Repeat

- Do not restore the transient resize pulse as an attempted fix for this
  failure.
- Do not correct fullscreen to freeform after Activity creation and treat that
  as a solution.
- Do not keep a complete Configuration override on a task without a proven
  mechanism for removing it at the correct lifecycle point.
- Do not use `startActivityWithConfig` on this firmware.
- Do not pass a nested shell Intent without accounting for Android 16 redirect
  hardening.
- Do not rely on `startActivityAsCaller` under UID 2000.
- Do not add exceptions for the Play Games or PermissionController packages.
- Do not evaluate a new launch while old crash dialogs remain on screen.

## Clean Manual Experiment Setup

1. Force-stop Play Games.
2. Clear its data if the first permission flow is required.
3. Close any remaining system crash dialogs.
4. Clear the relevant event and logcat records.
5. Start MagicDesk and the desktop session again.
6. Perform exactly one Windowed launch of Play Games.
7. Correlate the visual result with `wm_create_activity`,
   `wm_relaunch_resume_activity`, `wm_set_resumed_activity`, and the task
   configuration.

## Possible Follow-Up

1. Evaluate the prepared normal task plus PendingIntent transition once in
   isolation, without mixing it with other changes.
2. If `d80` remains, stop rearranging launch APIs and add focused tracing of
   the ActivityRecord/RunningTaskInfo configuration at each stage.
3. Investigate a launch-scoped configuration lease: establish the initial
   configuration before Activity creation and release it on an observed
   lifecycle or task event rather than a timer. Before accepting it, prove that
   it causes no resize regressions.
4. Add a test Activity that performs a redirect and requests a runtime
   permission. The self-test should verify the absence of a relaunch and
   temporary fullscreen state without depending on Play Games.
5. After a solution, verify ordinary cold start, Play Games first run, resize,
   native caption snap, fullscreen/restore, and every display type.
