package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public final class AndroidDesktopActionCatalogTest {
    @Test
    public void catalogDescribesParametersAndResultLifecycle() {
        final JSONArray actions = AndroidDesktopActionCatalog.describe();
        final Map<String, JSONObject> byId = new HashMap<>();
        for (int index = 0; index < actions.length(); index++) {
            final JSONObject action = actions.optJSONObject(index);
            byId.put(action.optString("id"), action);
        }

        assertEquals(AndroidDesktopActionCatalog.ids().length, byId.size());
        assertTrue(byId.get("open-document")
                .optBoolean("returnsActivityResult"));
        assertTrue(byId.get("create-document")
                .optBoolean("returnsActivityResult"));
        assertFalse(byId.get("app-details")
                .optBoolean("returnsActivityResult"));
        assertFalse(byId.get("sound-settings")
                .optBoolean("returnsActivityResult"));
        assertEquals("package", byId.get("app-details")
                .optJSONArray("requiredParameters").optString(0));
        assertTrue(contains(
                byId.get("open-document")
                        .optJSONArray("presentationParameters"),
                "instance"));
    }

    private static boolean contains(
            final JSONArray values,
            final String expected) {
        for (int index = 0; values != null && index < values.length(); index++) {
            if (expected.equals(values.optString(index))) {
                return true;
            }
        }
        return false;
    }
}
