package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AndroidUiIdentityTest {
    @Test public void reusedRowsCannotSilentlyBecomeAnotherActionTarget() {
        final var identity = new AndroidUiIdentity("test", "Button", "id", "uid", 4, false, "Delete A", "A");
        assertTrue(identity.matches(identity));
        assertFalse(identity.matches(new AndroidUiIdentity("test", "Button", "id", "uid", 4, false, "Delete B", "A")));
        assertFalse(identity.matches(new AndroidUiIdentity("test", "Button", "id", "uid", 5, false, "Delete A", "A")));
        assertFalse(identity.matches(new AndroidUiIdentity("other", "Button", "id", "uid", 4, false, "Delete A", "A")));
        assertFalse(identity.matches(new AndroidUiIdentity("test", "Button", "id", "new", 4, false, "Delete A", "A")));
    }

    @Test public void editingTextDoesNotReplaceAnEditorIdentity() {
        final var identity = new AndroidUiIdentity("test", "Editor", "id", "uid", 4, true, "", "");
        assertTrue(identity.matches(new AndroidUiIdentity("test", "Editor", "id", "uid", 4, true, "new text", "")));
        assertFalse(identity.matches(new AndroidUiIdentity("test", "Editor", "id", "uid", 4, false, "", "")));
    }
}
