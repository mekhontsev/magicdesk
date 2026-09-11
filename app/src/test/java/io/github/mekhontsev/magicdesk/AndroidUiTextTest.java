package io.github.mekhontsev.magicdesk;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AndroidUiTextTest {
    @Test public void pagesReconstructLongUnicodeTextWithoutLineBreakChanges() throws Exception {
        final String value = ("English\n\u0442\u0435\u043a\u0441\u0442 \ud83d\ude00\n").repeat(4000);
        final StringBuilder read = new StringBuilder();
        int offset = 0;
        do {
            final var captured = AndroidUiText.page(value, false, new JSONObject().put("offset", offset).put("limit", 513));
            final var page = new JSONObject(new String(captured.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(value.length(), page.getInt("totalLength"));
            assertFalse(page.getBoolean("redacted"));
            read.append(page.getString("text"));
            if (page.isNull("nextOffset")) break;
            offset = page.getInt("nextOffset");
        } while (true);
        assertEquals(value, read.toString());
    }

    @Test public void singleUnitLimitStillMakesUnicodeSafeProgress() throws Exception {
        final var page = AndroidUiText.page("\ud83d\ude00!", false, new JSONObject().put("limit", 1));
        assertEquals("\ud83d\ude00", page.getString("text"));
        assertEquals(2, page.getInt("nextOffset"));
        assertThrows(IllegalArgumentException.class, () -> AndroidUiText.page("\ud83d\ude00", false,
                new JSONObject().put("offset", 1)));
    }

    @Test public void passwordDoesNotDiscloseLengthOrPage() throws Exception {
        final var page = AndroidUiText.page("secret", true, new JSONObject().put("offset", 999));
        assertTrue(page.getBoolean("redacted"));
        assertTrue(page.isNull("text"));
        assertTrue(page.isNull("totalLength"));
        assertTrue(page.isNull("nextOffset"));
    }

    @Test public void validatesBoundsAndSupportsEmptyText() throws Exception {
        assertEquals("", AndroidUiText.page(null, false, new JSONObject()).getString("text"));
        assertThrows(IllegalArgumentException.class, () -> AndroidUiText.page("abc", false, new JSONObject().put("offset", 4)));
        for (Object invalid : new Object[] {-1, 0, 32769, 1.5, "3", JSONObject.NULL}) {
            assertThrows(IllegalArgumentException.class, () -> AndroidUiText.page("abc", false, new JSONObject().put("limit", invalid)));
        }
    }
}
