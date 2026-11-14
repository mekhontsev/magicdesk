package io.github.mekhontsev.magicdesk.platform.honor;

import java.util.Set;
import io.github.mekhontsev.magicdesk.PlatformComponent;
import io.github.mekhontsev.magicdesk.PlatformDevice;
import io.github.mekhontsev.magicdesk.PlatformExtension;
import io.github.mekhontsev.magicdesk.PlatformFeatures;
import io.github.mekhontsev.magicdesk.PlatformMatch;
import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;

/** Honor's optional scaled-window contract; all other services retain Android owners. */
public final class HonorPlatformDriver implements PlatformExtension {
    private final HonorWindowingDriver mWindowing;

    public HonorPlatformDriver(HonorWindowingDriver windowing) { mWindowing = windowing; }

    @Override public String id() { return "honor"; }
    @Override public String name() { return "Honor window geometry"; }
    @Override public PlatformMatch match(PlatformDevice device) {
        return device != null && device.sdkInt >= 34 && mWindowing.hasScaledWindows()
                ? PlatformMatch.matched(mWindowing.geometryDiagnostic())
                : PlatformMatch.unavailable("scaled-window task API unavailable");
    }
    @Override public Set<PlatformComponent> components() {
        return Set.of(PlatformComponent.WINDOWING);
    }
    @Override public String componentEvidence(PlatformComponent component) {
        return component == PlatformComponent.WINDOWING ? mWindowing.geometryDiagnostic() : "";
    }
    @Override public PlatformFeatures extendFeatures(PlatformFeatures baseline) { return baseline; }
    @Override public PlatformWindowingDriver windowing() { return mWindowing; }
}
