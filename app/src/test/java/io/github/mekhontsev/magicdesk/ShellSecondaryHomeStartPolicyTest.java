package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.junit.Test;

import java.util.Set;

public final class ShellSecondaryHomeStartPolicyTest {
    private static final Set<String> SECONDARY = Set.of(Intent.CATEGORY_SECONDARY_HOME);

    @Test public void blocksUnaddressedSelectorsDuringExternalDesktop() {
        assertTrue(ShellSecondaryHomeStartPolicy.shouldBlock(4, Intent.ACTION_MAIN, SECONDARY, null));
        assertTrue(ShellSecondaryHomeStartPolicy.shouldBlock(4, Intent.ACTION_MAIN, SECONDARY, ""));
    }

    @Test public void doesNotInterceptWithoutExternalDesktop() {
        assertFalse(ShellSecondaryHomeStartPolicy.shouldBlock(-1, Intent.ACTION_MAIN, SECONDARY, null));
        assertFalse(ShellSecondaryHomeStartPolicy.shouldBlock(0, Intent.ACTION_MAIN, SECONDARY, null));
    }

    @Test public void retainsAndroidPackageAddressedHomeStarts() {
        assertFalse(ShellSecondaryHomeStartPolicy.shouldBlock(4, Intent.ACTION_MAIN,
                SECONDARY, "io.github.mekhontsev.magicdesk"));
        assertFalse(ShellSecondaryHomeStartPolicy.shouldBlock(4, Intent.ACTION_MAIN,
                SECONDARY, "com.example.launcher"));
    }

    @Test public void leavesPrimaryHomeAndOrdinaryLaunchesAlone() {
        assertFalse(ShellSecondaryHomeStartPolicy.shouldBlock(4, Intent.ACTION_MAIN,
                Set.of(Intent.CATEGORY_HOME), null));
        assertFalse(ShellSecondaryHomeStartPolicy.shouldBlock(4, Intent.ACTION_VIEW, SECONDARY, null));
        assertFalse(ShellSecondaryHomeStartPolicy.shouldBlock(4, null, SECONDARY, null));
        assertFalse(ShellSecondaryHomeStartPolicy.shouldBlock(4, Intent.ACTION_MAIN, null, null));
        assertFalse(ShellSecondaryHomeStartPolicy.shouldBlock(4, Intent.ACTION_MAIN, Set.of(), null));
    }

    @Test public void resolvedOwnComponentDoesNotBypassAdmission() throws Exception {
        verify("""
                Fixture policy = new Fixture();
                policy.configure(4);
                Intent request = new Intent();
                request.component = "io.github.mekhontsev.magicdesk/.DesktopActivity";
                check(!policy.onActivityStarting(request, "io.github.mekhontsev.magicdesk"),
                        "resolved preferred handler bypassed the guard");
                request.packageName = "io.github.mekhontsev.magicdesk";
                check(policy.onActivityStarting(request, request.packageName),
                        "addressed framework HOME was blocked");
                request.packageName = null;
                request.categories = null;
                check(policy.onActivityStarting(request, "io.github.mekhontsev.magicdesk"),
                        "explicit Desktop host launch was blocked");
                check(policy.onActivityStarting(null, "com.example"), "null intent was blocked");
                """);
    }

    @Test public void oneWorkspaceCannotReleaseAnotherWorkspacesProtection() throws Exception {
        verify("""
                Fixture first = new Fixture();
                Fixture second = new Fixture();
                Intent request = new Intent();
                check(first.onActivityStarting(request, "com.example"), "unconfigured guard is active");
                first.configure(4);
                second.configure(7);
                first.configure(-1);
                check(first.onActivityStarting(request, "com.example"), "released guard is active");
                check(!second.onActivityStarting(request, "com.example"), "other workspace lost protection");
                second.configure(-1);
                check(second.onActivityStarting(request, "com.example"), "last release retained protection");
                first.configure(0);
                check(first.onActivityStarting(request, "com.example"), "phone-only workspace intercepted secondary HOME");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class Display { static final int INVALID_DISPLAY = -1, DEFAULT_DISPLAY = 0; }
                static class Intent {
                    static final String ACTION_MAIN = "main", CATEGORY_SECONDARY_HOME = "secondary";
                    String action = ACTION_MAIN, packageName, component;
                    Set<String> categories = Set.of(CATEGORY_SECONDARY_HOME);
                    String getAction() { return action; }
                    Set<String> getCategories() { return categories; }
                    String getPackage() { return packageName; }
                }
                static class Log { static void i(String tag, String message) {} }
                volatile int mDisplayId = -1;
                """ + RuntimeSourceFixture.methods("ShellSecondaryHomeStartPolicy",
                        "configure", "onActivityStarting", "shouldBlock").replace("@Override", "")
                + "public static void verify() {\n" + scenario + "}\n");
    }
}
