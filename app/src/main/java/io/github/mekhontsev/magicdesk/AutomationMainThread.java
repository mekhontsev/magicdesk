package io.github.mekhontsev.magicdesk;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.json.JSONObject;

/** Bounded callback handoff, never a settling delay or an implicit cancellation of dispatched work. */
final class AutomationMainThread {
    static <T> CompletableFuture<T> submit(Callable<T> action) {
        var result = new CompletableFuture<T>();
        Runnable invoke = () -> {
            if (result.isDone()) return;
            try { result.complete(action.call()); }
            catch (Exception error) { result.completeExceptionally(error); }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) invoke.run();
        else if (!new Handler(Looper.getMainLooper()).post(invoke))
            result.completeExceptionally(new IllegalStateException("Main thread unavailable"));
        return result;
    }

    static DesktopAutomationResult await(CompletableFuture<JSONObject> result, String operation, boolean readOnly) {
        var completed = new CountDownLatch(1);
        result.whenComplete((value, error) -> completed.countDown());
        // EVENT_WAIT: command/snapshot callback; expiry leaves any dispatched mutation observable.
        var expired = AutomationCallbackWait.await(completed, 10_000, operation, readOnly, new JSONObject());
        if (expired != null) { result.cancel(false); return expired; }
        try { return DesktopAutomationResult.success("ok", result.join()); }
        catch (java.util.concurrent.CompletionException error) {
            var cause = error.getCause();
            return DesktopAutomationResult.failure(cause instanceof IllegalArgumentException
                    ? DesktopAutomationErrorCode.INVALID_ARGUMENT : DesktopAutomationErrorCode.ACTION_FAILED,
                    ShellAccess.usefulMessage(cause), false);
        }
    }

    static <T> T read(Callable<T> action) {
        var result = submit(action);
        var completed = new CountDownLatch(1);
        result.whenComplete((value, error) -> completed.countDown());
        // EVENT_WAIT: immutable UI-owned snapshot; timeout cancels a read that has not started.
        var expired = AutomationCallbackWait.await(completed, 5000, "graphical snapshot", true, new JSONObject());
        if (expired != null) { result.cancel(false); throw new IllegalStateException(expired.message); }
        return result.join();
    }
    private AutomationMainThread() { }
}
