package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.Display;

import org.junit.Test;

import java.util.Collections;
import java.util.Set;

public final class ShellSecondaryHomeStartPolicyTest {
    private static final String MAGICDESK =
            "io.github.mekhontsev.magicdesk";
    private static final Set<String> SECONDARY_HOME =
            Collections.singleton(Intent.CATEGORY_SECONDARY_HOME);

    @Test
    public void blocksCompetingSecondaryHomeForExternalSession() {
        assertTrue(ShellSecondaryHomeStartPolicy.shouldBlock(
                4,
                Intent.ACTION_MAIN,
                SECONDARY_HOME,
                null,
                null,
                null));
        assertTrue(ShellSecondaryHomeStartPolicy.shouldBlock(
                4,
                Intent.ACTION_MAIN,
                SECONDARY_HOME,
                "android",
                "com.android.internal.app.ResolverActivity",
                null));
        assertTrue(ShellSecondaryHomeStartPolicy.shouldBlock(
                4,
                Intent.ACTION_MAIN,
                SECONDARY_HOME,
                "com.example.launcher",
                "com.example.launcher.SecondaryDisplayLauncher",
                null));
    }

    @Test
    public void allowsSecondaryHomeWithoutExternalSession() {
        assertFalse(ShellSecondaryHomeStartPolicy.shouldBlock(
                Display.INVALID_DISPLAY,
                Intent.ACTION_MAIN,
                SECONDARY_HOME,
                null,
                null,
                null));
        assertFalse(ShellSecondaryHomeStartPolicy.shouldBlock(
                Display.DEFAULT_DISPLAY,
                Intent.ACTION_MAIN,
                SECONDARY_HOME,
                null,
                null,
                null));
    }

    @Test
    public void allowsExplicitSecondaryHomeStarts() {
        assertFalse(ShellSecondaryHomeStartPolicy.shouldBlock(
                4,
                Intent.ACTION_MAIN,
                SECONDARY_HOME,
                MAGICDESK,
                MAGICDESK + ".DesktopActivity",
                null));
        assertFalse(ShellSecondaryHomeStartPolicy.shouldBlock(
                4,
                Intent.ACTION_MAIN,
                SECONDARY_HOME,
                null,
                null,
                MAGICDESK));
    }

    @Test
    public void allowsOtherActivityStarts() {
        assertFalse(ShellSecondaryHomeStartPolicy.shouldBlock(
                4,
                Intent.ACTION_VIEW,
                SECONDARY_HOME,
                null,
                null,
                null));
        assertFalse(ShellSecondaryHomeStartPolicy.shouldBlock(
                4,
                Intent.ACTION_MAIN,
                Collections.singleton(Intent.CATEGORY_HOME),
                null,
                null,
                null));
        assertFalse(ShellSecondaryHomeStartPolicy.shouldBlock(
                4,
                Intent.ACTION_MAIN,
                null,
                null,
                null,
                null));
    }
}
