package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopAutomationDisplayBoundsTest {
    @Test
    public void taskbarAndPopupBoundsIncludeWindowOrigin() throws Exception {
        verify("""
                View taskbar = new View(0, 1016, 10, 8, 118, 56);
                check(readDisplayBounds(taskbar, out), "taskbar invisible");
                checkBounds(out, 10, 1024, 118, 1072);
                View popup = new View(16, 396, 151, 133, 276, 237);
                check(readDisplayBounds(popup, out), "popup invisible");
                checkBounds(out, 167, 529, 292, 633);
                """);
    }

    @Test
    public void clippingIsPreservedAndHiddenBoundsAreEmpty() throws Exception {
        verify("""
                View clipped = new View(-20, 100, 20, 30, 50, 60);
                check(readDisplayBounds(clipped, out), "clipped view invisible");
                checkBounds(out, 0, 130, 30, 160);
                clipped.shown = false;
                check(!readDisplayBounds(clipped, out), "hidden view visible");
                check(out.isEmpty(), "hidden bounds retained");
                clipped.shown = true;
                clipped.clippedOut = true;
                check(!readDisplayBounds(clipped, out), "clipped-out view visible");
                check(out.isEmpty(), "clipped-out bounds retained");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static final class Rect {
                    int left, top, right, bottom;
                    void setEmpty() { left = top = right = bottom = 0; }
                    boolean isEmpty() { return right <= left || bottom <= top; }
                    void offset(int x, int y) { left += x; right += x; top += y; bottom += y; }
                }
                static final class View {
                    final int x, y, left, top, right, bottom;
                    boolean shown = true, clippedOut;
                    View(int x, int y, int left, int top, int right, int bottom) {
                        this.x = x; this.y = y; this.left = left; this.top = top;
                        this.right = right; this.bottom = bottom;
                    }
                    boolean isAttachedToWindow() { return true; }
                    boolean isShown() { return shown; }
                    float getAlpha() { return 1; }
                    View getRootView() { return this; }
                    void getLocationOnScreen(int[] p) { p[0] = x; p[1] = y; }
                    boolean getGlobalVisibleRect(Rect r) {
                        r.left = left; r.top = top; r.right = right; r.bottom = bottom;
                        return !clippedOut;
                    }
                }
                static void checkBounds(Rect r, int left, int top, int right, int bottom) {
                    check(r.left == left && r.top == top && r.right == right && r.bottom == bottom,
                            "incorrect display bounds");
                }
                public static void verify() {
                    Rect out = new Rect();
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                "DesktopAutomationUiRegistry", "readDisplayBounds", "isVisible"));
    }
}
