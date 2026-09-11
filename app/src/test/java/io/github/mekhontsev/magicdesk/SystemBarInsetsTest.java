package io.github.mekhontsev.magicdesk;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class SystemBarInsetsTest {
    @Test public void consoleReservesKeyboardSpace() throws Exception {
        assertTrue(RuntimeSourceFixture.methods("CommandConsoleActivity", "createContentView")
                .contains("SystemBarInsets.addToPadding(page, true)"));
    }

    @Test public void keyboardPaddingDoesNotAccumulateOrDoubleCountNavigation() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Insets {
                    final int left, top, right, bottom;
                    Insets(int l,int t,int r,int b) { left=l; top=t; right=r; bottom=b; }
                }
                static class WindowInsets {
                    static class Type {
                        static int systemBars() { return 1; }
                        static int displayCutout() { return 2; }
                        static int ime() { return 4; }
                    }
                    int keyboard, requested;
                    Insets getInsets(int mask) {
                        requested=mask;
                        return new Insets((mask&2)!=0?30:0, (mask&1)!=0?24:0, 0,
                                Math.max((mask&1)!=0?20:0, (mask&4)!=0?keyboard:0));
                    }
                }
                static class View {
                    interface Listener { WindowInsets apply(View view,WindowInsets insets); }
                    int left=8,top=6,right=8,bottom=6; Listener listener;
                    int getPaddingLeft() { return left; } int getPaddingTop() { return top; }
                    int getPaddingRight() { return right; } int getPaddingBottom() { return bottom; }
                    void setPadding(int l,int t,int r,int b) { left=l;top=t;right=r;bottom=b; }
                    void setOnApplyWindowInsetsListener(Listener value) { listener=value; }
                }
                static class SystemBarInsets {
                """ + RuntimeSourceFixture.methods("SystemBarInsets", "addToPadding") + """
                }
                public static void verify() {
                    View view=new View(); WindowInsets insets=new WindowInsets();
                    SystemBarInsets.addToPadding(view,true);
                    check(view.listener.apply(view,insets)==insets, "insets consumed for child views");
                    check(insets.requested==7 && view.bottom==26, "missing inset types or base padding");
                    check(view.left==38 && view.top==30 && view.right==8, "bars or cutout not reserved");
                    insets.keyboard=300; view.listener.apply(view,insets);
                    check(view.bottom==306, "keyboard missing or navigation double counted");
                    view.listener.apply(view,insets);
                    check(view.bottom==306 && view.top==30, "padding accumulated");
                    insets.keyboard=0; view.listener.apply(view,insets);
                    check(view.bottom==26, "hidden keyboard left padding behind");
                    View other=new View(); SystemBarInsets.addToPadding(other);
                    insets.keyboard=300; other.listener.apply(other,insets);
                    check(insets.requested==3 && other.bottom==26, "changed non-IME consumers");
                }
                """);
    }
}
