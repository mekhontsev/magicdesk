package io.github.mekhontsev.magicdesk;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class SystemBarInsetsTest {
    @Test public void consoleReservesKeyboardSpace() throws Exception {
        assertTrue(RuntimeSourceFixture.methods("ConsoleTerminalWindow", "createContentView")
                .contains("SystemBarInsets.addToPadding(page, true)"));
    }

    @Test public void keyboardPaddingDoesNotAccumulateOrDoubleCountNavigation() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Insets {
                    final int left, top, right, bottom;
                    Insets(int l,int t,int r,int b) { left=l; top=t; right=r; bottom=b; }
                    static final Insets NONE=new Insets(0,0,0,0);
                    static Insets of(int l,int t,int r,int b) { return new Insets(l,t,r,b); }
                    static Insets max(Insets a,Insets b) {
                        return of(Math.max(a.left,b.left),Math.max(a.top,b.top),Math.max(a.right,b.right),Math.max(a.bottom,b.bottom));
                    }
                    @Override public boolean equals(Object value) {
                        return value instanceof Insets b && left==b.left && top==b.top && right==b.right && bottom==b.bottom;
                    }
                }
                static class Rect {
                    int left,top,right,bottom;
                    Rect(int l,int t,int r,int b) { left=l;top=t;right=r;bottom=b; }
                }
                static class Intent {
                    Rect previous;
                    <T> T getParcelableExtra(String key,Class<T> type) { return type.cast(previous); }
                }
                static class Activity {
                    Intent intent=new Intent(); boolean multi=true;
                    Intent getIntent() { return intent; }
                    boolean isInMultiWindowMode() { return multi; }
                }
                static class WindowInsets {
                    static class Type {
                        static int systemBars() { return 1; }
                        static int displayCutout() { return 2; }
                        static int ime() { return 4; }
                        static int captionBar() { return 8; }
                    }
                    int keyboard, requested, caption;
                    Insets getInsets(int mask) {
                        if(mask==8) return Insets.of(0,caption,0,0);
                        requested=mask;
                        return new Insets((mask&2)!=0?30:0, (mask&1)!=0?24:0, 0,
                                Math.max((mask&1)!=0?20:0, (mask&4)!=0?keyboard:0));
                    }
                }
                static class View {
                    interface Listener { WindowInsets apply(View view,WindowInsets insets); }
                    int left=8,top=6,right=8,bottom=6; Listener listener;
                    WindowInsets published;
                    WindowInsets getRootWindowInsets() { return published; }
                    int getPaddingLeft() { return left; } int getPaddingTop() { return top; }
                    int getPaddingRight() { return right; } int getPaddingBottom() { return bottom; }
                    void setPadding(int l,int t,int r,int b) { left=l;top=t;right=r;bottom=b; }
                    void setOnApplyWindowInsetsListener(Listener value) { listener=value; }
                }
                """ + "static " + RuntimeSourceFixture.nestedClass("CaptionInsetsHandoff", "CaptionInsetsHandoff") + """
                static class SystemBarInsets {
                    static final String CAPTION_HANDOFF="caption";
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
                    Activity host=new Activity(); host.intent.previous=new Rect(0,40,0,0);
                    View restored=new View(); SystemBarInsets.addToPadding(restored,true,host);
                    restored.published=new WindowInsets();
                    restored.listener.apply(restored,insets);
                    check(restored.top==46 && restored.bottom==306, "first layout lost the known caption or IME");
                    restored.listener.apply(restored,insets);
                    check(restored.top==46, "caption padding accumulated");
                    restored.published.caption=32; restored.listener.apply(restored,insets);
                    check(restored.top==30, "caption consumed by DecorView was counted twice");
                    restored.published.caption=0; restored.listener.apply(restored,insets);
                    check(restored.top==30, "hidden caption restored stale handoff");
                    View fullscreen=new View(); SystemBarInsets.addToPadding(fullscreen,true,host);
                    host.multi=false; fullscreen.listener.apply(fullscreen,insets);
                    check(fullscreen.top==30, "fullscreen retained caption handoff");
                    host.multi=true; fullscreen.listener.apply(fullscreen,insets);
                    check(fullscreen.top==30, "old caption returned on later multiwindow entry");
                }
                """);
    }
}
