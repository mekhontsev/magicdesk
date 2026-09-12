package io.github.mekhontsev.magicdesk;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A callback deadline bounds observation, not the lifetime of a dispatched action. */
final class AutomationCallbackWait {
    private AutomationCallbackWait() { }

    /** Returns null when the callback arrived, otherwise an explicit uncertain outcome. */
    static DesktopAutomationResult await(CountDownLatch completed, long timeoutMillis,
            String operation, boolean safeToRetry, JSONObject observation) {
        try {
            if (completed.await(timeoutMillis, TimeUnit.MILLISECONDS)) return null;
            return DesktopAutomationResult.outcomeUnknown(
                    operation + " callback wait expired", safeToRetry, observation);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return DesktopAutomationResult.outcomeUnknown(
                    operation + " callback wait interrupted", safeToRetry, observation);
        }
    }
}
