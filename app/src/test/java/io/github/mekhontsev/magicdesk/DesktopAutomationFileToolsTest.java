package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.json.JSONObject;
import org.junit.Test;

public final class DesktopAutomationFileToolsTest {
    @Test
    public void pathsArePassedToTheFileOwnerWithoutTrimming() throws Exception {
        final String path = "/Desktop/file ";
        assertEquals(path, DesktopAutomationFileTools.required(
                new JSONObject().put("path", path), "path"));
    }

    @Test
    public void fileArgumentsMustBeNonEmptyStrings() throws Exception {
        for (final Object value : new Object[] {"", 42, true, JSONObject.NULL}) {
            final JSONObject args = new JSONObject().put("path", value);
            assertThrows(IllegalArgumentException.class,
                    () -> DesktopAutomationFileTools.required(args, "path"));
        }
    }
}
