package io.github.mekhontsev.magicdesk.platform.nubia;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.mekhontsev.magicdesk.AppLaunchTarget;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public final class RedmagicEntryPointCatalogTest {
    @Test
    public void catalogContainsUniqueExplicitTargets() {
        final Set<String> packages = new HashSet<>();
        for (final AppLaunchTarget target : RedmagicEntryPointCatalog.targets()) {
            assertTrue(packages.add(target.packageName()));
            assertFalse(target.activityClassName().isEmpty());
            assertFalse(target.action().isEmpty());
        }
    }
}
