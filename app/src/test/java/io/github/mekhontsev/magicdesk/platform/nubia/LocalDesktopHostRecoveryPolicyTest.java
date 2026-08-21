package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.TaskRepository;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.view.Display;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class LocalDesktopHostRecoveryPolicyTest {
    private static final String PACKAGE = "io.github.mekhontsev.magicdesk";

    @Test
    public void restoresLocalDesktopWhenHomeReplacesLastWindow() {
        assertTrue(LocalDesktopHostRecoveryPolicy.shouldRestore(
                Display.DEFAULT_DISPLAY,
                Arrays.asList(desktopHost(), home(true)),
                PACKAGE));
    }

    @Test
    public void ignoresExternalDisplay() {
        assertFalse(LocalDesktopHostRecoveryPolicy.shouldRestore(
                17,
                Arrays.asList(desktopHost(), home(true)),
                PACKAGE));
    }

    @Test
    public void ignoresHomeBehindVisibleApplication() {
        assertFalse(LocalDesktopHostRecoveryPolicy.shouldRestore(
                Display.DEFAULT_DISPLAY,
                Arrays.asList(desktopHost(), home(true), application(true)),
                PACKAGE));
    }

    @Test
    public void ignoresHomeWithoutDesktopHost() {
        assertFalse(LocalDesktopHostRecoveryPolicy.shouldRestore(
                Display.DEFAULT_DISPLAY,
                Collections.singletonList(home(true)),
                PACKAGE));
    }

    @Test
    public void ignoresHiddenHome() {
        assertFalse(LocalDesktopHostRecoveryPolicy.shouldRestore(
                Display.DEFAULT_DISPLAY,
                Arrays.asList(desktopHost(), home(false)),
                PACKAGE));
    }

    @Test
    public void ignoresAlreadyActiveDesktopHost() {
        assertFalse(LocalDesktopHostRecoveryPolicy.shouldRestore(
                Display.DEFAULT_DISPLAY,
                Arrays.asList(desktopHost(true), home(true)),
                PACKAGE));
    }

    @Test
    public void ignoresMagicDeskStructuralHome() {
        assertFalse(LocalDesktopHostRecoveryPolicy.shouldRestore(
                Display.DEFAULT_DISPLAY,
                Arrays.asList(desktopHost(), structuralHome()),
                PACKAGE));
    }

    private static TaskRepository.TaskEntry desktopHost() {
        return desktopHost(false);
    }

    private static TaskRepository.TaskEntry desktopHost(
            final boolean active) {
        return task(
                PACKAGE,
                PACKAGE + "/.DesktopActivity",
                false,
                active,
                active);
    }

    private static TaskRepository.TaskEntry home(final boolean visible) {
        return task(
                "com.zte.mifavor.launcher",
                "com.zte.mifavor.launcher/.QuickstepLauncher",
                true,
                visible,
                false);
    }

    private static TaskRepository.TaskEntry structuralHome() {
        return task(
                PACKAGE,
                PACKAGE + "/.TaskAreaBackstopActivity",
                true,
                true,
                false);
    }

    private static TaskRepository.TaskEntry application(final boolean visible) {
        return task(
                "net.sf.golly",
                "net.sf.golly/.MainActivity",
                false,
                visible,
                true);
    }

    private static TaskRepository.TaskEntry task(
            final String packageName,
            final String componentName,
            final boolean home,
            final boolean visible,
            final boolean active) {
        return new TaskRepository.TaskEntry(
                1,
                componentName.hashCode(),
                Display.DEFAULT_DISPLAY,
                packageName,
                componentName,
                componentName,
                "fullscreen",
                new Rect(0, 0, 1080, 2400),
                home,
                visible,
                active);
    }
}
