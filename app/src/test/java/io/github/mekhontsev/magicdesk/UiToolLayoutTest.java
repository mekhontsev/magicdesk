package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.junit.Test;

public final class UiToolLayoutTest {
    @Test public void applicationMenusFitContentAndFileMenusRetainTheirWidth() throws Exception {
        RuntimeSourceFixture.verify("""
                static class View { static class MeasureSpec {
                    static final int UNSPECIFIED = 0, AT_MOST = 1, EXACTLY = 2;
                    static int makeMeasureSpec(int size, int mode) { return size * 4 + mode; }
                } }
                static class LinearLayout {
                    int desired, width, calls, lastMode;
                    LinearLayout(int desired) { this.desired = desired; }
                    void measure(int w, int h) {
                        calls++; lastMode = w % 4;
                        check(h == 0, "menu height constrained before wrapping");
                        width = lastMode == View.MeasureSpec.AT_MOST ? Math.min(desired, w / 4) : w / 4;
                    }
                    int getMeasuredWidth() { return width; }
                }
                public static void verify() {
                    for (int limit : new int[]{1, 160, 360, 1080}) {
                        for (int content : new int[]{0, 120, 240, 2000}) {
                            var menu = new LinearLayout(content);
                            check(measureMenuPanel(menu, limit, true) == Math.max(1, Math.min(content, limit)),
                                    "content width not bounded");
                            check(menu.calls == 2 && menu.lastMode == View.MeasureSpec.EXACTLY,
                                    "rows did not receive final width");
                            var files = new LinearLayout(content);
                            check(measureMenuPanel(files, limit, false) == limit && files.calls == 1,
                                    "file-menu width changed");
                        }
                    }
                }
                """ + RuntimeSourceFixture.methods("DesktopUiFactory", "measureMenuPanel"));
        assertTrue(RuntimeSourceFixture.methods("ApplicationContextMenu", "show")
                .contains("ui.showMenuPopup(anchor, panel, true)"));
        assertTrue(RuntimeSourceFixture.methods("FileItemContextMenu", "showPopup")
                .contains("ui.showMenuPopup(anchor, panel)"));
        assertTrue(RuntimeSourceFixture.methods("DesktopUiFactory", "showMenuPopup")
                .contains("showMenuPopup(anchor, panel, false)"));
    }

    @Test public void edgeToEdgeContentKeepsSystemInsetsWithoutFormPadding() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Context {}
                enum UiColor { BACKGROUND }
                static class LinearLayout {
                    int left,top,right,bottom; boolean insets,ime;
                    void setPadding(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
                }
                static class UiAppearance { static void background(LinearLayout p,UiColor c){} }
                static class SystemBarInsets {
                    static void addToPadding(LinearLayout p,boolean ime){p.insets=true;p.ime=ime;}
                }
                static int dp(Context c,int value){return value*3;}
                static LinearLayout column(Context c){return new LinearLayout();}
                """ + RuntimeSourceFixture.methods("UiToolLayout", "page") + """
                public static void verify() {
                    var form=page(new Context(),UiColor.BACKGROUND,true);
                    check(form.left==36 && form.right==36 && form.top==24 && form.bottom==24,
                            "default form margins changed");
                    var terminal=page(new Context(),UiColor.BACKGROUND,true,0,0);
                    check(terminal.left==0 && terminal.right==0 && terminal.top==0 && terminal.bottom==0,
                            "terminal retained decorative padding");
                    check(form.insets && terminal.insets && terminal.ime, "edge-to-edge content lost insets/IME");
                }
                """);
    }
    @Test public void toolPagesShareInsetsWithoutChangingSpecializedSurfaces() throws Exception {
        for (String[] owner : new String[][] {
                {"SettingsView", "create"}, {"FileManagerView", "FileManagerView"},
                {"AppearanceSettings", "createPage"}, {"DeviceSetupView", "create"},
                {"ConsoleTerminalWindow", "createContentView"}}) {
            String source=java.nio.file.Files.readString(java.nio.file.Path.of(RuntimeSourceFixture.MAIN+owner[0]+".java"));
            assertTrue(owner[0], source.contains("UiToolLayout.page("));
        }
        for (String owner : new String[] {"ActivityExplorerActivity", "AppLogViewerActivity", "AppPresentationSettingsView",
                "CompatibilityOnboardingActivity", "DiagnosticsActivity", "GraphicalSessionsActivity",
                "PhoneControlPanelController", "TaskManagerView", "UserPromptActivity"}) {
            String source=java.nio.file.Files.readString(java.nio.file.Path.of(RuntimeSourceFixture.MAIN+owner+".java"));
            assertTrue(owner, source.contains("UiToolLayout.page("));
        }
        for (String owner : new String[] {"X11Activity", "WaylandActivity", "DisplayViewerActivity"}) {
            String source=java.nio.file.Files.readString(java.nio.file.Path.of(RuntimeSourceFixture.MAIN+owner+".java"));
            assertFalse(owner, source.contains("UiToolLayout.page("));
        }
    }

    @Test public void adaptiveNavigationResizesTheSameViewsAndHonorsRtl() throws Exception {
        RuntimeSourceFixture.verify("""
            static class MeasureSpec {
                static final int EXACTLY=1;
                static int getSize(int value){return value;} static int makeMeasureSpec(int size,int mode){return size;}
            }
            static class LayoutParams { static int MATCH_PARENT=-1,WRAP_CONTENT=-2; int width,height; }
            static class View {
                LayoutParams params=new LayoutParams(); int width,height,left,top,right,bottom;
                LayoutParams getLayoutParams(){return params;} void setMinimumHeight(int value){}
                void measure(int w,int h){width=w;height=h;}
                int getMeasuredWidth(){return width;} int getMeasuredHeight(){return height;}
                void layout(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
            }
            static class LinearLayout extends View {
                static int VERTICAL=1,HORIZONTAL=0; int orientation; View child=new View();
                void setOrientation(int v){orientation=v;} int getChildCount(){return 1;} View getChildAt(int i){return child;}
            }
            LinearLayout navigation=new LinearLayout(); View content=new View(),horizontal=new View(),vertical=new View();
            int sidebar=176,strip=48,breakpoint=720,measuredWidth,measuredHeight,direction;
            static final int LAYOUT_DIRECTION_RTL=1; boolean wide;
            int getLayoutDirection(){return direction;} void setMeasuredDimension(int w,int h){measuredWidth=w;measuredHeight=h;}
            """ + RuntimeSourceFixture.methods("UiAdaptivePane", "wide", "onMeasure", "onLayout") + """
            public static void verify() {
                Fixture f=new Fixture(); Object content=f.content, navigation=f.navigation;
                f.onMeasure(1000,700); f.onLayout(true,0,0,1000,700);
                check(f.wide && f.content.width==824 && f.content.height==700 && f.content.left==176, "wide sidebar");
                check(f.navigation.orientation==1 && f.navigation.child.params.width==-1, "wide navigation");
                f.onMeasure(400,600); f.onLayout(true,0,0,400,600);
                check(!f.wide && f.content.width==400 && f.content.height==552 && f.content.top==48, "narrow strip");
                check(f.navigation.orientation==0 && f.navigation.child.params.width==-2, "narrow navigation");
                f.direction=1; f.onMeasure(800,500); f.onLayout(true,0,0,800,500);
                check(f.horizontal.left==624 && f.content.left==0 && f.content.right==624, "RTL sidebar");
                f.onMeasure(20,20); f.onLayout(true,0,0,20,20);
                check(f.content.height==0 && f.content.width==20, "negative tiny content");
                check(f.content==content && f.navigation==navigation, "resize replaced content");
            }
            """);
    }
}
