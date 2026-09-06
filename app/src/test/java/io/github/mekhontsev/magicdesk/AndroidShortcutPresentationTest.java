package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONObject;
import org.junit.Test;

public final class AndroidShortcutPresentationTest {
    @Test
    public void existingModeOnlyPresentationsRemainSupported() {
        for (final DesktopLaunchMode mode : DesktopLaunchMode.values()) {
            AndroidIntegrationGateway.requireShortcutPresentation(
                    DesktopLaunchPresentation.forMode(mode));
        }
    }

    @Test
    public void unsupportedTaskInstanceAndGeometryRequestsFailBeforeDispatch() throws Exception {
        for (final JSONObject args : new JSONObject[] {
                new JSONObject().put("instance", "new"),
                new JSONObject().put("mode", "windowed").put("bounds",
                        new JSONObject().put("x", 100).put("y", 100)
                                .put("width", 5000).put("height", 5000)),
                new JSONObject().put("mode", "fullscreen").put("preferredTaskId", 42)}) {
            final var presentation = AndroidIntegrationRequest.parsePresentation(
                    args, DesktopTaskInstancePolicy.REUSE_EXISTING);
            assertThrows(IllegalArgumentException.class,
                    () -> AndroidIntegrationGateway.requireShortcutPresentation(presentation));
        }
    }

    @Test
    public void gatewayValidatesBeforeCallingTheShortcutBridge() throws Exception {
        final String source = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/AndroidIntegrationGateway.java"));
        final String execute = source.substring(source.indexOf(
                "private DesktopAutomationResult executeShortcut("), source.indexOf(
                "static void requireShortcutPresentation("));
        final int validate = execute.indexOf("requireShortcutPresentation(action.presentation)");
        assertTrue(validate >= 0);
        assertTrue(validate < execute.indexOf("DesktopRuntimeBridge.invokeAppActionObserved("));
        final String invoke = source.substring(source.indexOf(
                "DesktopAutomationResult invokeAppAction("), source.indexOf(
                "DesktopAutomationResult listNotifications("));
        assertTrue(invoke.contains("AndroidIntegrationRequest.parsePresentation("));
        assertTrue(invoke.contains("optionalDisplayId(args)"));
    }
}
