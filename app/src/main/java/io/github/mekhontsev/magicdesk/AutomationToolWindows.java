package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** JSON adapter for the same typed placement service used by the phone UI. */
final class AutomationToolWindows {
    private AutomationToolWindows() { }

    static DesktopAutomationResult open(final Intent intent, final JSONObject args) {
        try {
            final ToolLaunchTarget target = ToolLaunchTarget.resolve(args.optString("placement", "auto"),
                    args.has("displayId") ? AutomationJsonArguments.requiredInt(args, "displayId") : -1,
                    DesktopRuntimeBridge.workspaceDisplayIds());
            final Context context = MagicDeskApplication.applicationContext();
            final CountDownLatch completed = new CountDownLatch(1);
            final Throwable[] failure = new Throwable[1];
            final BuiltInWindowLauncher.Callback callback = error -> {
                failure[0] = error;
                completed.countDown();
            };
            final String uniqueId = args.has("uniqueId") ? args.getString("uniqueId") : null;
            if (intent.getComponent() != null && CommandConsoleActivity.class.getName()
                    .equals(intent.getComponent().getClassName())) {
                TerminalSessions.open(context, intent, target, uniqueId, callback);
            } else ToolApplications.open(context, intent, target, uniqueId, callback);
            // Bound the launch-completion callback; there is no display/task polling here.
            if (!completed.await(20_000L, TimeUnit.MILLISECONDS)) {
                return DesktopAutomationResult.failure(DesktopAutomationErrorCode.TIMEOUT,
                        "application launch acknowledgement timed out", true);
            }
            if (failure[0] != null) {
                return DesktopAutomationResult.failure(DesktopAutomationErrorCode.ACTION_FAILED,
                        ShellAccess.usefulMessage(failure[0]), true);
            }
            return DesktopAutomationResult.success("application launch accepted", new JSONObject()
                    .put("accepted", true).put("displayId", target.displayId)
                    .put("placement", target.desktop ? "desktop" : "display"));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return DesktopAutomationResult.failure(DesktopAutomationErrorCode.ACTION_FAILED,
                    "application launch interrupted", true);
        } catch (IllegalArgumentException | JSONException error) {
            return DesktopAutomationResult.failure(DesktopAutomationErrorCode.INVALID_ARGUMENT,
                    ShellAccess.usefulMessage(error), false);
        } catch (RuntimeException error) {
            return DesktopAutomationResult.failure(DesktopAutomationErrorCode.ACTION_FAILED,
                    ShellAccess.usefulMessage(error), true);
        }
    }
}
