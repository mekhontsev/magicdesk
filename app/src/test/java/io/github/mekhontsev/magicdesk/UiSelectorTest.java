package io.github.mekhontsev.magicdesk;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class UiSelectorTest {
    @Test public void matchesExactConjunctionAndStateChanges() throws Exception {
        final JSONObject criteria = new JSONObject().put("package", "test.app")
                .put("text", "Line 1\n\u0441\u0442\u0440\u043e\u043a\u0430 2").put("focused", true);
        final UiSelector selector = new UiSelector(criteria);
        assertTrue(selector.matches(new JSONObject(criteria.toString())));
        assertFalse(selector.matches(new JSONObject(criteria.toString()).put("focused", false)));
        assertFalse(selector.matches(new JSONObject(criteria.toString()).put("package", "other.app")));
        assertFalse(selector.matches(new JSONObject(criteria.toString()).put("text", JSONObject.NULL)));
        criteria.put("text", "changed after construction");
        assertFalse(selector.matches(criteria));
    }

    @Test public void validatesSelectorWithoutCoercion() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new UiSelector(new JSONObject()));
        assertThrows(IllegalArgumentException.class, () -> new UiSelector(new JSONObject().put("focused", "true")));
        assertThrows(IllegalArgumentException.class, () -> new UiSelector(new JSONObject().put("text", true)));
        assertThrows(IllegalArgumentException.class, () -> new UiSelector(new JSONObject().put("x", 100)));
        assertThrows(IllegalArgumentException.class, () -> new UiSelector(new JSONObject().put("regex", ".*")));
    }

    @Test public void incompleteTreeCanProvePresenceButNeverAbsence() {
        assertTrue(UiSelector.satisfied(true, 1, false, true));
        assertFalse(UiSelector.satisfied(true, 0, true, true));
        assertFalse(UiSelector.satisfied(false, 0, false, true));
        assertFalse(UiSelector.satisfied(false, 1, true, true));
        assertTrue(UiSelector.satisfied(false, 0, true, true));
        assertFalse(UiSelector.satisfied(true, 1, true, false));
        assertFalse(UiSelector.satisfied(false, 0, true, false));
    }

    @Test public void fullTextMatchCannotMistakePreviewForValue() throws Exception {
        final String text = "x".repeat(512) + "\n" + "y".repeat(4000);
        final JSONObject node = new JSONObject().put("text", text);
        assertFalse(new UiSelector(new JSONObject().put("text", text.substring(0, 512))).matches(node));
        assertTrue(new UiSelector(new JSONObject().put("text", text)).matches(node));
        assertThrows(IllegalArgumentException.class, () ->
                new UiSelector(new JSONObject().put("text", "x".repeat(32769))));
    }

    @Test public void redactedTextCannotProveAbsenceButDoesNotHideUnrelatedNodes() throws Exception {
        final JSONObject node = new JSONObject().put("password", true).put("package", "test.app")
                .put("resourceId", "test.app:id/password").put("text", JSONObject.NULL);
        assertTrue(new UiSelector(new JSONObject().put("text", "secret")).couldMatchRedacted(node));
        assertFalse(new UiSelector(new JSONObject().put("resourceId", "dialog")).couldMatchRedacted(node));
        assertFalse(new UiSelector(new JSONObject().put("package", "other.app")
                .put("text", "secret")).couldMatchRedacted(node));
    }

    @Test public void limitsRejectFractionalOverflowAndCoercedValues() throws Exception {
        assertEquals(5, UiSelector.integer(new JSONObject(), "count", 5, 0, 10));
        assertEquals(10, UiSelector.integer(new JSONObject().put("count", 10), "count", 5, 0, 10));
        for (Object value : new Object[] {-1, 11, 1.5, Long.MAX_VALUE, "3", JSONObject.NULL}) {
            assertThrows(IllegalArgumentException.class, () ->
                    UiSelector.integer(new JSONObject().put("count", value), "count", 5, 0, 10));
        }
    }
}
