package io.github.mekhontsev.magicdesk;

import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.*;

public final class AutomationCallbackWaitTest {
    @Test public void deadlineDoesNotCancelActionOrPermitUnsafeReplay() throws Exception {
        var callback = new CountDownLatch(1);
        var result = AutomationCallbackWait.await(callback, 0, "display creation", false, null);
        assertFalse(result.success);
        assertEquals(DesktopAutomationErrorCode.OUTCOME_UNKNOWN, result.errorCode);
        assertFalse(result.retryable);
        assertFalse(result.observation.getBoolean("completionConfirmed"));
        assertTrue(result.observation.getBoolean("operationMayContinue"));
        callback.countDown();
        assertNull(AutomationCallbackWait.await(callback, 0, "display creation", false, null));
    }

    @Test public void safeRemovalRetryRetainsExactIdentityWithoutMutatingCallerState() throws Exception {
        var identity = new JSONObject().put("displayId", 7).put("uniqueId", "owned");
        var result = AutomationCallbackWait.await(new CountDownLatch(1), 0, "display removal", true, identity);
        assertTrue(result.retryable);
        assertTrue(result.observation.getBoolean("safeToRetry"));
        assertEquals(7, result.observation.getInt("displayId"));
        assertEquals("owned", result.observation.getString("uniqueId"));
        assertFalse(identity.has("operationMayContinue"));
    }

    @Test public void interruptionDoesNotClaimCancellation() throws Exception {
        Thread.currentThread().interrupt();
        try {
            var result = AutomationCallbackWait.await(new CountDownLatch(1), 100, "launch", false, null);
            assertEquals(DesktopAutomationErrorCode.OUTCOME_UNKNOWN, result.errorCode);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(result.observation.getBoolean("operationMayContinue"));
            assertFalse(result.retryable);
        } finally { Thread.interrupted(); }
    }
}
