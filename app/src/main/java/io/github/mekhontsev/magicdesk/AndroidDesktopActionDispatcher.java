package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide asynchronous adapter for UI-triggered semantic Android actions. */
final class AndroidDesktopActionDispatcher {
    interface Callback {
        void onComplete(DesktopAutomationResult result);
    }

    private interface GatewayOperation {
        DesktopAutomationResult execute(AndroidIntegrationGateway gateway)
                throws Exception;
    }

    private static final ExecutorService WORKER =
            Executors.newFixedThreadPool(2, runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskAndroidAction");
                thread.setDaemon(true);
                return thread;
            });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AndroidDesktopActionDispatcher() {
    }

    static ContentRequestScope createContentScope() {
        return new ContentRequestScope(WORKER);
    }

    static void dispatch(
            final Context context,
            final AndroidDesktopAction action,
            final int displayId,
            final Callback callback) {
        dispatch(
                context,
                gateway -> gateway.execute(action, displayId),
                callback);
    }

    static void deliverContent(
            final ContentRequestScope owner,
            final Context context,
            final AndroidContentPayload content,
            final AppLaunchTarget target,
            final DesktopLaunchPresentation presentation,
            final int displayId,
            final Runnable release,
            final Callback callback) {
        final Context appContext = context.getApplicationContext();
        owner.submit(cancelled -> new AndroidIntegrationGateway(appContext)
                .deliverContent(content, target, presentation, displayId), release)
                .thenAccept(completion -> {
                    if (completion.value != null && completion.failure != null) {
                        CompatibilityDiagnostics.record(
                                "CONTENT-GRANT-RELEASE-001",
                                "Could not release incoming content permissions",
                                "deliveryCompleted=true", completion.failure);
                    }
                    // Delivery is already decided by the gateway. A grant-release
                    // error must not tell the user to repeat a committed action.
                    final DesktopAutomationResult completed = completion.value != null ? completion.value
                            : DesktopAutomationResult.failure(
                                    DesktopAutomationErrorCode.ACTION_FAILED,
                                    ShellAccess.usefulMessage(completion.failure), false);
                    if (callback != null) {
                        owner.deliver(MAIN::post, () -> callback.onComplete(completed));
                    }
                });
    }

    static void shareContent(
            final Context context,
            final AndroidContentPayload content,
            final int displayId,
            final Callback callback) {
        dispatch(
                context,
                gateway -> gateway.shareContent(content, displayId),
                callback);
    }

    private static void dispatch(
            final Context context,
            final GatewayOperation operation,
            final Callback callback) {
        final Context appContext = context.getApplicationContext();
        WORKER.execute(() -> {
            final DesktopAutomationResult result;
            try {
                result = operation.execute(
                        new AndroidIntegrationGateway(appContext));
            } catch (Exception error) {
                final DesktopAutomationResult failure =
                        DesktopAutomationResult.failure(
                                DesktopAutomationErrorCode.ACTION_FAILED,
                                ShellAccess.usefulMessage(error),
                                false);
                post(callback, failure);
                return;
            }
            post(callback, result);
        });
    }

    private static void post(
            final Callback callback,
            final DesktopAutomationResult result) {
        if (callback != null) {
            MAIN.post(() -> callback.onComplete(result));
        }
    }
}
