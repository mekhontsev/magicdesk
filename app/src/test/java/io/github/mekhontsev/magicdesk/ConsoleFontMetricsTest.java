package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Exercises the production renderer/PTY size boundary, not a second resize policy. */
public final class ConsoleFontMetricsTest {
    @Test public void changingFontResizesTheExistingSessionAndPreservesScrollback() throws Exception {
        verify("""
                View view=new View(); view.refreshFontMetrics(); Session original=view.mSession;
                int columns=view.mColumns, rows=view.mRows, resizes=original.resizes;
                view.mTopRow=-5; view.setFontSizeSp(24);
                check(view.mSession==original && original.resizes==resizes+1, "font size replaced or did not resize PTY");
                check(view.mColumns<columns && view.mRows<rows, "larger font did not reduce rows and columns");
                check(view.mTopRow==-5, "font resize reset scrollback");
                view.setFontSizeSp(24);
                check(original.resizes==resizes+1, "same size repeated PTY resize");
                check(TypedValue.lastUnit==TypedValue.COMPLEX_UNIT_SP, "font did not use Android sp conversion");
                """);
    }

    @Test public void cellMetricChangesReachPtyEvenWhenRowsAndColumnsAreUnchanged() throws Exception {
        verify("""
                View view=new View(); view.width=20; view.height=20; view.refreshFontMetrics();
                check(view.mColumns==2 && view.mRows==2, "fixture must keep grid at minimum size");
                int resizes=view.mSession.resizes, cell=view.mAppliedCellHeight;
                view.setFontSizeSp(20);
                check(view.mColumns==2 && view.mRows==2 && view.mAppliedCellHeight!=cell, "fixture changed grid");
                check(view.mSession.resizes==resizes+1, "cell-only font change was dropped");
                """);
    }

    @Test public void configurationChangesRecomputeMetricsWithoutChangingSpPreference() throws Exception {
        verify("""
                View view=new View(); view.refreshFontMetrics(); int resizes=view.mSession.resizes;
                float cell=view.mRenderer.cellHeight(); view.resources.metrics.fontScale=2;
                view.onConfigurationChanged(new Configuration());
                check(view.mFontSizeSp==14 && view.mRenderer.cellHeight()>cell, "configuration overwrote font preference");
                check(view.mSession.resizes==resizes+1, "configuration did not resize PTY");
                """);
    }

    @Test public void unmeasuredViewWaitsForLayoutBeforeResizingRetainedPty() throws Exception {
        verify("""
                View view=new View(); view.width=0; view.height=0; view.setFontSizeSp(20);
                check(view.mSession.resizes==0, "font change resized an unmeasured view");
                view.width=600; view.height=400; view.resizeTerminal();
                check(view.mSession.resizes==1, "measured view did not resize PTY");
                """);
    }

    private static void verify(final String body) throws Exception {
        RuntimeSourceFixture.verify("""
                static class Configuration {}
                static class Metrics { float density=1, fontScale=1; }
                static class R { static class font { static final int console_mono=1; } }
                static class Resources { final Metrics metrics=new Metrics(); Metrics getDisplayMetrics() { return metrics; }
                    Object getFont(int id) { return new Object(); } }
                static class TypedValue {
                    static final int COMPLEX_UNIT_SP=2; static int lastUnit;
                    static float applyDimension(int unit,float sp,Metrics metrics) {
                        lastUnit=unit; return sp*metrics.density*metrics.fontScale;
                    }
                }
                static class ViewConfiguration {
                    static ViewConfiguration get(Object c) { return new ViewConfiguration(); }
                    int getScaledTouchSlop() { return 2; }
                }
                static class MagicDeskTerminalRenderer {
                    final float size;
                    MagicDeskTerminalRenderer(Object family,float pixels) { size=pixels; }
                    float cellWidth() { return (float)Math.ceil(size*0.6f); }
                    float cellHeight() { return (float)Math.ceil(size*1.2f); }
                }
                static class Session {
                    int columns=80,rows=24,resizes;
                    int columns() { return columns; } int rows() { return rows; }
                    void resize(int c,int r,int cw,int ch) { columns=c; rows=r; resizes++; }
                }
                static class ConsolePreferences { static final int MIN_FONT_SIZE_SP=8, MAX_FONT_SIZE_SP=40;
                """ + RuntimeSourceFixture.methods("ConsolePreferences", "clampFontSize") + """
                }
                static class BaseView { void onConfigurationChanged(Configuration c) {} }
                static class View extends BaseView {
                    final Resources resources=new Resources(); Session mSession=new Session();
                    MagicDeskTerminalRenderer mRenderer;
                    int width=600,height=400,mFontSizeSp=14,mColumns=80,mRows=24,mTopRow;
                    int mAppliedCellWidth,mAppliedCellHeight,mContentPadding,mTouchSlop;
                    Resources getResources() { return resources; } Object getContext() { return this; }
                    int getWidth() { return width; } int getHeight() { return height; }
                    void invalidate() {} void clearSelection() {}
                    void clampTopRow() { mTopRow=Math.min(0,Math.max(-20,mTopRow)); }
                """ + RuntimeSourceFixture.methods("ConsoleTerminalView", "setFontSizeSp", "refreshFontMetrics",
                        "onConfigurationChanged", "resizeTerminal", "cellWidth", "cellHeight")
                + "}\npublic static void verify() {\n" + body + "\n}");
    }
}
