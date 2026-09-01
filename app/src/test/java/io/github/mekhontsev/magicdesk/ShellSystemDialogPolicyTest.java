package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Set;

public final class ShellSystemDialogPolicyTest {
    @Test
    public void resolvedTransientPackageIsRecognizedRegardlessOfUid() {
        final ShellSystemDialogPolicy policy =
                new ShellSystemDialogPolicy(Set.of(
                        "android", "com.android.intentresolver"));

        assertTrue(policy.isSystemDialog(window(
                "com.android.intentresolver", 10_287)));
        assertTrue(policy.isSystemDialog(window("android", 1_000)));
    }

    @Test
    public void unrelatedSystemApplicationIsNotDialog() {
        final ShellSystemDialogPolicy policy =
                new ShellSystemDialogPolicy(Set.of("android"));

        assertFalse(policy.isSystemDialog(window(
                "com.android.settings", 1_000)));
        assertFalse(policy.isSystemDialog(window("example.app", 10_321)));
    }

    private static FrameworkInputWindowState.Window window(
            final String packageName,
            final int ownerUid) {
        return new FrameworkInputWindowState.Window(
                7, packageName, packageName + "/.Activity", ownerUid, 0);
    }
}
