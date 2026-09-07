package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DisplayPowerCommandsTest {
    @Test
    public void powerCommandFixturesUseTheSameResolutionForGuardAndReport()
            throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "compatibility/display-power-commands.json")) {
            final JSONArray cases = new JSONArray(new String(
                    stream.readAllBytes(), StandardCharsets.UTF_8));
            for (int index = 0; index < cases.length(); index++) {
                final JSONObject entry = cases.getJSONObject(index);
                final JSONObject replies = entry.getJSONObject("replies");
                final String name = entry.getString("name");
                final DisplayPowerCommands.CommandReader reader = operation -> {
                    // Every probe is argument-free. In particular it cannot
                    // turn off display 0 while building a compatibility report.
                    assertTrue(operation.matches("help|power-(off|on|reset)"));
                    assertTrue(name, replies.opt(operation) instanceof String);
                    return (String) replies.opt(operation);
                };
                final String expected = entry.optString("restore", "");
                assertEquals(entry.getString("name"),
                        expected.isEmpty() ? null : expected,
                        DisplayPowerCommands.resolveRestoreOperation(reader));
                final StringBuilder report = report(reader);
                assertTrue(report.toString(), report.toString().contains(
                        "display.power_restore="
                                + (expected.isEmpty() ? "unavailable" : "declared")));
                assertTrue(report.toString(), report.toString().contains(
                        "display.power_off=" + entry.getString("powerOff")));
            }
        }
    }

    @Test
    public void advertisedCommandsNeedOnlyOneHelpQuery() throws Exception {
        final List<String> calls = new ArrayList<>();
        final DisplayPowerCommands.CommandReader reader = operation -> {
            calls.add(operation);
            return "power-off DISPLAY_ID\npower-on DISPLAY_ID\npower-reset DISPLAY_ID";
        };
        assertEquals("power-reset", DisplayPowerCommands.resolveRestoreOperation(reader));
        assertEquals(Arrays.asList("help"), calls);
        calls.clear();
        report(reader);
        assertEquals(Arrays.asList("help"), calls);
    }

    @Test
    public void commandNamesInProseOrPrefixesDoNotCountAsDeclarations() {
        assertFalse(DisplayPowerCommands.advertises(
                "  no power-reset support\npower-reset-extra DISPLAY_ID", "power-reset"));
        assertFalse(DisplayPowerCommands.advertises(null, "power-reset"));
        assertTrue(DisplayPowerCommands.advertises(
                "  power-on\tDISPLAY_ID", "power-on"));
    }

    @Test
    public void missingCommandsAreNotInferredFromEmptyHelp() throws Exception {
        assertNull(DisplayPowerCommands.resolveRestoreOperation(operation ->
                "help".equals(operation) ? "" : "Unknown command: " + operation));
    }

    @Test
    public void permissionFailureRemainsAnErrorRatherThanAnAbsentCommand() {
        final DisplayPowerCommands.CommandReader reader = operation ->
                "help".equals(operation) ? "power-off DISPLAY_ID"
                        : "SecurityException: permission denied";
        assertThrows(IOException.class,
                () -> DisplayPowerCommands.resolveRestoreOperation(reader));
        final StringBuilder report = report(reader);
        assertTrue(report.toString().contains("display.power_off=declared"));
        assertTrue(report.toString().contains("display.power_restore=error"));
        assertFalse(report.toString().contains("display.power_restore=unavailable"));
    }

    @Test
    public void failedHelpDoesNotClaimAnyCapability() {
        final StringBuilder report = report(operation -> {
            throw new IOException("command timeout");
        });
        assertTrue(report.toString().contains("display.power_off=error"));
        assertTrue(report.toString().contains("display.power_restore=error"));
    }

    @Test
    public void helperAcceptsOnlyRestoreOperations() {
        assertTrue(DisplayPowerCommands.isRestoreOperation("power-reset"));
        assertTrue(DisplayPowerCommands.isRestoreOperation("power-on"));
        assertFalse(DisplayPowerCommands.isRestoreOperation("power-off"));
        assertFalse(DisplayPowerCommands.isRestoreOperation("reset"));
        assertFalse(DisplayPowerCommands.isRestoreOperation(null));
    }

    private static StringBuilder report(final DisplayPowerCommands.CommandReader reader) {
        final StringBuilder report = new StringBuilder();
        DisplayPowerCommands.probe((key, state, detail) ->
                report.append(key).append('=').append(state)
                        .append(" | ").append(detail).append('\n'), reader);
        return report;
    }
}
