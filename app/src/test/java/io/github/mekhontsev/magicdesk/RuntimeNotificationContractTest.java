package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Keeps notification entry points outside Android's trampoline restriction. */
public final class RuntimeNotificationContractTest {
    @Test
    public void notificationEntryPointsLaunchActivitiesDirectly() throws Exception {
        final String source = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/"
                        + "MagicDeskRuntimeService.java"));

        assertTrue(source.contains("PendingIntent.getActivity("));
        assertTrue(source.contains("ControlActivity.createLaunchIntent(this)"));
        assertTrue(source.contains("MagicDeskTouchpadActivity.createLaunchIntent("));
        assertTrue(source.contains("options.setLaunchDisplayId("));
        assertTrue(source.contains("PendingIntent.FLAG_IMMUTABLE"));
        assertFalse(source.contains("PendingIntent.getForegroundService("));
        assertFalse(source.contains("PendingIntent.getService("));
        assertFalse(source.contains("PendingIntent.getBroadcast("));
    }
}
