package io.github.mekhontsev.magicdesk;

import java.util.Collections;
import java.util.List;

final class RedmagicEntryPointCatalog {
    private static final List<AppLaunchTarget> TARGETS =
            Collections.singletonList(AppLaunchTarget.explicit(
                    "cn.nubia.redmagickyi",
                    "cn.nubia.redmagickyi.guide.activity.RedmagicStartActivity",
                    "intent.action.redmagickyi.main"));

    private RedmagicEntryPointCatalog() {
    }

    static List<AppLaunchTarget> targets() {
        return TARGETS;
    }
}
