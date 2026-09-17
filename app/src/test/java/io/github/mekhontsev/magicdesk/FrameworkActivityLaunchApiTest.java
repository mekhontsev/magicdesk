package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class FrameworkActivityLaunchApiTest {
    @Test public void explicitFullscreenDoesNotInheritPersistedFreeformParameters() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Rect {
                    int left, top, right, bottom;
                    boolean isEmpty() { return left >= right || top >= bottom; }
                }
                public static class ActivityOptions {
                    int display = -1, mode;
                    Rect bounds;
                    static ActivityOptions makeBasic() { return new ActivityOptions(); }
                    void setLaunchDisplayId(int value) { display = value; }
                    public void setLaunchWindowingMode(int value) { mode = value; }
                    void setLaunchBounds(Rect value) { bounds = value; }
                }
                """ + RuntimeSourceFixture.methods("FrameworkActivityLaunchApi", "options") + """
                public static void verify() throws Exception {
                    for (int display : new int[] {0, 7}) {
                        ActivityOptions fullscreen = options(display, true);
                        check(fullscreen.display == display && fullscreen.mode == 1,
                                "fullscreen destination changed");
                        check(fullscreen.bounds != null && fullscreen.bounds.isEmpty(),
                                "fullscreen must explicitly clear saved freeform launch bounds");
                        ActivityOptions inherited = options(display, false);
                        check(inherited.display == display && inherited.mode == 0
                                        && inherited.bounds == null,
                                "unspecified presentation must retain Android launch defaults");
                        check(options(display, true).bounds != fullscreen.bounds,
                                "mutable launch bounds shared between requests");
                    }
                    try {
                        options(-1, true);
                        throw new AssertionError("invalid display accepted");
                    } catch (IllegalArgumentException expected) { }
                }
                """);
    }
}
