package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class AppProfileIdentityTest {
    private static final String PACKAGE = "example.application";

    @Test
    public void durableIdentitySeparatesProfilesAndRecycledUserIds() {
        final AppProfile personal = new AppProfile(0, 5);
        final AppProfile work = new AppProfile(10, 19);
        final AppProfile recreated = new AppProfile(10, 27);
        assertNotEquals(personal.application(PACKAGE), work.application(PACKAGE));
        assertNotEquals(work.application(PACKAGE), recreated.application(PACKAGE));
        assertEquals(work.application(PACKAGE),
                AppIdentity.fromPersistentKey(work.application(PACKAGE).persistentKey()));
        assertThrows(IllegalArgumentException.class,
                () -> work.application(PACKAGE).requireProfile(recreated));
        assertThrows(IllegalArgumentException.class,
                () -> work.application(PACKAGE).requireProfile(personal));
    }

    @Test
    public void missingAndMalformedProfileNeverBecomePersonalProfile() {
        for (final String key : new String[]{PACKAGE, "-1|" + PACKAGE,
                "01|" + PACKAGE, "+1|" + PACKAGE, "1|bad;package", "|" + PACKAGE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> AppIdentity.fromPersistentKey(key));
        }
        assertThrows(IllegalArgumentException.class, () -> new AppProfile(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new AppProfile(0, -1));
    }

    @Test
    public void launchIdentityRequiresActualTaskUser() {
        final LaunchActivityIdentity identity =
                LaunchActivityIdentity.packageScoped(10, PACKAGE, null);
        assertTrue(identity.matchesTask(task(10, 3)));
        assertFalse(identity.matchesTask(task(0, 3)));
        assertFalse(identity.matchesTask(task(AppProfile.UNKNOWN_USER_ID, 3)));
        assertThrows(IllegalArgumentException.class,
                () -> LaunchActivityIdentity.packageScoped(-1, PACKAGE, null));
    }

    @Test
    public void phoneTransferDoesNotReuseOrPreferAnotherProfilesTask() {
        final LaunchActivityIdentity identity =
                LaunchActivityIdentity.packageScoped(10, PACKAGE, null);
        final TaskRepository.TaskEntry work = task(10, 3);
        final TaskRepository.TaskEntry personalPhone = task(0, 0);
        assertSame(work, DisplayAppLauncher.selectTransfer(identity,
                new TaskRepository.Snapshot(List.of(work), List.of(personalPhone), true, ""), 0));
        assertNull(DisplayAppLauncher.selectTransfer(identity,
                new TaskRepository.Snapshot(List.of(task(0, 3)), true, ""), 0));
    }

    @Test
    public void catalogItemDoesNotMatchAnotherProfilesTask() {
        final AppItem app = new AppItem(new AppProfile(10, 19), "Example", PACKAGE,
                true, AppItem.FULLSCREEN_REASON_NONE, null,
                AppLaunchTarget.packageDefault(PACKAGE));
        assertTrue(app.matchesTask(task(10, 3)));
        assertFalse(app.matchesTask(task(0, 3)));
        assertFalse(app.matchesTask(task(-1, 3)));
        assertTrue(HomeRecentApps.select(List.of(task(0, 0)), List.of(app), "", 0).isEmpty());
    }

    @Test
    public void parkedTaskIdentityRetainsUser() {
        final var parked = new DesktopTaskParkingController.ParkedTask(
                41, 10, PACKAGE, false, true, null);
        final TaskRepository.TaskEntry work = task(10, 0);
        assertSame(work, DesktopTaskParkingController.findLiveTask(List.of(work), parked));
        assertNull(DesktopTaskParkingController.findLiveTask(List.of(task(0, 0)), parked));
        assertNull(DesktopTaskParkingController.findLiveTask(List.of(task(-1, 0)), parked));
    }

    @Test
    public void rawSnapshotUserIsUnknownWhenFrameworkMemberIsUnavailable() {
        assertEquals(10, HiddenTaskApi.getTaskUserId(new RawTask(10)));
        assertEquals(-1, HiddenTaskApi.getTaskUserId(new RawTask(-1)));
        assertEquals(-1, HiddenTaskApi.getTaskUserId(new Object()));
    }

    public static final class RawTask {
        public final int userId;

        RawTask(final int userId) {
            this.userId = userId;
        }
    }

    private static TaskRepository.TaskEntry task(final int userId, final int displayId) {
        return new TaskRepository.TaskEntry(41, 41, displayId, PACKAGE,
                PACKAGE + "/.Main", PACKAGE + "/.Main", "freeform", null,
                1, 160, false, true, true, userId);
    }
}
