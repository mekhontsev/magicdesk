package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.os.Bundle;

import org.junit.Test;

public final class FrameworkWindowingApiTest {
    @Test
    public void resolvesAndInvokesPrimitiveOperations() throws Exception {
        final FrameworkWindowingApi api = FrameworkWindowingApi.inspect(
                FakeToken.class, FakeTransaction.class);
        final FakeTransaction transaction = new FakeTransaction();
        final FakeToken token = new FakeToken();
        final FakeToken parent = new FakeToken();

        assertTrue(api.available());
        api.setWindowingMode(transaction, token, 5);
        api.reparent(transaction, token, parent, true);
        api.reorder(transaction, token, true, true);
        api.setHidden(transaction, token, true);

        assertEquals(5, transaction.mode);
        assertEquals(token, transaction.token);
        assertEquals(parent, transaction.parent);
        assertTrue(transaction.onTop);
        assertTrue(transaction.includingParents);
        assertTrue(transaction.hidden);
    }

    @Test
    public void missingRequiredPrimitiveRejectsProfile() {
        final FrameworkWindowingApi api = FrameworkWindowingApi.inspect(
                FakeToken.class, IncompleteTransaction.class);

        assertFalse(api.available());
        assertFalse(api.error().isEmpty());
    }

    public static final class FakeToken {
    }

    public static class FakeTransaction {
        int mode;
        FakeToken token;
        FakeToken parent;
        boolean onTop;
        boolean includingParents;
        boolean hidden;

        public void setWindowingMode(final FakeToken value, final int newMode) {
            token = value;
            mode = newMode;
        }

        public void setBounds(final FakeToken value, final Rect bounds) {
            token = value;
        }

        public void reorder(final FakeToken value, final boolean top) {
            token = value;
            onTop = top;
        }

        public void reorder(
                final FakeToken value,
                final boolean top,
                final boolean parents) {
            token = value;
            onTop = top;
            includingParents = parents;
        }

        public void reparent(
                final FakeToken value,
                final FakeToken newParent,
                final boolean top) {
            token = value;
            parent = newParent;
            onTop = top;
        }

        public void setFocusable(final FakeToken value, final boolean focusable) {
            token = value;
        }

        public void setForceTranslucent(
                final FakeToken value, final boolean translucent) {
            token = value;
        }

        public void setHidden(final FakeToken value, final boolean newHidden) {
            token = value;
            hidden = newHidden;
        }

        public void removeTask(final FakeToken value) {
            token = value;
        }

        public void startTask(final int taskId, final Bundle options) {
            mode = taskId;
        }
    }

    public static final class IncompleteTransaction {
        public void setWindowingMode(final FakeToken token, final int mode) {
        }
    }
}
