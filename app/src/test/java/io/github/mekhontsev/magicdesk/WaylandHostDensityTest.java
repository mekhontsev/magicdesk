package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class WaylandHostDensityTest {
    @Test public void fractionalScaleReachesOutputWithoutRedundantUpdates() throws Exception {
        RuntimeSourceFixture.verify("""
                final Activity activity = new Activity();
                final Output output = new Output();
                float scale;
                static class Activity { float scale = 1.3f; }
                static class HostedUiScale {
                    static float resolve(Activity activity) { return activity.scale; }
                }
                static class Output {
                    double scale;
                    int calls;
                    void scale(double value) { scale = value; calls++; }
                }
                public static void verify() {
                    var host = new Fixture();
                    host.updateDensity();
                    check(Math.abs(host.output.scale - 1.3) < 0.00001, "fractional output density");
                    check(host.scale == host.activity.scale, "layout and protocol scales differ");
                    host.updateDensity();
                    check(host.output.calls == 1, "unchanged density republished");
                    host.activity.scale = 1.95f;
                    host.updateDensity();
                    check(Math.abs(host.output.scale - 1.95) < 0.00001, "live density truncated");
                    check(host.output.calls == 2 && host.scale == host.activity.scale, "live scale ownership");
                }
                """ + RuntimeSourceFixture.methods("WaylandHostBinding", "updateDensity"));
    }
}
