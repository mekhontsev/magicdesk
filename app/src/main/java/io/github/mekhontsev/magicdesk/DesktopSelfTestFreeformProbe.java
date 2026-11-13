package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.require;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.POLL_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.STEP_TIMEOUT_MILLIS;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;

import java.io.IOException;

/** Tests firmware freeform admission before any Desktop or HOME acquisition. */
final class DesktopSelfTestFreeformProbe {
    private DesktopSelfTestFreeformProbe() { }

    static void run(final Context context, final int displayId,
            final DesktopSelfTestResult result) throws DesktopSelfTestSteps.AbortSelfTest {
        DesktopSelfTestSteps.scenario(result, "FREEFORM-INDEPENDENT",
                "Freeform without Desktop or HOME", () -> {
            final DesktopDisplayInfo display = require(result, "FREEFORM-INDEPENDENT-001",
                    "Prepare independent freeform probe", () -> {
                if (DesktopHomeRoleLease.snapshot() != null || DesktopRuntimeBridge.hasWorkspaces()) {
                    throw new IOException("independent probe requires no Desktop or HOME lease");
                }
                DesktopSelfTestCleanup.removeFixtureTasks();
                return DesktopDisplayCatalog.require(displayId, null);
            }, "display=" + displayId + "; no Desktop or HOME lease");
            final Rect screen = new Rect(0, 0, display.width, display.height);
            final Rect bounds = new Rect(display.width / 4, display.height / 4,
                    display.width * 3 / 4, display.height * 3 / 4);
            final String token = "independent-" + Long.toHexString(System.nanoTime());
            final int taskId = require(result, "FREEFORM-INDEPENDENT-002",
                    "Launch freeform without Desktop or HOME", () -> {
                DesktopSelfTestFixtureState.clearLaunchMarkers(context);
                final Intent intent = TaskDisplayAreaLaunchCommand.createSelfTestIntent(displayId,
                        token, false, DesktopSelfTestFixtureAppearance.PRIMARY);
                return parseTaskId(ShellAccess.run(AppProcessCommand.run(
                        TaskDisplayAreaLaunchCommand.class.getName(),
                        "app " + displayId + " "
                                + ShellCommandLine.quote(intent.toUri(Intent.URI_INTENT_SCHEME))
                                + " " + bounds.left + " " + bounds.top
                                + " " + bounds.right + " " + bounds.bottom)));
            });
            require(result, "FREEFORM-INDEPENDENT-003", "Render independent freeform window", () -> {
                DesktopSelfTestFixtureState.awaitFirstFrame(context, token, displayId);
                return awaitWindow(displayId, taskId, screen)
                        + "; requested=" + bounds.toShortString() + "; HOME lease=none";
            });
        }, () -> {
            DesktopSelfTestCleanup.removeFixtureTasks();
            return "independent probe tasks removed";
        });
    }

    private static int parseTaskId(final String output) throws IOException {
        for (final String line : output.split("\\r?\\n")) {
            if (line.startsWith("task-display-area-launch=")) {
                try {
                    final int id = Integer.parseInt(line.substring(line.indexOf('=') + 1));
                    if (id >= 0) return id;
                } catch (NumberFormatException ignored) { }
            }
        }
        throw new IOException("independent launch did not return a task: " + output);
    }

    private static String awaitWindow(final int displayId, final int taskId, final Rect screen)
            throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        String last = "absent";
        do {
            DesktopSelfTestRunState.checkpoint();
            final TaskRepository.Snapshot snapshot = TaskRepository.loadAllNow();
            if (!snapshot.available) throw new IOException(snapshot.error);
            for (final TaskRepository.TaskEntry task : snapshot.tasks) {
                if (task.taskId != taskId) continue;
                last = "task=" + taskId + "/display=" + task.displayId + "/mode=" + task.windowingMode
                        + "/visible=" + task.visible + "/bounds=" + task.bounds.toShortString();
                if (isVisibleFreeform(task, displayId, screen)) return last;
            }
            BoundedStateAwaiter.pause(BoundedStateAwaiter.Reason.TASK_WINDOWING_MODE, POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("independent window did not become visible freeform: " + last);
    }

    static boolean isVisibleFreeform(final TaskRepository.TaskEntry task,
            final int displayId, final Rect screen) {
        return task != null && task.displayId == displayId && task.visible
                && "freeform".equals(task.windowingMode) && !task.bounds.isEmpty()
                && screen.contains(task.bounds) && !screen.equals(task.bounds);
    }
}
