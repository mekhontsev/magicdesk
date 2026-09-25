package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import io.github.mekhontsev.magicdesk.x11.X11WindowInspection;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public final class AutomationGraphicsInspectionTest {
    private static GraphicalSessions.Window window(long id, long parent) {
        return new GraphicalSessions.Window(id, "", true, "", "application",
                new io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout(parent, 100, 100,
                        io.github.mekhontsev.magicdesk.hosted.HostedWindowConstraints.NONE), null);
    }
    @Test public void familySelectionIsBoundedAndKeepsParentageAcrossCatalogOrder() {
        var catalog = List.of(window(4, 3), window(3, 2), window(2, 1), window(1, 0), window(5, 0));
        var all = AutomationGraphicsInspection.selectFamily(catalog, 1, 16);
        assertEquals(List.of(1L, 2L, 3L, 4L), List.copyOf(all.parents().keySet()));
        assertEquals(Long.valueOf(3), all.parents().get(4L));
        assertFalse(all.truncated());
        var bounded = AutomationGraphicsInspection.selectFamily(catalog, 1, 2);
        assertEquals(2, bounded.parents().size());
        assertTrue(bounded.truncated());
        var huge = new java.util.ArrayList<GraphicalSessions.Window>();
        for (int i = 5000; i > 0; --i) huge.add(window(i, i - 1));
        assertTrue(AutomationGraphicsInspection.selectFamily(huge, 1, 256).truncated());
    }

    @Test public void expirationCancelsPendingReadsAndPreventsLateNativeRequests() {
        var requests = new AutomationGraphicsInspection.Requests();
        var first = new java.util.concurrent.CompletableFuture<String>();
        assertSame(first, requests.start(() -> first));
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        first.whenComplete((value, failure) -> requests.start(() -> {
            attempts.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture("late");
        }));
        requests.close();
        assertTrue(first.isCancelled());
        assertTrue(requests.start(() -> {
            attempts.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture("late");
        }).isCompletedExceptionally());
        assertEquals(0, attempts.get());
        requests.close();
    }

    @Test public void familyKeepsNativeCoordinatesAndActualFocusChild() throws Exception {
        var bounds = new X11WindowInspection.Bounds(-10, 20, 300, 400);
        var dialog = new X11WindowInspection.Node(10, 1, 3, 50, "Save", X11WindowInspection.Type.DIALOG,
                bounds, true, true, false, false, true);
        var child = new X11WindowInspection.Node(11, 10, 0, 0, "", X11WindowInspection.Type.UNKNOWN,
                bounds, false, false, true, false, false);
        var result = AutomationGraphicsInspection.family("test", new X11WindowInspection(3, true, false,
                new X11WindowInspection.Focus("window", 11), bounds, List.of(dialog, child)));
        var windows = result.getJSONArray("windows");
        assertFalse(windows.getJSONObject(0).getBoolean("focused"));
        assertTrue(windows.getJSONObject(1).getBoolean("focused"));
        assertEquals(3, windows.getJSONObject(0).getInt("transientFor"));
        assertEquals(1, windows.getJSONObject(0).getInt("parentId"));
        assertEquals(-10, windows.getJSONObject(0).getJSONObject("bounds").getInt("left"));
        assertEquals("x11_root", result.getString("coordinateSpace"));
        assertFalse(result.has("taskId"));
    }

    @Test public void missingAndTruncatedAreExplicitWithoutInventingFocus() throws Exception {
        var result = AutomationGraphicsInspection.family("test", new X11WindowInspection(3, false, false,
                new X11WindowInspection.Focus("unknown", 0), new X11WindowInspection.Bounds(0, 0, 800, 600), List.of()));
        assertFalse(result.getBoolean("found"));
        assertEquals("unknown", result.getJSONObject("focus").getString("kind"));
        assertEquals(0, result.getJSONArray("windows").length());
    }

    @Test public void catalogIsDiscoverableAndRejectsExtraTargetKinds() throws Exception {
        var catalog = AutomationCommandCatalog.create();
        JSONObject schema = null;
        for (int i = 0; i < catalog.length(); i++) if (catalog.getJSONObject(i).getString("name").equals("graphics.inspect_window"))
            schema = catalog.getJSONObject(i).getJSONObject("inputSchema");
        assertNotNull(schema);
        assertEquals(3, schema.getJSONObject("properties").length());
        AutomationCommandArguments.check("graphics.inspect_window", new JSONObject().put("sessionId", "test").put("windowId", 4294967295L));
        assertThrows(IllegalArgumentException.class, () -> AutomationCommandArguments.check("graphics.inspect_window",
                new JSONObject().put("sessionId", "test").put("windowId", 1).put("displayId", 0)));
    }
}
