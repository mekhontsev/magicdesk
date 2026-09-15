package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;

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
            final JSONObject viewer = args.has("viewer") ? args.getJSONObject("viewer") : null;
            if (viewer != null && (intent.getComponent() == null || !DisplayViewerActivity.class.getName()
                    .equals(intent.getComponent().getClassName()))) {
                throw new IllegalArgumentException("viewer options require builtin=display_viewer");
            }
            if (intent.getComponent() != null && CommandConsoleActivity.class.getName()
                    .equals(intent.getComponent().getClassName())) {
                TerminalSessions.open(context, intent, target, uniqueId, callback);
            } else if (viewer != null) {
                final boolean output = switch (viewer.optString("mode", "mirror")) {
                    case "mirror" -> false;
                    case "output" -> true;
                    default -> throw new IllegalArgumentException("unknown Viewer mode");
                };
                DisplayPresentations.openViewer(context, target, uniqueId,
                        viewer.has("sourceDisplayId") ? AutomationJsonArguments.requiredInt(viewer, "sourceDisplayId") : null,
                        output, viewer.optBoolean("immersive", output), callback);
            } else ToolApplications.open(context, intent, target, uniqueId, callback);
            // Bound the launch-completion callback; there is no display/task polling here.
            final DesktopAutomationResult pending = AutomationCallbackWait.await(completed,
                    20_000L, viewer != null && viewer.has("sourceDisplayId")
                            ? "display viewer Surface attachment" : "application launch", false,
                    new JSONObject().put("displayId", target.displayId));
            if (pending != null) return pending;
            if (failure[0] != null) {
                return DesktopAutomationResult.failure(DesktopAutomationErrorCode.ACTION_FAILED,
                        ShellAccess.usefulMessage(failure[0]), true);
            }
            final JSONObject result = new JSONObject()
                    .put("accepted", true).put("displayId", target.displayId)
                    .put("placement", target.desktop ? "desktop" : "display");
            if (viewer != null) result.put("presentations", DisplayPresentations.snapshot());
            return DesktopAutomationResult.success("application launch accepted", result);
        } catch (IllegalArgumentException | JSONException error) {
            return DesktopAutomationResult.failure(DesktopAutomationErrorCode.INVALID_ARGUMENT,
                    ShellAccess.usefulMessage(error), false);
        } catch (RuntimeException error) {
            return DesktopAutomationResult.failure(DesktopAutomationErrorCode.ACTION_FAILED,
                    ShellAccess.usefulMessage(error), true);
        }
    }
}
