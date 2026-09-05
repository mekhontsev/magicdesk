package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public final class ShellDesktopSurfaceOrderTest {
    @Test
    public void everyPlaneOrderIncludesChromeAboveApplications() {
        final Map<String, Integer> applications = new LinkedHashMap<>();
        applications.put("idle", -3);
        applications.put("fullscreen-a", 1);
        applications.put("fullscreen-b", 4);
        final Map<String, Integer> composed =
                ShellDesktopSurfaceOrder.composeLayers(applications, "chrome");

        assertEquals(Integer.valueOf(Integer.MAX_VALUE), composed.get("chrome"));
        assertEquals(Integer.valueOf(-3), composed.get("idle"));
        assertEquals(Integer.valueOf(1), composed.get("fullscreen-a"));
        assertEquals(Integer.valueOf(4), composed.get("fullscreen-b"));
        assertFalse(applications.containsKey("chrome"));
    }

    @Test
    public void freeformOnlyWorkspaceStillCommitsChrome() {
        assertEquals(Collections.singletonMap("chrome", Integer.MAX_VALUE),
                ShellDesktopSurfaceOrder.composeLayers(
                        Collections.emptyMap(), "chrome"));
    }

    @Test
    public void staleChromeLayerCannotOverrideInvariant() {
        assertEquals(Collections.singletonMap("chrome", Integer.MAX_VALUE),
                ShellDesktopSurfaceOrder.composeLayers(
                        Collections.singletonMap("chrome", 0), "chrome"));
    }

    @Test
    public void cleanupWithoutChromePreservesRemainingPlaneOrder() {
        final Map<String, Integer> layers = Collections.singletonMap("plane", -1);
        assertEquals(layers, ShellDesktopSurfaceOrder.composeLayers(layers, null));
    }
}
