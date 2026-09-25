package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import static io.github.mekhontsev.magicdesk.DesktopSystemThemeSession.Preference.*;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class DesktopSystemThemeSessionTest {
    @Test public void inactiveAndUnchangedDoNotAccessAndroid() throws Exception {
        final Fixture f = new Fixture(SystemNightMode.DARK);
        f.session.update(LIGHT, false);
        f.session.update(UNCHANGED, true);
        assertEquals(0, f.reads);
        assertTrue(f.writes.isEmpty());
    }

    @Test public void severalWorkspacesShareOneOverride() throws Exception {
        final Fixture f = new Fixture(SystemNightMode.DARK);
        f.session.update(LIGHT, true);
        f.session.update(LIGHT, true); // second workspace joins
        f.session.update(LIGHT, true); // first workspace closes
        assertEquals(List.of(SystemNightMode.LIGHT), f.writes);
        f.session.update(LIGHT, false);
        assertEquals(List.of(SystemNightMode.LIGHT, SystemNightMode.DARK), f.writes);
        assertNull(f.pending);
    }

    @Test public void liveChangesKeepOriginalAutomaticPolicy() throws Exception {
        for (final SystemNightMode original : List.of(SystemNightMode.AUTO,
                SystemNightMode.SCHEDULE, SystemNightMode.BEDTIME)) {
            final Fixture f = new Fixture(original);
            f.session.update(LIGHT, true);
            f.session.update(DARK, true);
            assertEquals(original, f.pending.previous());
            f.session.update(UNCHANGED, true);
            assertEquals(List.of(SystemNightMode.LIGHT, SystemNightMode.DARK, original), f.writes);
            assertNull(f.pending);
        }
    }

    @Test public void alreadySelectedThemeIsNotOwned() throws Exception {
        final Fixture f = new Fixture(SystemNightMode.LIGHT);
        f.session.update(LIGHT, true);
        assertNull(f.pending);
        f.current = SystemNightMode.DARK;
        f.session.update(LIGHT, false);
        assertTrue(f.writes.isEmpty());
    }

    @Test public void userChangeIsRespectedEvenAfterTheySwitchBack() throws Exception {
        final Fixture f = new Fixture(SystemNightMode.AUTO);
        f.session.update(LIGHT, true);
        f.current = SystemNightMode.DARK;
        f.session.systemChanged();
        assertNull(f.pending);
        f.current = SystemNightMode.LIGHT;
        f.session.systemChanged();
        f.session.update(LIGHT, true);
        f.session.update(LIGHT, false);
        assertEquals(List.of(SystemNightMode.LIGHT), f.writes);
    }

    @Test public void unobservedUserChangeIsCheckedOnClose() throws Exception {
        final Fixture f = new Fixture(SystemNightMode.AUTO);
        f.session.update(LIGHT, true);
        f.current = SystemNightMode.BEDTIME;
        f.session.update(LIGHT, false);
        assertEquals(SystemNightMode.BEDTIME, f.current);
        assertNull(f.pending);
    }

    @Test public void explicitNewPreferenceAfterUserChangeUsesTheirNewBaseline() throws Exception {
        final Fixture f = new Fixture(SystemNightMode.AUTO);
        f.session.update(LIGHT, true);
        f.current = SystemNightMode.SCHEDULE;
        f.session.systemChanged();
        f.session.update(DARK, true);
        f.session.update(DARK, false);
        assertEquals(SystemNightMode.SCHEDULE, f.current);
    }

    @Test public void ownSettingNotificationDoesNotRevokeRestoration() throws Exception {
        final Fixture f = new Fixture(SystemNightMode.DARK);
        f.session.update(LIGHT, true);
        f.session.systemChanged();
        assertNotNull(f.pending);
        f.session.update(LIGHT, false);
        assertEquals(SystemNightMode.DARK, f.current);
    }

    @Test public void nextProcessRecoversPendingThemeWithoutDesktop() throws Exception {
        final Fixture f = new Fixture(SystemNightMode.BEDTIME);
        f.session.update(LIGHT, true);
        f.restart();
        f.session.update(UNCHANGED, false);
        assertEquals(SystemNightMode.BEDTIME, f.current);
        assertNull(f.pending);
    }

    @Test public void failedJournalCannotChangeSystem() {
        final Fixture f = new Fixture(SystemNightMode.DARK);
        f.failStorage = true;
        assertThrows(IOException.class, () -> f.session.update(LIGHT, true));
        assertTrue(f.writes.isEmpty());
        assertNull(f.pending);
    }

    @Test public void lostWriteReplyRemainsRecoverable() throws Exception {
        final Fixture f = new Fixture(SystemNightMode.AUTO);
        f.failAfterWrite = true;
        assertThrows(IOException.class, () -> f.session.update(LIGHT, true));
        assertNotNull(f.pending);
        assertEquals(SystemNightMode.LIGHT, f.current);
        f.failAfterWrite = false;
        f.restart();
        f.session.update(UNCHANGED, false);
        assertEquals(SystemNightMode.AUTO, f.current);
    }

    @Test public void refusedWriteDoesNotPretendThemeWasApplied() throws Exception {
        final Fixture f = new Fixture(SystemNightMode.DARK);
        f.ignoreWrite = true;
        assertThrows(IOException.class, () -> f.session.update(LIGHT, true));
        assertFalse("A refresh must not report recovery without another attempt",
                f.session.update(LIGHT, true));
        assertEquals(List.of(SystemNightMode.LIGHT), f.writes);
        assertTrue(f.session.update(LIGHT, false));
        assertEquals(SystemNightMode.DARK, f.current);
        assertNull(f.pending);
    }

    @Test public void lostRestoreReplyDoesNotRepeatRestoration() throws Exception {
        final Fixture f = new Fixture(SystemNightMode.AUTO);
        f.session.update(LIGHT, true);
        f.failAfterWrite = true;
        assertThrows(IOException.class, () -> f.session.update(LIGHT, false));
        f.failAfterWrite = false;
        f.session.update(LIGHT, false);
        assertEquals(List.of(SystemNightMode.LIGHT, SystemNightMode.AUTO), f.writes);
        assertNull(f.pending);
    }

    @Test public void readFailureDoesNotDiscardRecovery() throws Exception {
        final Fixture f = new Fixture(SystemNightMode.DARK);
        f.session.update(LIGHT, true);
        f.failRead = true;
        assertThrows(IOException.class, () -> f.session.update(LIGHT, false));
        assertNotNull(f.pending);
        f.failRead = false;
        f.session.update(LIGHT, false);
        assertEquals(SystemNightMode.DARK, f.current);
    }

    @Test public void unknownConfigurationIsNotGuessed() {
        assertThrows(IllegalArgumentException.class, () -> SystemNightMode.fromFramework(3, 9));
        assertThrows(IllegalArgumentException.class, () -> SystemNightMode.fromFramework(-1, -1));
        for (final SystemNightMode value : SystemNightMode.values()) {
            assertEquals(value, SystemNightMode.fromFramework(value.mode, value.customType));
        }
        assertEquals(UNCHANGED, DesktopSystemThemeSession.Preference.parse("future-value"));
    }

    private static final class Fixture implements DesktopSystemThemeSession.Access {
        SystemNightMode current;
        DesktopSystemThemeSession.Override pending;
        DesktopSystemThemeSession session;
        final List<SystemNightMode> writes = new ArrayList<>();
        int reads;
        boolean failRead, failStorage, failAfterWrite, ignoreWrite;

        Fixture(final SystemNightMode current) { this.current = current; restart(); }
        void restart() {
            session = new DesktopSystemThemeSession(this, new DesktopSystemThemeSession.Storage() {
                @Override public DesktopSystemThemeSession.Override read() { return pending; }
                @Override public void write(final DesktopSystemThemeSession.Override value) throws IOException {
                    if (failStorage) throw new IOException("storage failed");
                    pending = value;
                }
            });
        }
        @Override public SystemNightMode read() throws IOException {
            reads++;
            if (failRead) throw new IOException("read failed");
            return current;
        }
        @Override public void write(final SystemNightMode mode) throws IOException {
            assertNotNull("write-ahead journal required", pending);
            writes.add(mode);
            if (!ignoreWrite) current = mode;
            if (failAfterWrite) throw new IOException("lost reply");
        }
    }
}
