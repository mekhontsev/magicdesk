package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public final class DesktopAutomationCaptureTest {
    @Test public void onlyDisplayAndOptionalRectangleSelectTheCapture() throws Exception {
        assertEquals(new DisplayCaptureRequest(4, null),
                DesktopAutomationCapture.request(new JSONObject(), () -> 4));
        assertEquals(new DisplayCaptureRequest(0, null),
                DesktopAutomationCapture.request(new JSONObject("{\"displayId\":0}"), () -> 4));
        assertEquals(new DisplayCaptureRequest(3, new DisplayCaptureRequest.Region(10, 20, 110, 220)),
                DesktopAutomationCapture.request(new JSONObject("""
                        {"displayId":3,"region":{"left":10,"top":20,"right":110,"bottom":220}}
                        """), () -> 0));
    }

    @Test public void explicitDisplayDoesNotResolveAnAmbiguousDefault() throws Exception {
        final java.util.function.IntSupplier ambiguous = () -> {
            throw new IllegalStateException("several Desktops");
        };
        assertEquals(new DisplayCaptureRequest(0, null), DesktopAutomationCapture.request(
                new JSONObject().put("displayId", 0), ambiguous));
        assertEquals(new DisplayCaptureRequest(49, null), DesktopAutomationCapture.request(
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
