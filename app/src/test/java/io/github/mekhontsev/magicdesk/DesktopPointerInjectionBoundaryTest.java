package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopPointerInjectionBoundaryTest {
    @Test
    public void coordinateActionsUseOnlyTheDisplayTargetedInjector() throws Exception {
        RuntimeSourceFixture.verify("""
                static final String TAG = "test";
                static final List<String> events = new ArrayList<>();
                static class Point {
                    final int x, y;
                    Point(int x, int y) { this.x = x; this.y = y; }
                }
                static class Log {
                    static void e(String tag, String message, Throwable error) {}
                }
                static class DesktopPointerInjector {
                    static boolean reject;
                    static void injectMouseHover(int display, Point p)
                            throws ReflectiveOperationException {
                        if (reject) throw new ReflectiveOperationException();
                        events.add("hover:" + display + ":" + p.x + ":" + p.y);
                    }
                    static void injectClickAt(int display, Point p, int button) {
                        if (reject) throw new IllegalStateException();
                        events.add("click:" + display + ":" + p.x + ":" + p.y + ":" + button);
                    }
                }
                public static void verify() {
                    Fixture f = new Fixture();
                    check(f.injectPointerHoverAt(133, 500, 400), "external hover rejected");
                    check(f.injectPointerClickAt(133, 500, 400, 1), "external click rejected");
                    check(f.injectPointerHoverAt(0, 120, 600), "phone hover rejected");
                    check(events.equals(List.of("hover:133:500:400", "click:133:500:400:1",
                            "hover:0:120:600")), "display or coordinates were lost: " + events);
                    DesktopPointerInjector.reject = true;
                    check(!f.injectPointerHoverAt(133, 500, 400), "hover failure reported success");
                    check(!f.injectPointerClickAt(133, 500, 400, 1), "click failure reported success");
                }
                """ + RuntimeSourceFixture.methods("ShellCommandService",
                        "injectPointerHoverAt", "injectPointerClickAt"));
    }
}
