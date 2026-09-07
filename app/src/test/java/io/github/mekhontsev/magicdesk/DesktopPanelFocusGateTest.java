package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class DesktopPanelFocusGateTest {
    private final List<Boolean> mRequests = new ArrayList<>();
    private final List<TaskRepository.ActionCallback> mCallbacks = new ArrayList<>();
    private final List<String> mFailures = new ArrayList<>();
    private int mAttachments;
    private final DesktopPanelFocusGate mGate = new DesktopPanelFocusGate(
            (focusable, callback) -> {
                mRequests.add(focusable);
                mCallbacks.add(callback);
            }, () -> mAttachments++, mFailures::add);

    @Test
    public void nonFocusableChromeNeedsNoTransaction() {
        assertTrue(mGate.require(false));
        assertTrue(mRequests.isEmpty());
    }

    @Test
    public void panelAttachesAfterTaskFocusIsAcknowledged() {
        assertFalse(mGate.require(true));
        assertFalse(mGate.require(true));
        assertEquals(List.of(true), mRequests);
        assertEquals(0, mAttachments);
        complete(0, true);
        assertTrue(mGate.require(true));
        assertEquals(1, mAttachments);
        assertEquals(List.of(true), mRequests);
    }

    @Test
    public void closeQueuesReleaseImmediatelyAndIgnoresPendingEnable() {
        mGate.require(true);
        assertTrue(mGate.require(false));
        assertEquals(List.of(true, false), mRequests);
        complete(0, true);
        complete(1, true);
        assertEquals(0, mAttachments);
    }

    @Test
    public void reopenCannotUseAcknowledgementFromPreviousPanel() {
        mGate.require(true);
        mGate.require(false);
        mGate.require(true);
        complete(0, true);
        complete(1, true);
        assertEquals(0, mAttachments);
        complete(2, true);
        assertEquals(1, mAttachments);
    }

    @Test
    public void failedEnableDoesNotAttachAndStillAllowsRelease() {
        mGate.require(true);
        complete(0, false);
        assertEquals(0, mAttachments);
        assertEquals(List.of("failed"), mFailures);
        mGate.require(false);
        assertEquals(List.of(true, false), mRequests);
    }

    @Test
    public void detachedHostCannotAcknowledgeForItsReplacement() {
        mGate.require(true);
        mGate.require(false);
        mGate.reset();
        mGate.require(true);
        complete(0, true);
        complete(1, false);
        assertEquals(0, mAttachments);
        assertTrue(mFailures.isEmpty());
        complete(2, true);
        assertEquals(1, mAttachments);
    }

    private void complete(final int index, final boolean success) {
        mCallbacks.get(index).onComplete(new TaskRepository.ActionResult(
                success, success ? "ok" : "failed"));
    }
}
