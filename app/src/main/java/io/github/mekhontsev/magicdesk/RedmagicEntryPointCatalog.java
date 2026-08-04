package io.github.mekhontsev.magicdesk;

import java.util.Collections;
import java.util.List;

final class RedmagicEntryPointCatalog {
    private static final List<EntryPoint> ENTRIES = Collections.singletonList(
            new EntryPoint(
                    "cn.nubia.redmagickyi",
                    "cn.nubia.redmagickyi.guide.activity.RedmagicStartActivity",
                    "intent.action.redmagickyi.main"));

    private RedmagicEntryPointCatalog() {
    }

    static List<EntryPoint> entries() {
        return ENTRIES;
    }

    static final class EntryPoint {
        final AppLaunchTarget launchTarget;

        EntryPoint(
                final String packageName,
                final String activityClassName,
                final String action) {
            launchTarget = AppLaunchTarget.explicit(
                    packageName, activityClassName, action);
        }
    }
}
