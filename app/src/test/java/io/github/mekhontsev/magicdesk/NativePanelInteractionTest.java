package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class NativePanelInteractionTest {
    @Test public void siblingOutsideTouchDoesNotDismissRevealOrStartPointerDwell() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class ShellPanel { enum Edge {
                    TOP, BOTTOM, LEFT, RIGHT;
                    boolean vertical() { return this == LEFT || this == RIGHT; }
                } }
                static class InputDevice { static final int SOURCE_TOUCHSCREEN=1; }
                static class MotionEvent {
                    static final int ACTION_HOVER_ENTER=1,ACTION_HOVER_MOVE=2,ACTION_DOWN=3,ACTION_MOVE=4,
                        ACTION_UP=5,ACTION_HOVER_EXIT=6,ACTION_CANCEL=7,ACTION_OUTSIDE=8;
                    int action; float x,y; boolean touch;
                    MotionEvent(int a,float x,float y,boolean t) {action=a;this.x=x;this.y=y;touch=t;}
                    int getActionMasked(){return action;} float getRawX(){return x;} float getRawY(){return y;}
                    boolean isFromSource(int source){return touch;}
                }
                boolean mReleased, mTouchEdgeEnabled=true;
                int mTouchSlop=8;
                ShellPanel.Edge mTouchEdge;
                PointerEdgeRevealState mPointerState = new PointerEdgeRevealState();
                TouchEdgeRevealState mTouchState = new TouchEdgeRevealState();
                PointerEdgeRevealState.TimerAction last = PointerEdgeRevealState.TimerAction.NONE;
                boolean contains(float x,float y){return x>=10 && x<50 || x>=100 && x<150;}
                ShellPanel.Edge edgeAt(float x,float y){return contains(x,y)?ShellPanel.Edge.BOTTOM:null;}
                void applyTimerAction(PointerEdgeRevealState.TimerAction action){last=action;}
                void applyTouchAction(TouchEdgeRevealState.Action action,boolean afterDispatch){}
                public static void verify() {
                    Fixture f=new Fixture(); f.mPointerState.setArmed(true); f.mTouchState.setArmed(true);
                    f.mTouchState.reveal();
                    f.onEdgeInput(new MotionEvent(MotionEvent.ACTION_OUTSIDE,110,0,true));
                    check(f.mTouchState.isRevealed(),"sibling notification dismissed reveal");
                    check(f.last==PointerEdgeRevealState.TimerAction.NONE,"touch armed pointer dwell");
                    check(!f.mPointerState.onRevealTimeout(),"touch created pointer reveal state");
                    f.onEdgeInput(new MotionEvent(MotionEvent.ACTION_OUTSIDE,75,0,true));
                    check(!f.mTouchState.isRevealed(),"gap touch failed to dismiss");
                    f.onEdgeInput(new MotionEvent(MotionEvent.ACTION_OUTSIDE,110,0,false));
                    check(f.last==PointerEdgeRevealState.TimerAction.START_REVEAL,"mouse no longer reveals");
                }
                """ + RuntimeSourceFixture.methods("DesktopTaskbarRevealController",
                        "onEdgeInput", "handleTouchEdgeInput", "gestureX", "gestureY"),
                "PointerEdgeRevealState", "TouchEdgeRevealState");
    }

    @Test public void hostHitTestingKeepsFloatingGapsOutOfBothShownAndRevealInput() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class Rect {
                    int left, top, right, bottom;
                    Rect(int l, int t, int r, int b) { left=l; top=t; right=r; bottom=b; }
                }
                static class ShellPanel { enum Edge {
                    TOP, BOTTOM, LEFT, RIGHT;
                    boolean vertical() { return this == LEFT || this == RIGHT; }
                } }
                static class PanelGeometry {
                """ + RuntimeSourceFixture.methods("PanelGeometry", "reveal", "presented") + """
                }
                record Panel(ShellPanel.Edge edge, Rect paint, Rect output) { Rect frame() { return paint; } }
                List<Panel> mPanels;
                public static void verify() {
                    Fixture f = new Fixture();
                    Rect output = new Rect(100,200,1100,900);
                    f.mPanels = List.of(
                        new Panel(ShellPanel.Edge.BOTTOM, new Rect(200,830,500,880), output),
                        new Panel(ShellPanel.Edge.BOTTOM, new Rect(700,760,1000,810), output),
                        new Panel(ShellPanel.Edge.LEFT, new Rect(120,300,170,600), output));
                    check(f.contains(300,850,false,4), "shown panel missing");
                    check(f.contains(800,790,false,4), "stacked panel missing");
                    check(!f.contains(600,850,false,4), "horizontal gap captures pointer");
                    check(!f.contains(300,820,false,4), "vertical gap captures pointer");
                    check(f.contains(300,899,true,4), "first reveal missing");
                    check(f.contains(800,899,true,4), "second reveal missing");
                    check(!f.contains(600,899,true,4), "reveal union fills gap");
                    check(f.contains(102,400,true,4), "left reveal missing");
                    check(!f.contains(102,700,true,4), "left reveal extends past panel");
                    check(!f.contains(500,899,true,4), "half-open edge included");
                    check(f.contains(499.9f,899,true,4), "fractional interior excluded");
                }
                """ + RuntimeSourceFixture.methods("DesktopTaskbarHost", "contains", "bounds"), "ShellBounds");
    }

    @Test public void popupInteractionOwnersUseIndependentDefensivelyCopiedRectangles() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Rect {
                    int left, top, right, bottom;
                    Rect(int l, int t, int r, int b) { left=l; top=t; right=r; bottom=b; }
                    Rect(Rect r) { this(r.left,r.top,r.right,r.bottom); }
                    boolean contains(int x,int y) { return x>=left && x<right && y>=top && y<bottom; }
                }
                List<Rect> mInteractionOwnerBounds = List.of();
                public static void verify() {
                    Fixture f = new Fixture();
                    Rect first = new Rect(10,20,100,50), second = new Rect(200,20,300,50);
                    f.setInteractionOwnerBounds(List.of(first,second));
                    first.left=1000;
                    check(f.interactionOwnerContains(50,30), "caller mutated retained bounds");
                    check(f.interactionOwnerContains(250,30), "second panel lost");
                    check(!f.interactionOwnerContains(150,30), "empty gap became owner input");
                    check(!f.interactionOwnerContains(100,30), "half-open edge became input");
                    f.setInteractionOwnerBounds(null);
                    check(!f.interactionOwnerContains(50,30), "cleared owner retained input");
                }
                """ + RuntimeSourceFixture.methods("DesktopPanelWindowController",
                        "setInteractionOwnerBounds", "interactionOwnerContains"));
    }

    @Test public void allEdgesMapInwardSwipesToTheExistingTouchRevealState() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class ShellPanel { enum Edge {
                    TOP, BOTTOM, LEFT, RIGHT;
                    boolean vertical() { return this == LEFT || this == RIGHT; }
                } }
                public static void verify() {
                    for (var edge : ShellPanel.Edge.values()) {
                        float x=100,y=200;
                        float dx=edge==ShellPanel.Edge.LEFT?20:edge==ShellPanel.Edge.RIGHT?-20:0;
                        float dy=edge==ShellPanel.Edge.TOP?20:edge==ShellPanel.Edge.BOTTOM?-20:0;
                        TouchEdgeRevealState state = new TouchEdgeRevealState();
                        state.setArmed(true);
                        state.onDown(gestureX(edge,x,y),gestureY(edge,x,y));
                        check(state.onMove(gestureX(edge,x-dx,y-dy),gestureY(edge,x-dx,y-dy),8)
                            == TouchEdgeRevealState.Action.NONE, "outward swipe reveals " + edge);
                        check(state.onMove(gestureX(edge,x+dx,y+dy),gestureY(edge,x+dx,y+dy),8)
                            == TouchEdgeRevealState.Action.REVEAL, "inward swipe failed " + edge);
                        check(state.isRevealed(), "missing reveal state");
                    }
                }
                """ + RuntimeSourceFixture.methods("DesktopTaskbarRevealController", "gestureX", "gestureY"),
                "TouchEdgeRevealState");
    }

    @Test public void pointerExitUsesItsActionAndSiblingEntryCancelsHide() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class MotionEvent {
                    static final int ACTION_HOVER_ENTER=1,ACTION_HOVER_MOVE=2,ACTION_DOWN=3,ACTION_MOVE=4,
                        ACTION_UP=5,ACTION_HOVER_EXIT=6,ACTION_CANCEL=7,ACTION_OUTSIDE=8;
                    int action; float x,y;
                    MotionEvent(int a,float x,float y) {action=a;this.x=x;this.y=y;}
                    int getActionMasked(){return action;} float getRawX(){return x;} float getRawY(){return y;}
                }
                boolean mReleased;
                PointerEdgeRevealState mPointerState = new PointerEdgeRevealState();
                PointerEdgeRevealState.TimerAction last;
                boolean handleTouchEdgeInput(MotionEvent event){return false;}
                boolean contains(float x,float y){return x>=10 && x<50 || x>=100 && x<150;}
                void applyTimerAction(PointerEdgeRevealState.TimerAction action){last=action;}
                public static void verify() {
                    Fixture f=new Fixture(); f.mPointerState.setArmed(true);
                    f.onEdgeInput(new MotionEvent(MotionEvent.ACTION_HOVER_ENTER,20,0));
                    check(f.last==PointerEdgeRevealState.TimerAction.START_REVEAL,"edge did not arm");
                    f.onEdgeInput(new MotionEvent(MotionEvent.ACTION_HOVER_MOVE,75,0));
                    check(f.last==PointerEdgeRevealState.TimerAction.CANCEL_REVEAL,"gap kept dwell armed");
                    f.onEdgeInput(new MotionEvent(MotionEvent.ACTION_HOVER_ENTER,110,0));
                    check(f.mPointerState.onRevealTimeout(),"second panel did not reveal");
                    f.onEdgeInput(new MotionEvent(MotionEvent.ACTION_HOVER_EXIT,20,0));
                    check(f.last==PointerEdgeRevealState.TimerAction.START_HIDE,"exit with interior coordinates kept panel open");
                    f.onEdgeInput(new MotionEvent(MotionEvent.ACTION_HOVER_ENTER,20,0));
                    check(f.last==PointerEdgeRevealState.TimerAction.CANCEL_HIDE,"sibling entry failed to retain panel");
                    check(!f.mPointerState.onHideTimeout(),"cross-panel move hid chrome");
                    f.onEdgeInput(new MotionEvent(MotionEvent.ACTION_HOVER_EXIT,20,0));
                    check(f.mPointerState.onHideTimeout(),"exit at last interior position did not hide");
                    f.onEdgeInput(new MotionEvent(MotionEvent.ACTION_HOVER_ENTER,110,0));
                    check(f.mPointerState.onRevealTimeout(),"panel did not reveal again");
                    f.onEdgeInput(new MotionEvent(MotionEvent.ACTION_OUTSIDE,75,0));
                    check(f.last==PointerEdgeRevealState.TimerAction.START_HIDE,"outside gap did not hide");
                    check(f.mPointerState.onHideTimeout(),"hide was not retained");
                }
                """ + RuntimeSourceFixture.methods("DesktopTaskbarRevealController", "onEdgeInput"),
                "PointerEdgeRevealState");
    }
}
