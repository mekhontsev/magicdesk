package io.github.mekhontsev.magicdesk;

/** Pointer feedback relative to stable item slots, never a work-area reservation. */
public record ShellDockEffect(float scale, int liftDp, float radius) {
    public static final ShellDockEffect NONE = new ShellDockEffect(1, 0, 1.5f);
    public ShellDockEffect {
        ShellAppearance.range(scale, 1, 2, "dock scale");
        ShellAppearance.range(liftDp, 0, 32, "dock lift");
        ShellAppearance.range(radius, .5f, 3, "dock radius");
    }
    boolean enabled() { return scale > 1 || liftDp > 0; }
    float influence(float distance, float extent) {
        float x = Math.abs(distance) / Math.max(1, extent * radius);
        return x >= 1 ? 0 : (1 + (float) Math.cos(Math.PI * x)) * .5f;
    }
    int overflow(int extent, float density) {
        return (int) Math.ceil(extent * (scale - 1) * .5f + liftDp * density);
    }
}
