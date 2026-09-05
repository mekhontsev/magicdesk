package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.inputmethod.InputConnection;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class LocalTextInputSessionTest {
    @Test
    public void forwardsTextAndCompositionToTheCapturedConnection() {
        final Target target = new Target();
        final LocalTextInputSession session = target.session();
        assertTrue(session.dispatch(PlatformTextInputDriver.SET_COMPOSING_TEXT, "draft", 1, 0, 0));
        assertTrue(session.dispatch(PlatformTextInputDriver.SET_COMPOSING_REGION, "", 2, 4, 0));
        assertTrue(session.dispatch(PlatformTextInputDriver.FINISH_COMPOSING, "", 0, 0, 0));
        assertTrue(session.dispatch(PlatformTextInputDriver.COMMIT_TEXT, "done", 1, 0, 0));
        assertTrue(session.dispatch(PlatformTextInputDriver.DELETE_SURROUNDING, "", 3, 2, 0));
        assertEquals(Arrays.asList("setComposingText[draft, 1]", "setComposingRegion[2, 4]",
                "finishComposingText[]", "commitText[done, 1]", "deleteSurroundingText[3, 2]"),
                target.calls);
    }

    @Test
    public void normalizesNullText() {
        final Target target = new Target();
        assertTrue(target.session().dispatch(PlatformTextInputDriver.COMMIT_TEXT, null, 1, 0, 0));
        assertEquals(Arrays.asList("commitText[, 1]"), target.calls);
    }

    @Test
    public void unavailableEditorClosesTheSessionPermanently() {
        final Target target = new Target();
        final LocalTextInputSession session = target.session();
        target.available = false;
        assertFalse(session.dispatch(PlatformTextInputDriver.COMMIT_TEXT, "lost", 1, 0, 0));
        target.available = true;
        assertFalse(session.dispatch(PlatformTextInputDriver.COMMIT_TEXT, "new", 1, 0, 0));
        session.close();
        assertEquals(Arrays.asList("closeConnection[]"), target.calls);
    }

    @Test
    public void explicitCloseIsIdempotentAndRejectsLateImeOperations() {
        final Target target = new Target();
        final LocalTextInputSession session = target.session();
        session.close();
        session.close();
        assertFalse(session.dispatch(PlatformTextInputDriver.SET_COMPOSING_TEXT, "late", 1, 0, 0));
        assertEquals(Arrays.asList("closeConnection[]"), target.calls);
    }

    @Test
    public void rejectedOperationDoesNotReplaceOrCloseTheConnection() {
        final Target target = new Target();
        final LocalTextInputSession session = target.session();
        target.accept = false;
        assertFalse(session.dispatch(PlatformTextInputDriver.COMMIT_TEXT, "first", 1, 0, 0));
        target.accept = true;
        assertTrue(session.dispatch(PlatformTextInputDriver.COMMIT_TEXT, "second", 1, 0, 0));
        assertEquals(Arrays.asList("commitText[first, 1]", "commitText[second, 1]"), target.calls);
    }

    @Test
    public void unknownOperationDoesNotTouchTheEditor() {
        final Target target = new Target();
        assertFalse(target.session().dispatch(-1, "", 0, 0, 0));
        assertTrue(target.calls.isEmpty());
    }

    private static final class Target {
        final List<String> calls = new ArrayList<>();
        boolean available = true;
        boolean accept = true;

        LocalTextInputSession session() {
            final InputConnection connection = (InputConnection) Proxy.newProxyInstance(
                    InputConnection.class.getClassLoader(), new Class<?>[]{InputConnection.class},
                    (proxy, method, args) -> {
                        calls.add(method.getName() + Arrays.toString(
                                args == null ? new Object[0] : args));
                        return method.getReturnType() == boolean.class ? accept : null;
                    });
            return new LocalTextInputSession(connection, () -> available);
        }
    }
}
