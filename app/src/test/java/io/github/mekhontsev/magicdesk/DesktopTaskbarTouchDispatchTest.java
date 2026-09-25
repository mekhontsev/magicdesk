package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopTaskbarTouchDispatchTest {
    @Test
    public void buttonClickPrecedesRevealDismissalAndEdgeGesturesStayConsumed() throws Exception {
        RuntimeSourceFixture.verify("""
                static final ArrayDeque<Runnable> queue = new ArrayDeque<>();
                static final ArrayList<String> events = new ArrayList<>();
                static boolean attached = true, immediateClick;
                static final class MotionEvent {
                    static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2,
                            ACTION_CANCEL = 3, ACTION_OUTSIDE = 4;
                    final int action;
                    MotionEvent(int action) { this.action = action; }
                    int getActionMasked() { return action; }
                }
                static class FrameLayout {
                    boolean handled = true;
                    public boolean dispatchTouchEvent(MotionEvent event) {
                        events.add("control:" + event.action);
                        if (event.action == MotionEvent.ACTION_UP) {
                            Runnable click = () -> { if (attached) events.add("click"); };
                            if (immediateClick) click.run(); else queue.add(click);
                        }
                        return handled;
                    }
                }
                static final class DesktopTaskbarHost {
                    static void dispatchEdgeInput(int displayId, MotionEvent event) {
                        events.add("edge:" + event.action);
                        if (event.action == MotionEvent.ACTION_UP) {
                            queue.add(() -> { attached = false; events.add("dismiss"); });
                        }
                    }
                }
                static class Panel extends FrameLayout {
                    boolean mHiddenEdgeTouchSequence, mEdgeHidden;
                    int mDisplayId;
                """ + RuntimeSourceFixture.methods("DesktopChromeActivity", "dispatchTouchEvent") + """
                }
                static void drain() { while (!queue.isEmpty()) queue.remove().run(); }
                static void reset() { drain(); events.clear(); attached = true; }
                public static void verify() {
                    for (boolean immediate : new boolean[] {false, true}) {
                        reset(); immediateClick = immediate;
                        Panel panel = new Panel();
                        check(panel.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN)), "down lost");
                        check(panel.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_UP)), "up lost");
                        drain();
                        check(events.contains("click"), "detachment cancelled button click: " + events);
                        check(events.indexOf("click") < events.indexOf("dismiss"), "dismiss preceded click");
                    }
                    for (int end : new int[] {MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL}) {
                        reset();
                        Panel panel = new Panel(); panel.mEdgeHidden = true;
                        check(panel.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN)), "edge down escaped");
                        panel.mEdgeHidden = false;
                        check(panel.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_MOVE)), "edge move escaped");
                        check(panel.dispatchTouchEvent(new MotionEvent(end)), "edge end escaped");
                        drain();
                        check(events.stream().noneMatch(e -> e.startsWith("control:")),
                                "reveal gesture clicked exposed controls: " + events);
                        check(!panel.mHiddenEdgeTouchSequence, "edge tracking survived termination");
                        reset();
                        panel.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN));
                        panel.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_UP));
                        drain();
                        check(events.contains("click"), "next ordinary tap was consumed");
                    }
                    reset();
                    Panel panel = new Panel(); panel.handled = false;
                    check(!panel.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_OUTSIDE)),
                            "outside event swallowed");
                    check(events.contains("edge:" + MotionEvent.ACTION_OUTSIDE), "outside event lost");
                }
                """);
    }
}
