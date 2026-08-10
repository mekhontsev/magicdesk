package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

public final class DesktopStateStoreTest {
    @Test
    public void stateRoundTripPreservesDesktopConfiguration() throws Exception {
        final DesktopStateStore.State source = new DesktopStateStore.State();
        source.content.shortcuts.add(AppLaunchTarget.explicit(
                "example.application",
                "example.application.MainActivity",
                "android.intent.action.MAIN"));
        source.content.workspaceTarget =
                AppLaunchTarget.packageDefault("example.workspace");
        source.taskbarPackages.add("example.application");

        final DisplayProfileStore.Profile profile =
                new DisplayProfileStore.Profile("monitor:primary");
        profile.dpi = 160;
        profile.dpiExplicit = true;
        profile.workspaceBounds = new Rect();
        profile.workspaceBounds.left = 10;
        profile.workspaceBounds.top = 20;
        profile.workspaceBounds.right = 1010;
        profile.workspaceBounds.bottom = 720;
        profile.workspaceBoundsTarget = "example.workspace";
        profile.placements.put(
                "app:example.application",
                new DesktopPlacement(2, 3, 1, 2));
        source.displayProfiles.put(profile.monitorKey, profile);
        source.displayAliases.put("display:3", profile.monitorKey);

        final DesktopStateStore.State decoded = DesktopStateStore.decode(
                DesktopStateStore.encode(source));

        assertEquals(source.content.shortcuts, decoded.content.shortcuts);
        assertEquals(
                source.content.workspaceTarget,
                decoded.content.workspaceTarget);
        assertEquals(source.taskbarPackages, decoded.taskbarPackages);
        assertEquals("monitor:primary", decoded.displayAliases.get("display:3"));
        final DisplayProfileStore.Profile decodedProfile =
                decoded.displayProfiles.get("monitor:primary");
        assertEquals(160, decodedProfile.dpi);
        assertTrue(decodedProfile.dpiExplicit);
        assertEquals(10, decodedProfile.workspaceBounds.left);
        assertEquals(20, decodedProfile.workspaceBounds.top);
        assertEquals(1010, decodedProfile.workspaceBounds.right);
        assertEquals(720, decodedProfile.workspaceBounds.bottom);
        assertEquals(
                "example.workspace", decodedProfile.workspaceBoundsTarget);
        assertEquals(
                new DesktopPlacement(2, 3, 1, 2),
                decodedProfile.placements.get("app:example.application"));
    }

    @Test
    public void invalidEntriesAreIgnored() throws Exception {
        final DesktopStateStore.State decoded = DesktopStateStore.decode(
                "{\"format\":1,"
                        + "\"shortcuts\":[{\"package\":\"not a package\"}],"
                        + "\"taskbar\":[\"\",\"bad package\"],"
                        + "\"displayProfiles\":{\"wrong-key\":{"
                        + "\"monitor\":\"monitor:primary\"}},"
                        + "\"displayAliases\":{\"display:3\":\"\"}}" );

        assertTrue(decoded.content.shortcuts.isEmpty());
        assertNull(decoded.content.workspaceTarget);
        assertTrue(decoded.taskbarPackages.isEmpty());
        assertFalse(decoded.displayProfiles.containsKey("wrong-key"));
        assertTrue(decoded.displayAliases.isEmpty());
    }
}
