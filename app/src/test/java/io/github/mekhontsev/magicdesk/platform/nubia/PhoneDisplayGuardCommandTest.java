package io.github.mekhontsev.magicdesk.platform.nubia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

public final class PhoneDisplayGuardCommandTest {
    private static final int MAGICDESK_UID = 10_647;
    private static final int DESKTOP_APP_UID = 10_648;
    private static final int RETAINED_APP_UID = 10_649;

    @Test
    public void desktopHomeAloneNeedsNoWorkingState() {
        assertTrue(PhoneDisplayGuardCommand.protectedDesktopUids(
                MAGICDESK_UID, Set.of(MAGICDESK_UID), Set.of()).isEmpty());
    }

    @Test
    public void excludesHomeWithoutChangingOtherAppProtection() {
        final Set<Integer> live = Set.of(MAGICDESK_UID, DESKTOP_APP_UID);
        final Set<Integer> retained = Set.of(DESKTOP_APP_UID, RETAINED_APP_UID);

        assertEquals(Set.of(DESKTOP_APP_UID, RETAINED_APP_UID),
                PhoneDisplayGuardCommand.protectedDesktopUids(
                        MAGICDESK_UID, live, retained));
        assertEquals(Set.of(MAGICDESK_UID, DESKTOP_APP_UID), live);
        assertEquals(Set.of(DESKTOP_APP_UID, RETAINED_APP_UID), retained);
    }

    @Test
    public void retainsOtherAppsWhenNoTasksCanBeRead() {
        assertEquals(Set.of(RETAINED_APP_UID),
                PhoneDisplayGuardCommand.protectedDesktopUids(
                        MAGICDESK_UID, Set.of(), Set.of(RETAINED_APP_UID)));
    }

    @Test
    public void retainedUidCannotReintroduceHomeProtection() {
        assertEquals(Set.of(DESKTOP_APP_UID),
                PhoneDisplayGuardCommand.protectedDesktopUids(
                        MAGICDESK_UID, Set.of(DESKTOP_APP_UID),
                        Set.of(MAGICDESK_UID)));
    }

    @Test
    public void doesNotAddHomeWhenDesktopIsEmpty() {
        assertTrue(PhoneDisplayGuardCommand.protectedDesktopUids(
                MAGICDESK_UID, Set.of(), Set.of()).isEmpty());
    }
}
