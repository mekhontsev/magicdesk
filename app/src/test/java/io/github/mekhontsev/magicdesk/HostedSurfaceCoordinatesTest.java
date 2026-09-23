package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class HostedSurfaceCoordinatesTest {
    @Test public void ordinaryAndEmbeddedHostsReportTheSameDisplayCoordinateContract() throws Exception {
        RuntimeSourceFixture.verify("""
            java.util.function.Consumer<int[]> screenOrigin;
            int frameWidth = 800, frameHeight = 600;
            record Viewport(float left, float top, float width, float height) { }
            record Geometry(int contentWidth, int contentHeight, float left, float top, float right, float bottom) { }
            Viewport viewport = new Viewport(10, 20, 400, 300);
            void getLocationOnScreen(int[] result) { result[0] = 30; result[1] = 40; }
            public static void verify() {
                var view = new Fixture();
                check(view.geometry().equals(new Geometry(800, 600, 40, 60, 440, 360)), "ordinary geometry changed");
                view.screenOrigin(result -> { result[0] = 100; result[1] = -50; });
                check(view.geometry().equals(new Geometry(800, 600, 110, -30, 510, 270)), "embedded origin or letterbox lost");
                view.screenOrigin(null);
                check(view.geometry().left() == 40, "fallback retained embedded coordinates");
            }
            """ + RuntimeSourceFixture.methods("HostedSurfaceView", "screenOrigin", "locateOnScreen", "geometry"));
    }
}
