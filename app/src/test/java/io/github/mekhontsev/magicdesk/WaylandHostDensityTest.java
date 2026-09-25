package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class WaylandHostDensityTest {
    @Test public void fractionalScaleReachesOutputWithoutRedundantUpdates() throws Exception {
        RuntimeSourceFixture.verify("""
                final Activity activity = new Activity();
                final Session session = new Session();
                final Output output = new Output();
                float scale;
                static class Activity { float scale = 1.3f; }
                static class Session {
                    int percent = 100;
                    float unitScale(Activity activity) { return activity.scale * percent / 100f; }
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
                    host.session.percent = 150;
                    host.updateDensity();
                    check(Math.abs(host.output.scale - 2.925) < 0.00001, "manual profile reaches output");
                    host.updateDensity();
                    check(host.output.calls == 3, "manual profile re-emitted without change");
                    host.session.percent = 100;
                    host.updateDensity();
                    check(Math.abs(host.output.scale - 1.95) < 0.00001, "reset keeps automatic host density");
                }
                """ + RuntimeSourceFixture.methods("WaylandHostBinding", "updateDensity"));
    }

    @Test public void constraintsPlacementAndOutputUseTheSameSessionScale() throws Exception {
        org.junit.Assert.assertTrue(RuntimeSourceFixture.methods("WaylandSessions", "unitScale")
                .contains("HostedUiScale.adjust(HostedUiScale.resolve(activity), scalePercent)"));
        org.junit.Assert.assertTrue(RuntimeSourceFixture.methods("WaylandActivity", "changed")
                .contains("content.constraints(current.constraints(), session.unitScale(this))"));
    }
}
