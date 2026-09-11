package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Runs the View's input handlers with a bounded transcript and recorded terminal input. */
public final class ConsoleTerminalScrollingTest {
    @Test public void fingerFollowsTheContentAndStopsAtTranscriptBounds() throws Exception {
        verify("""
                View view = new View();
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN, 20));
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE, 50));
                check(view.mTopRow == -3, "dragging down must reveal older output");
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE, 40));
                check(view.mTopRow == -2, "reversing drag must move toward live output");
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE, 500));
                check(view.mTopRow == -20, "scroll exceeded transcript");
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE, 0));
                check(view.mTopRow == 0, "scroll exceeded live output");
                view.onTouchEvent(touch(MotionEvent.ACTION_UP, 0));
                check(view.keyboardRequests == 0, "scroll opened keyboard");
                """);
    }

    @Test public void touchScrollInMouseTrackingModeIsNotAMouseDrag() throws Exception {
        verify("""
                View view = new View();
                view.mSession.emulator.tracking = true;
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN, 20));
                check(view.mSession.emulator.events.isEmpty(), "touch down started mouse selection");
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE, 50));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP, 50));
                check(view.mSession.emulator.events.equals(List.of("64:true", "64:true", "64:true")),
                        "touch scroll did not send wheel-up events");
                check(view.mTopRow == 0, "application scroll changed transcript viewport");
                check(view.keyboardRequests == 0, "scroll opened keyboard");
                """);
    }

    @Test public void mouseTrackingTapAndHardwareDragKeepTheirMeaning() throws Exception {
        verify("""
                View view = new View();
                view.mSession.emulator.tracking = true;
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN, 20));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP, 20));
                check(view.mSession.emulator.events.equals(List.of("0:true", "0:false")), "touch tap lost click");
                view.mSession.emulator.events.clear();
                view.onTouchEvent(mouse(MotionEvent.ACTION_DOWN, 20));
                view.onTouchEvent(mouse(MotionEvent.ACTION_MOVE, 50));
                view.onTouchEvent(mouse(MotionEvent.ACTION_UP, 50));
                check(view.mSession.emulator.events.equals(List.of("0:true", "32:true", "0:false")),
                        "hardware mouse drag changed");
                """);
    }

    @Test public void smallFingerAndWheelDeltasAreAccumulated() throws Exception {
        verify("""
                View view = new View();
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN, 20));
                for(int y=23;y<=30;y++) view.onTouchEvent(touch(MotionEvent.ACTION_MOVE,y));
                check(view.mTopRow == -1, "small touch deltas were lost");
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,30));
                MotionEvent wheel=mouse(MotionEvent.ACTION_SCROLL,20); wheel.wheel=0.25f;
                for(int i=0;i<4;i++) view.onGenericMotionEvent(wheel);
                check(view.mTopRow == -4, "high-resolution wheel deltas were lost or amplified");
                check(view.computeVerticalScrollRange()==30 && view.computeVerticalScrollExtent()==10
                        && view.computeVerticalScrollOffset()==16, "scrollbar does not describe viewport");
                """);
    }

    @Test public void shiftWheelReadsHistoryWithoutSendingApplicationInput() throws Exception {
        verify("""
                View view = new View(); view.mSession.emulator.tracking=true;
                MotionEvent wheel=mouse(MotionEvent.ACTION_SCROLL,20); wheel.wheel=1; wheel.shift=true;
                view.onGenericMotionEvent(wheel);
                check(view.mTopRow == -3, "Shift+wheel did not read local history");
                check(view.mSession.emulator.events.isEmpty(), "Shift+wheel reached terminal application");
                """);
    }

    @Test public void alternateScreenWithoutMouseTrackingReceivesArrowKeys() throws Exception {
        verify("""
                View view = new View(); view.mSession.emulator.alternate=true;
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,20));
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE,40));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,40));
                check(view.mSession.output.equals("upup"), "alternate screen cannot scroll");
                check(view.mTopRow==0, "alternate screen created fake scrollback");
                """);
    }

    @Test public void selectionAndCancelledTouchesNeverBecomeTaps() throws Exception {
        verify("""
                View view = new View(); view.mSession.emulator.tracking=true;
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,20));
                view.beginSelection(touch(MotionEvent.ACTION_DOWN,20));
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE,50));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,50));
                check(view.mTopRow==0 && view.mSession.emulator.events.isEmpty(), "selection became scroll or click");
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,20));
                view.onTouchEvent(touch(MotionEvent.ACTION_CANCEL,20));
                check(view.keyboardRequests==0 && view.mSession.emulator.events.isEmpty(), "cancel became tap");
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,20));
                MotionEvent horizontal=touch(MotionEvent.ACTION_MOVE,20); horizontal.x=60;
                view.onTouchEvent(horizontal);
                MotionEvent up=touch(MotionEvent.ACTION_UP,20); up.x=60;
                view.onTouchEvent(up);
                check(view.mSession.emulator.events.isEmpty(), "horizontal swipe became a tap");
                """);
    }

    @Test public void shiftPageKeysReadHistoryWhileOrdinaryKeysReachThePty() throws Exception {
        verify("""
                View view = new View(); KeyEvent event=new KeyEvent(); event.shift=true;
                view.onKeyDown(KeyEvent.KEYCODE_PAGE_UP,event);
                check(view.mTopRow == -10 && view.mSession.output.isEmpty(), "Shift+PageUp escaped to PTY");
                view.onKeyDown(KeyEvent.KEYCODE_PAGE_DOWN,event);
                check(view.mTopRow == 0 && view.mSession.output.isEmpty(), "Shift+PageDown escaped to PTY");
                event.shift=false; view.onKeyDown(KeyEvent.KEYCODE_PAGE_UP,event);
                check(view.mSession.output.equals("key"), "ordinary PageUp did not reach PTY");
                """);
    }

    @Test public void controlWheelZoomsOnlyThisWindowWithoutTerminalInput() throws Exception {
        verify("""
                View view = new View(); View other = new View(); view.mSession.emulator.tracking=true;
                MotionEvent wheel=mouse(MotionEvent.ACTION_SCROLL,20); wheel.ctrl=true; wheel.wheel=0.25f;
                for(int i=0;i<4;i++) view.onGenericMotionEvent(wheel);
                check(view.fontSizeSp()==15 && other.fontSizeSp()==14, "zoom is not local to this window");
                check(view.fontRefreshes==1, "fractional zoom reflows before a font step");
                check(view.mTopRow==0 && view.mSession.emulator.events.isEmpty()
                        && view.mSession.output.isEmpty(), "zoom reached tmux");
                wheel.wheel=100; view.onGenericMotionEvent(wheel);
                check(view.fontSizeSp()==40, "zoom exceeded maximum font size");
                wheel.wheel=-100; view.onGenericMotionEvent(wheel);
                check(view.fontSizeSp()==8, "zoom exceeded minimum font size");
                """);
    }

    @Test public void pinchConsumesTheWholeGestureIncludingTheRemainingFinger() throws Exception {
        verify("""
                View view = new View(); view.mSession.emulator.tracking=true;
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,20));
                MotionEvent two=touch(MotionEvent.ACTION_POINTER_DOWN,20); two.pointers=2;
                view.onTouchEvent(two);
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE,60));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,60));
                check(view.mGestures.cancelled==1, "pinch retained long-press recognition");
                check(view.mTopRow==0 && !view.mSelecting && view.mSession.emulator.events.isEmpty()
                        && view.keyboardRequests==0, "pinch became scroll, click, or selection");
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,20));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,20));
                check(view.mSession.emulator.events.equals(List.of("0:true", "0:false")), "pinch blocked the next tap");
                """);
    }

    @Test public void pinchAccumulatesSmallScaleFactorsBeforeReflow() throws Exception {
        verify("""
                View view = new View(); ScaleGestureDetector scale=new ScaleGestureDetector(); scale.factor=1.01f;
                view.onScaleBegin(scale);
                for(int i=0;i<4;i++) view.onScale(scale);
                check(view.fontSizeSp()==15 && view.fontRefreshes==1, "small scale factors were lost or reflowed repeatedly");
                scale.factor=100; view.onScale(scale); check(view.fontSizeSp()==40, "pinch exceeds limit");
                scale.factor=0.001f; view.onScale(scale); check(view.fontSizeSp()==8, "pinch exceeds lower limit");
                """);
    }

    @Test public void linksRequireATapAndDoNotStealTerminalMouseReporting() throws Exception {
        verify("""
                View view=new View(); view.link=new TerminalHyperlink("https://example.com");
                List<String> opened=new ArrayList<>();
                view.mClipboardActions=new ClipboardActions() {
                    public void copySelection() {} public void pasteClipboard() {}
                    public void showLink(TerminalHyperlink link) { opened.add(link.uri()); }
                };
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN, 10));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP, 10));
                check(opened.size()==1 && view.keyboardRequests==0, "link tap did not use explicit actions");
                view.mSession.emulator.tracking=true;
                view.onTouchEvent(mouse(MotionEvent.ACTION_DOWN, 10));
                view.onTouchEvent(mouse(MotionEvent.ACTION_UP, 10));
                check(opened.size()==1 && view.mSession.emulator.events.size()==2, "link stole TUI input");
                MotionEvent down=mouse(MotionEvent.ACTION_DOWN,10); down.ctrl=true;
                MotionEvent up=mouse(MotionEvent.ACTION_UP,10); up.ctrl=true;
                view.onTouchEvent(down); view.onTouchEvent(up);
                check(opened.size()==2 && view.mSession.emulator.events.size()==2, "Ctrl link sent TUI input");
                view.mSession.emulator.tracking=false;
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,10));
                view.onTouchEvent(touch(MotionEvent.ACTION_CANCEL,10));
                check(opened.size()==2, "cancel activated link");
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,10));
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE,50));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,50));
                check(opened.size()==2, "scroll activated link");
                """);
    }

    private static void verify(final String body) throws Exception {
        RuntimeSourceFixture.verify("""
                static class Point { int x,y; Point(int x,int y) { this.x=x; this.y=y; } }
                static class MotionEvent {
                    static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, ACTION_CANCEL=3,
                            ACTION_POINTER_DOWN=5, ACTION_SCROLL=8;
                    static final int AXIS_VSCROLL=9;
                    int action, pointers=1; final float y; boolean touch=true, shift, ctrl; float wheel, x=10;
                    MotionEvent(int action,float y) { this.action=action; this.y=y; }
                    int getActionMasked() { return action; } float getX() { return x; }
                    float getY() { return y; } float getAxisValue(int axis) { return wheel; }
                    int getMetaState() { return ctrl ? KeyEvent.META_CTRL_ON : 0; }
                    int getPointerCount() { return pointers; }
                    void setAction(int value) { action=value; } void recycle() {}
                    static MotionEvent obtain(MotionEvent e) { return new MotionEvent(e.action,e.y); }
                }
                static MotionEvent touch(int action,float y) { return new MotionEvent(action,y); }
                static MotionEvent mouse(int action,float y) { var e=touch(action,y); e.touch=false; return e; }
                static class TerminalEmulator {
                    static final int MOUSE_LEFT_BUTTON=0, MOUSE_LEFT_BUTTON_MOVED=32,
                            MOUSE_WHEELUP_BUTTON=64, MOUSE_WHEELDOWN_BUTTON=65;
                    boolean tracking, alternate; final List<String> events=new ArrayList<>();
                    boolean isMouseTrackingActive() { return tracking; }
                    boolean isAlternateBufferActive() { return alternate; }
                    boolean isCursorKeysApplicationMode() { return false; }
                    boolean isKeypadApplicationMode() { return false; }
                    TerminalEmulator getScreen() { return this; }
                    int getActiveTranscriptRows() { return alternate ? 0 : 20; }
                    void sendMouseEvent(int button,int x,int y,boolean pressed) { events.add(button+":"+pressed); }
                }
                static class KeyEvent {
                    static final int KEYCODE_DPAD_UP=19, KEYCODE_DPAD_DOWN=20, KEYCODE_BACK=4,
                            KEYCODE_PAGE_UP=92, KEYCODE_PAGE_DOWN=93, KEYCODE_C=31, KEYCODE_V=50, META_CTRL_ON=4096;
                    boolean shift, alt, ctrl;
                    boolean isShiftPressed() { return shift; } boolean isAltPressed() { return alt; }
                    boolean isCtrlPressed() { return ctrl; }
                }
                static class KeyHandler {
                    static String getCode(int key,int mods,boolean cursor,boolean keypad) { return key==19?"up":"down"; }
                }
                static class Session {
                    final TerminalEmulator emulator=new TerminalEmulator(); String output="";
                    TerminalEmulator emulator() { return emulator; } void write(String s) { output+=s; }
                }
                static class Renderer { float cellHeight() { return 10; } }
                static class Gestures {
                    int cancelled;
                    void onTouchEvent(MotionEvent e) { if(e.action==MotionEvent.ACTION_CANCEL) cancelled++; }
                }
                static class ScaleGestureDetector {
                    float factor=1; float getScaleFactor() { return factor; }
                    void onTouchEvent(MotionEvent e) {}
                }
                static class ConsolePreferences {
                    static final int MIN_FONT_SIZE_SP=8, MAX_FONT_SIZE_SP=40;
                """ + RuntimeSourceFixture.methods("ConsolePreferences", "clampFontSize") + """
                }
                static class BaseView {
                    boolean onGenericMotionEvent(MotionEvent e) { return false; }
                    boolean onKeyDown(int key,KeyEvent event) { return false; }
                }
                static class Input { String key(KeyEvent e,TerminalEmulator t) { return "key"; } }
                record TerminalHyperlink(String uri) {}
                interface ClipboardActions { void copySelection(); void pasteClipboard(); void showLink(TerminalHyperlink link); }
                static class View extends BaseView {
                    Session mSession=new Session(); Renderer mRenderer=new Renderer(); Gestures mGestures=new Gestures();
                    ScaleGestureDetector mScaleGestures=new ScaleGestureDetector();
                    boolean mFontScaleGesture; int mFontSizeSp=14, fontRefreshes; float mPinchFontSizeSp, mFontWheelRemainder;
                    Input mInput=new Input(); ClipboardActions mClipboardActions;
                    TerminalHyperlink link; TerminalHyperlink linkAt(MotionEvent event) { return link; }
                    static final int SCROLL_ROWS=3;
                    int mRows=10, mTopRow, mTouchSlop=2, mTerminalMouseButton, keyboardRequests;
                    float mDownX, mDownY, mLastTouchY, mTouchScrollRemainder, mWheelScrollRemainder;
                    boolean mTouchScrolling, mSelecting, mTerminalMousePress;
                    void requestFocus() {} void invalidate() {} void awakenScrollBars() {}
                    void refreshFontMetrics() { fontRefreshes++; }
                    void scrollToBottom() { mTopRow=0; }
                    void clearSelection() { mSelecting=false; } void beginSelection(MotionEvent e) { mSelecting=true; }
                    void updateSelection(Point cell) {} void showSoftKeyboard() { keyboardRequests++; }
                    static boolean isTouch(MotionEvent e) { return e.touch; }
                    static boolean isShiftPressed(MotionEvent e) { return e.shift; }
                    static int mouseButton(MotionEvent e) { return 0; }
                    Point cellAt(MotionEvent e) { return new Point(1,Math.max(0,Math.min(mRows-1,(int)e.y/10))); }
                """ + RuntimeSourceFixture.methods("ConsoleTerminalView", "onTouchEvent", "onGenericMotionEvent",
                        "scrollRows", "clampTopRow", "clamp", "scrollTerminal", "scrollTouch", "onKeyDown",
                        "handleFontScaleGesture", "fontSizeSp", "setFontSizeSp", "onScaleBegin", "onScale",
                        "computeVerticalScrollRange", "computeVerticalScrollExtent", "computeVerticalScrollOffset")
                + "}\npublic static void verify() {\n" + body + "\n}");
    }
}
