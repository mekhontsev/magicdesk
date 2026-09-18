package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public final class DesktopAutomationCaptureTest {
    @Test public void defaultAndExplicitDisplayKeepTheirSourceCoordinates() throws Exception {
        assertEquals(new CaptureRequest(CaptureRequest.Target.DISPLAY, 4, null),
                DesktopAutomationCapture.request(new JSONObject(), () -> 4));
        assertEquals(new CaptureRequest(CaptureRequest.Target.DISPLAY, 0, null),
                DesktopAutomationCapture.request(new JSONObject("{\"displayId\":0}"), () -> 4));
        assertEquals(new CaptureRequest(CaptureRequest.Target.DISPLAY, 3, new CaptureRequest.Region(10, 20, 110, 220)),
                DesktopAutomationCapture.request(new JSONObject("""
                        {"displayId":3,"region":{"left":10,"top":20,"right":110,"bottom":220}}
                        """), () -> 0));
    }

    @Test public void taskIsAnAlternativeSourceAndDoesNotResolveDefaultDisplay() throws Exception {
        final java.util.function.IntSupplier forbidden = () -> { throw new AssertionError("display resolved for task"); };
        assertEquals(new CaptureRequest(CaptureRequest.Target.TASK, 23, null),
                DesktopAutomationCapture.request(new JSONObject().put("taskId", 23), forbidden));
        assertEquals(new CaptureRequest(CaptureRequest.Target.TASK, 23, new CaptureRequest.Region(1, 2, 10, 20)),
                DesktopAutomationCapture.request(new JSONObject("""
                        {"taskId":23,"region":{"left":1,"top":2,"right":10,"bottom":20}}
                        """), forbidden));
        assertThrows(IllegalArgumentException.class, () -> DesktopAutomationCapture.request(
                new JSONObject().put("taskId", 23).put("displayId", 0), forbidden));
        for (Object invalid : new Object[]{-1, 1.5, "23", JSONObject.NULL, true, 2147483648L}) {
            assertThrows(IllegalArgumentException.class, () -> DesktopAutomationCapture.request(
                    new JSONObject().put("taskId", invalid), forbidden));
        }
    }

    @Test public void taskMetadataUsesTheCapturedFrameNotDisplayGeometry() throws Exception {
        final var region = new CaptureRequest.Region(30, 40, 130, 90);
        final var request = new CaptureRequest(CaptureRequest.Target.TASK, 23, region);
        final var info = new TaskCapture.Info(23, 800, 600, 1600, 1200, 1, "example/.Main");
        final var data = DesktopAutomationCapture.imageMetadata(
                new CaptureService.Image(request, 800, 600, 1, region, new byte[]{1}, info));
        assertEquals("task", data.getString("sourceType"));
        assertEquals(23, data.getInt("taskId"));
        assertEquals(false, data.has("displayId"));
        assertEquals(false, data.has("displayWidth"));
        assertEquals(100, data.getInt("width"));
        assertEquals(50, data.getInt("height"));
        assertEquals(800, data.getInt("sourceWidth"));
        assertEquals(1600, data.getInt("taskWidth"));
        assertEquals(30, data.getJSONObject("sourceBounds").getInt("left"));
    }

    @Test public void explicitDisplayDoesNotResolveAnAmbiguousDefault() throws Exception {
        final java.util.function.IntSupplier ambiguous = () -> {
            throw new IllegalStateException("several Desktops");
        };
        assertEquals(new CaptureRequest(CaptureRequest.Target.DISPLAY, 0, null), DesktopAutomationCapture.request(
                new JSONObject().put("displayId", 0), ambiguous));
        assertEquals(new CaptureRequest(CaptureRequest.Target.DISPLAY, 49, null), DesktopAutomationCapture.request(
                new JSONObject().put("displayId", 49), ambiguous));
        assertThrows(IllegalStateException.class,
                () -> DesktopAutomationCapture.request(new JSONObject(), ambiguous));
    }

    @Test public void coordinatesAreExactIntegersWithAllFourEdgesRequired() throws Exception {
        for (final Object invalid : new Object[] {"10", 1.5, JSONObject.NULL, true, 2147483648L}) {
            for (final String key : new String[] {"left", "top", "right", "bottom"}) {
                final var region = new JSONObject("{\"left\":10,\"top\":20,\"right\":110,\"bottom\":220}");
                region.put(key, invalid);
                assertThrows(IllegalArgumentException.class, () -> DesktopAutomationCapture.request(
                        new JSONObject().put("region", region), () -> 0));
                region.remove(key);
                assertThrows(IllegalArgumentException.class, () -> DesktopAutomationCapture.request(
                        new JSONObject().put("region", region), () -> 0));
            }
            assertThrows(IllegalArgumentException.class, () -> DesktopAutomationCapture.request(
                    new JSONObject().put("displayId", invalid), () -> 0));
        }
        for (final String invalid : new String[] {"null", "[]", "42", "\"all\""}) {
            assertThrows(JSONException.class, () -> DesktopAutomationCapture.request(
                    new JSONObject("{\"region\":" + invalid + "}"), () -> 0));
        }
    }
}
