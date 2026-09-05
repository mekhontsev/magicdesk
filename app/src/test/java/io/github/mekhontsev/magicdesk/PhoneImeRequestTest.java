package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PhoneImeRequestTest {
    @Test
    public void waitsForBothWindowFocusAndAnInputConnection() {
        final PhoneImeRequest request = new PhoneImeRequest();
        request.begin();
        assertFalse(request.takeShowRequest(true));
        request.openConnection();
        assertFalse(request.takeShowRequest(false));
        assertTrue(request.takeShowRequest(true));
    }

    @Test
    public void repeatedFocusAndConnectionCallbacksDoNotShowAgain() {
        final PhoneImeRequest request = showingRequest();
        assertFalse(request.takeShowRequest(false));
        assertFalse(request.takeShowRequest(true));
        request.openConnection();
        assertFalse(request.takeShowRequest(true));
    }

    @Test
    public void repeatedOpenRetainsAPendingConnection() {
        final PhoneImeRequest request = new PhoneImeRequest();
        request.begin();
        final long connection = request.openConnection();
        request.begin();
        assertTrue(request.accepts(connection));
        assertTrue(request.takeShowRequest(true));
    }

    @Test
    public void repeatedOpenDoesNotRestartAShownKeyboard() {
        final PhoneImeRequest request = showingRequest();
        final long connection = request.currentConnection();
        request.wasDismissed(true);
        request.begin();
        assertTrue(request.accepts(connection));
        assertFalse(request.takeShowRequest(true));
        assertTrue(request.wasDismissed(false));
    }

    @Test
    public void hiddenInsetsDoNotCancelAPendingShow() {
        final PhoneImeRequest request = new PhoneImeRequest();
        request.begin();
        assertFalse(request.wasDismissed(false));
        request.openConnection();
        assertTrue(request.takeShowRequest(true));
        assertFalse(request.wasDismissed(false));
        assertTrue(request.isRequested());
    }

    @Test
    public void onlyAnObservedShownKeyboardCanBeDismissed() {
        final PhoneImeRequest request = showingRequest();
        assertFalse(request.wasDismissed(true));
        assertTrue(request.wasDismissed(false));
        request.cancel();
        assertFalse(request.wasDismissed(false));
    }

    @Test
    public void unrelatedImeVisibilityDoesNotBecomeAnOwnedRequest() {
        final PhoneImeRequest request = new PhoneImeRequest();
        assertFalse(request.wasDismissed(true));
        request.begin();
        assertFalse(request.wasDismissed(true));
        request.openConnection();
        assertTrue(request.takeShowRequest(true));
        assertFalse(request.wasDismissed(false));
    }

    @Test
    public void cancelDuringOpeningRejectsTheQueuedShowAndInput() {
        final PhoneImeRequest request = new PhoneImeRequest();
        request.begin();
        final long connection = request.openConnection();
        request.cancel();
        assertFalse(request.isRequested());
        assertFalse(request.accepts(connection));
        assertFalse(request.takeShowRequest(true));
        assertEquals(0, request.openConnection());
    }

    @Test
    public void replacedConnectionCannotTypeOrCloseTheNewConnection() {
        final PhoneImeRequest request = showingRequest();
        final long oldConnection = request.currentConnection();
        final long newConnection = request.openConnection();
        assertFalse(request.accepts(oldConnection));
        request.closeConnection(oldConnection);
        assertTrue(request.accepts(newConnection));
    }

    @Test
    public void closedConnectionCannotTypeEvenBeforeTheRequestEnds() {
        final PhoneImeRequest request = showingRequest();
        final long connection = request.currentConnection();
        request.closeConnection(connection);
        assertFalse(request.accepts(connection));
        assertFalse(request.accepts(0));
    }

    @Test
    public void reopeningStartsFreshAndRejectsThePreviousKeyboard() {
        final PhoneImeRequest request = showingRequest();
        final long oldConnection = request.currentConnection();
        request.wasDismissed(true);
        request.cancel();
        request.cancel();
        request.begin();
        final long newConnection = request.openConnection();
        request.closeConnection(oldConnection);
        assertFalse(request.accepts(oldConnection));
        assertTrue(request.accepts(newConnection));
        assertFalse(request.wasDismissed(false));
        assertTrue(request.takeShowRequest(true));
    }

    private static PhoneImeRequest showingRequest() {
        final PhoneImeRequest request = new PhoneImeRequest();
        request.begin();
        request.openConnection();
        assertTrue(request.takeShowRequest(true));
        return request;
    }
}
