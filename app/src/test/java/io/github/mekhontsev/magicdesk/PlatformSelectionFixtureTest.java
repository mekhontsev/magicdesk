package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class PlatformSelectionFixtureTest {
    @Test
    public void knownDeviceFixturesResolveDeterministically()
            throws Exception {
        final JSONObject root = fixture();
        assertEquals(1, root.getInt("schemaVersion"));
        final JSONArray fixtures = root.getJSONArray("fixtures");
        for (int index = 0; index < fixtures.length(); index++) {
            final JSONObject value = fixtures.getJSONObject(index);
            final PlatformDevice device = new PlatformDevice(
                    value.getString("manufacturer"),
                    value.getString("brand"),
                    value.getString("model"),
                    value.getString("device"),
                    value.getString("product"),
                    value.getString("fingerprint"),
                    value.getInt("sdk"));
            final PlatformDriver platform = PlatformDrivers.resolve(
                    device,
                    value.getString("override"),
                    value.getBoolean("nubiaFirmwareAvailable"));
            final String fixtureName = value.getString("name");

            assertEquals(fixtureName,
                    value.getString("platform"), platform.id());
            assertEquals(fixtureName,
                    value.getString("projectionProvider"),
                    platform.selection()
                            .provider(PlatformComponent.PROJECTION).id);
        }
    }

    private static JSONObject fixture() throws Exception {
        try (InputStream stream = PlatformSelectionFixtureTest.class
                .getClassLoader()
                .getResourceAsStream(
                        "compatibility/platform-selection.json")) {
            if (stream == null) {
                throw new IllegalStateException("platform fixture missing");
            }
            return new JSONObject(new String(
                    stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
