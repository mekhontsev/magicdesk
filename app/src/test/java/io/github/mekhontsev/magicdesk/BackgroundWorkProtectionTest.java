package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

public final class BackgroundWorkProtectionTest {
    private static final class Platform implements PlatformBackgroundWork {
        final Map<Integer, Integer> active = new HashMap<>();
        int failedUid = -1;
        int refreshed;
        @Override public Session begin(int uid) {
            if (uid == failedUid) throw new IllegalStateException("denied");
            active.merge(uid, 1, Integer::sum);
            return new Session() {
                @Override public void refresh() { refreshed++; }
                @Override public void close() { active.compute(uid, (key, count) -> count == 1 ? null : count - 1); }
            };
        }
    }

    @Test public void overlappingOwnersCannotReleaseEachOthersProtection() throws Exception {
        Platform platform = new Platform();
        BackgroundWorkProtection protection = new BackgroundWorkProtection(platform);
        Object desktop = new Object(), automation = new Object();
        protection.retain(desktop, Set.of(10123, 10234));
        protection.retain(automation, Set.of(10123, 10345));
        assertEquals(Integer.valueOf(1), platform.active.get(10123));
        protection.release(desktop);
        assertEquals(Set.of(10123, 10345), platform.active.keySet());
        protection.refresh();
        assertEquals(2, platform.refreshed);
        protection.release(automation);
        protection.release(automation);
        assertTrue(platform.active.isEmpty());
    }

    @Test public void hostIsProtectedWithoutHomeAndAppsAreRetainedForWorkLifetime() throws Exception {
        Platform platform = new Platform();
        BackgroundWorkProtection protection = new BackgroundWorkProtection(platform);
        Object work = new Object();
        protection.retain(work, Set.of(10647, 10234, 2000));
        protection.retain(work, Set.of(10345));
        protection.retain(work, Set.of());
        assertEquals(Set.of(10647, 10234, 10345), protection.uids(work));
        protection.close();
        assertTrue(platform.active.isEmpty());
        assertTrue(protection.uids(work).isEmpty());
    }

    @Test public void failedExpansionRollsBackOnlyNewResources() throws Exception {
        Platform platform = new Platform();
        BackgroundWorkProtection protection = new BackgroundWorkProtection(platform);
        Object work = new Object();
        protection.retain(work, Set.of(10123));
        platform.failedUid = 10345;
        assertThrows(IllegalStateException.class, () -> protection.retain(work,
                new java.util.LinkedHashSet<>(java.util.List.of(10234, 10345))));
        assertEquals(Set.of(10123), platform.active.keySet());
        assertEquals(Set.of(10123), protection.uids(work));
        protection.close();
    }

    @Test public void automationCannotAcquireUnboundedWork() {
        assertThrows(IllegalArgumentException.class, () -> ShellBackgroundWork.validateDuration(0, false));
        assertThrows(IllegalArgumentException.class, () -> ShellBackgroundWork.validateDuration(1800001, false));
        ShellBackgroundWork.validateDuration(1000, false);
        ShellBackgroundWork.validateDuration(1800000, false);
        ShellBackgroundWork.validateDuration(0, true);
    }
}
