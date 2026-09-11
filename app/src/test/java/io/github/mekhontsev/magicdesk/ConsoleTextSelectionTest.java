package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ConsoleTextSelectionTest {
    @Test public void handlesAdjustEitherBoundaryInScrolledText() throws Exception {
        verify("""
                View view=new View(); view.mTopRow=-5; view.mTouchSelection=true;
                view.mSelectionStartColumn=2; view.mSelectionStartRow=-4;
                view.mSelectionEndColumn=5; view.mSelectionEndRow=-3;
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
                view.mSelectionStartColumn=8; view.mSelectionStartRow=4;
                view.mSelectionEndColumn=3; view.mSelectionEndRow=2;
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
                view.mSelectionStartColumn=1; view.mSelectionStartRow=1;
                view.mSelectionEndColumn=5; view.mSelectionEndRow=1;
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

    private static void verify(final String body) throws Exception {
        RuntimeSourceFixture.verify("""
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
                    int mSelectionStartColumn=NO_SELECTION,mSelectionStartRow=NO_SELECTION;
                    int mSelectionEndColumn=NO_SELECTION,mSelectionEndRow=NO_SELECTION;
                    int mTopRow,mColumns=20,mRows=10,mContentPadding=6,stops;
                    boolean mTouchSelection,mSelecting,focused=true;
                    boolean hasWindowFocus() { return focused; } void invalidate() {} void stopFling() { stops++; }
                """ + RuntimeSourceFixture.methods("ConsoleTerminalView", "updateSelectionHandles",
                        "normalizeSelection", "moveSelectionHandle", "selectedText", "hasSelection",
                        "clearSelection", "position", "clamp")
                + "}\npublic static void verify() {\n" + body + "\n}");
    }
}
