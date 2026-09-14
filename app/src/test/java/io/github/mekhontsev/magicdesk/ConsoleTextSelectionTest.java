package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ConsoleTextSelectionTest {
    @Test public void handlesAdjustEitherBoundaryInScrolledText() throws Exception {
        verify("""
                View view=new View(); view.mViewport.jumpTo(-5,20); view.mTouchSelection=true;
                view.mViewport.select(2,-4,5,-3);
                view.updateSelectionHandles();
                check(Arrays.equals(view.mSelectionHandles.anchors,new float[]{26,26,66,36}),
                        "handles do not point at terminal-cell boundaries");
                view.moveSelectionHandle(true,16,16);
                check(view.selectedText().equals("1,-5:5,-3"), "start handle did not extend selection");
                view.moveSelectionHandle(false,82,46);
                check(view.selectedText().equals("1,-5:7,-2"), "end handle lost inclusive last cell");
                check(view.stops==2, "handle movement retained fling");
                """);
    }

    @Test public void handlesNormalizeReverseSelectionAndCannotCross() throws Exception {
        verify("""
                View view=new View(); view.mTouchSelection=true;
                view.mViewport.select(8,4,3,2);
                view.updateSelectionHandles();
                check(view.selectedText().equals("3,2:8,4"), "reverse drag not normalized");
                view.moveSelectionHandle(true,1000,1000);
                check(view.selectedText().equals("8,4:8,4"), "start crossed end");
                view.moveSelectionHandle(false,-100,-100);
                check(view.selectedText().equals("8,4:8,4"), "end crossed start");
                """);
    }

    @Test public void handlesHideWhileSelectingOrWhenWindowLosesFocus() throws Exception {
        verify("""
                View view=new View(); view.mTouchSelection=true;
                view.mViewport.select(1,1,5,1);
                view.updateSelectionHandles(); check(view.mSelectionHandles.visible, "touch selection has no handles");
                view.mSelecting=true; view.updateSelectionHandles();
                check(!view.mSelectionHandles.visible, "handles steal initial drag");
                view.mSelecting=false; view.focused=false; view.updateSelectionHandles();
                check(!view.mSelectionHandles.visible && view.hasSelection(), "focus loss destroyed selection");
                view.focused=true; view.updateSelectionHandles();
                check(view.mSelectionHandles.visible, "returning focus lost handles");
                view.clearSelection(); check(!view.mSelectionHandles.visible && !view.hasSelection(), "clear left handles");
                """);
    }

    @Test public void handlesShareTheFractionalTransformAndReachThePartialBottomRow() throws Exception {
        verify("""
                View view=new View(); view.mViewport.jumpTo(-5,20); view.mViewport.scroll(7,20); view.mTouchSelection=true;
                view.mViewport.select(2,-5,5,-3);
                view.updateSelectionHandles();
                check(Arrays.equals(view.mSelectionHandles.anchors,new float[]{26,9,66,29}),
                        "handles did not follow fractional content offset");
                view.moveSelectionHandle(true,16,9);
                check(view.selectedText().equals("1,-5:5,-3"), "top fragment cannot be selected");
                view.moveSelectionHandle(false,66,105);
                check(view.selectedText().equals("1,-5:5,5"), "bottom fragment cannot be selected");
                """);
    }

    private static void verify(final String body) throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class Renderer { float cellWidth() { return 10; } float cellHeight() { return 10; } }
                static class Session {
                    Session emulator() { return this; }
                    String getSelectedText(int x1,int y1,int x2,int y2) { return x1+","+y1+":"+x2+","+y2; }
                }
                static class Handles {
                    boolean visible; float[] anchors;
                    void hide() { visible=false; }
                    void update(float a,float b,float c,float d) { visible=true;anchors=new float[]{a,b,c,d}; }
                }
                static class View {
                    static final int NO_SELECTION=Integer.MIN_VALUE;
                    final Handles mSelectionHandles=new Handles(); final Session mSession=new Session();
                    final Renderer mRenderer=new Renderer();
                    final TerminalViewport mViewport=new TerminalViewport();
                    { mViewport.metrics(10,10,6); mViewport.resize(212,112); }
                    int stops;
                    boolean mTouchSelection,mSelecting,focused=true;
                    boolean hasWindowFocus() { return focused; } void invalidate() {} void stopFling() { stops++; }
                """ + RuntimeSourceFixture.methods("ConsoleTerminalView", "updateSelectionHandles",
                        "moveSelectionHandle", "selectedText", "hasSelection",
                        "clearSelection", "rowBottomY", "viewportRowAt", "visibleRowCount")
                + "}\npublic static void verify() {\n" + body + "\n}", "TerminalViewport");
    }
}
