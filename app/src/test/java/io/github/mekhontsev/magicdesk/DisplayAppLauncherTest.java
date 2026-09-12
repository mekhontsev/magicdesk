package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public final class DisplayAppLauncherTest {
    private static final LaunchActivityIdentity APP =
            LaunchActivityIdentity.packageScoped(0, "com.example.app", null);

    @Test
    public void transfersAnExistingExternalTask() {
        final TaskRepository.TaskEntry external = task(1, 3, "com.example.app", false);
        assertSame(external, DisplayAppLauncher.selectTransfer(APP,
                new TaskRepository.Snapshot(Collections.singletonList(external), true, ""), 0));
    }

    @Test
    public void phoneInstanceLeavesExternalTaskAlone() {
        final TaskRepository.TaskEntry external = task(1, 3, "com.example.app", false);
        final TaskRepository.TaskEntry phone = task(2, 0, "com.example.app", false);
        assertNull(DisplayAppLauncher.selectTransfer(APP, new TaskRepository.Snapshot(
                Collections.singletonList(external), Collections.singletonList(phone), true, ""), 0));
    }

    @Test
    public void doesNotMoveHomeOrUnrelatedApplications() {
        assertNull(DisplayAppLauncher.selectTransfer(APP, new TaskRepository.Snapshot(
                Arrays.asList(task(1, 3, "com.example.app", true),
                        task(2, 3, "com.example.other", false)), true, ""), 0));
    }

    @Test
    public void newAppNeedsNoTransfer() {
        assertNull(DisplayAppLauncher.selectTransfer(APP, new TaskRepository.Snapshot(
                Collections.emptyList(), true, ""), 0));
    }

    private static TaskRepository.TaskEntry task(
            final int id, final int displayId, final String packageName, final boolean home) {
        return new TaskRepository.TaskEntry(id, id, displayId, packageName,
                packageName + "/.MainActivity", packageName + "/.MainActivity",
                displayId == 0 ? "fullscreen" : "freeform", null, -1, -1,
                home, true, true, 0);
    }
}
