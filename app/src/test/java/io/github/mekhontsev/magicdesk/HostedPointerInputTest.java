package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;

public final class HostedPointerInputTest {
    private static void verify(String body) throws Exception {
        String source = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "HostedPointerInput.java"));
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk",
                "static " + source.substring(source.indexOf("final class HostedPointerInput")) + STUBS
                        + "public static void verify() { " + body + " }", "HostedViewport");
    }

    @Test public void twoFingersScrollWithoutSelectionAndDoNotClickWhenLifted() throws Exception {
        verify("""
            var f = new Input();
            f.send(0, 1, 100, 100);
            check(f.out.edges.isEmpty(), "first contact must not start selection");
            f.send(5, 2, 100, 100);
            f.send(2, 2, 130, 200);
            check(f.out.scrolls.equals(List.of("-1,2")), "centroid produces natural scroll in wheel units");
            check(f.out.edges.isEmpty(), "two-finger scroll is not a drag");
            check(f.out.x == .1f && f.out.y == .1f, "scroll leaves pointer at anchor");
            f.send(6, 2, 300, 500);
            f.send(2, 1, 800, 900);
            f.send(1, 1, 800, 900);
            check(f.out.edges.isEmpty() && f.view.clicks == 0, "remaining finger cannot click or drag");
            check(f.out.scrolls.size() == 1, "finger removal is not scroll movement");
            check(f.view.pending == null, "gesture cancels delayed press");
            """);
    }

    @Test public void fractionalMotionAccumulatesAndDirectionCanReverse() throws Exception {
        verify("""
            var f = new Input(); f.send(0, 1, 100, 100); f.send(5, 2, 100, 100);
            for (int i=1; i<=49; i++) f.send(2, 2, 100, 100+i);
            check(f.out.scrolls.isEmpty(), "not one wheel click per motion sample");
            f.send(2, 2, 100, 151);
            check(f.out.scrolls.equals(List.of("0,1")), "accumulated scroll");
            f.send(2, 2, 100, 99);
            check(f.out.scrolls.equals(List.of("0,1", "0,-1")), "reversed scroll");
            f.send(3, 2, 100, 99);
            f.send(0, 1, 100, 100); f.send(5, 2, 100, 100); f.send(2, 2, 100, 101);
            check(f.out.scrolls.size() == 2, "new gesture has no previous remainder");
            """);
    }

    @Test public void tapsDragsAndLongPressRetainBalancedButtons() throws Exception {
        verify("""
            var f = new Input(); f.send(0, 1, 100, 100); f.send(1, 1, 100, 100);
            check(f.out.edges.equals(List.of("PRIMARY:true", "PRIMARY:false")) && f.view.clicks==1, "tap");
            f.out.edges.clear(); f.send(0, 1, 100, 100); f.send(2, 1, 102, 100);
            check(f.out.edges.isEmpty(), "touch slop");
            f.send(2, 1, 200, 100);
            check(f.input.dragging() && f.out.pressX == .1f, "drag presses at original location");
            f.send(1, 1, 200, 100);
            check(f.out.edges.equals(List.of("PRIMARY:true", "PRIMARY:false")), "drag release");
            f.out.edges.clear(); f.send(0, 1, 100, 100); f.view.pending.run();
            check(f.input.dragging(), "stationary long press");
            f.send(3, 1, 100, 100);
            check(f.out.edges.equals(List.of("PRIMARY:true", "PRIMARY:false")), "cancel long press");
            """);
    }

    @Test public void physicalMouseKeepsDragWheelAndRightButtonSemantics() throws Exception {
        verify("""
            var f = new Input(); f.source=InputDevice.SOURCE_MOUSE; f.tool=MotionEvent.TOOL_TYPE_MOUSE;
            f.buttons=1; f.send(0, 1, 100, 100); f.send(11, 1, 100, 100); f.send(2, 1, 200, 300);
            check(f.out.x == .2f && f.out.y == .3f && f.input.dragging(), "button-press callback must not stop dragging");
            f.buttons=0; f.send(12, 1, 200, 300); f.send(1, 1, 200, 300);
            check(f.out.edges.equals(List.of("PRIMARY:true", "PRIMARY:false")), "no duplicate physical edges");
            f.buttons=2; f.send(11, 1, 200, 300); f.buttons=0; f.send(12, 1, 200, 300);
            check(f.out.edges.subList(2,4).equals(List.of("SECONDARY:true", "SECONDARY:false")), "right button");
            var wheel=f.event(8,1,200,300); wheel.h=1; wheel.v=-2; f.input.event(wheel);
            check(f.out.scrolls.equals(List.of("1,-2")), "ordinary wheel units unchanged");
            """);
    }

    @Test public void hoverExitBeforeMouseDownDoesNotCreateAnotherClick() throws Exception {
        verify("""
            var f = new Input(); f.source=InputDevice.SOURCE_MOUSE; f.tool=MotionEvent.TOOL_TYPE_MOUSE;
            f.send(9, 1, 100, 100); f.send(7, 1, 200, 100);
            f.buttons=1; f.send(10, 1, 200, 100);
            check(f.out.edges.isEmpty(), "hover exit already carries pressed state, but is not a press");
            f.send(0, 1, 200, 100); f.send(11, 1, 200, 100);
            f.buttons=0; f.send(12, 1, 200, 100); f.send(1, 1, 200, 100);
            f.send(9, 1, 200, 100);
            check(f.out.edges.equals(List.of("PRIMARY:true", "PRIMARY:false")), "one tap is one click");
            check(f.out.x == .2f && f.out.y == .1f, "hover retains pointer position");
            """);
    }

    @Test public void hoverDoesNotChangeButtonOrContactOwnership() throws Exception {
        verify("""
            var f = new Input(); f.source=InputDevice.SOURCE_MOUSE; f.tool=MotionEvent.TOOL_TYPE_MOUSE;
            for (int action : new int[]{9, 7, 10}) {
                f.buttons=2; f.send(action, 1, 200, 100);
            }
            check(f.out.edges.isEmpty(), "hover cannot press a secondary button either");
            f.buttons=1; f.send(0, 1, 200, 100); f.send(11, 1, 200, 100);
            f.buttons=0;
            for (int action : new int[]{9, 7, 10}) f.send(action, 1, 300, 100);
            check(f.input.dragging() && f.out.edges.equals(List.of("PRIMARY:true")), "hover cannot release a drag");
            f.send(12, 1, 300, 100); f.send(1, 1, 300, 100);
            check(f.out.edges.equals(List.of("PRIMARY:true", "PRIMARY:false")), "button event releases drag");
            """);
    }

    @Test public void stylusIsDirectAndContactDoesNotRequireMouseSource() throws Exception {
        verify("""
            var f = new Input(); f.tool=2; f.send(0,1,100,100);
            check(f.input.dragging(), "stylus presses immediately");
            f.send(2,1,200,200); f.send(1,1,200,200);
            check(f.out.edges.equals(List.of("PRIMARY:true", "PRIMARY:false")), "stylus stroke");
            """);
    }

    @Test public void rawTouchpadIsRelativeButMouseFingerCoordinatesAreAbsolute() throws Exception {
        verify("""
            var f = new Input(); f.source=InputDevice.SOURCE_TOUCHPAD;
            f.send(0,1,800,800); f.send(2,1,900,800); f.send(1,1,900,800);
            check(f.out.x == .6f && f.out.y == .5f, "raw pad does not warp to pad coordinates");
            check(f.out.edges.isEmpty(), "one-finger pad movement is not selection");
            f.send(0,1,100,100); f.send(1,1,100,100);
            check(f.out.edges.equals(List.of("PRIMARY:true", "PRIMARY:false")), "pad tap clicks at cursor");
            f.out.edges.clear(); f.source=InputDevice.SOURCE_MOUSE;
            f.send(0,1,200,300); f.send(5,2,200,300); f.send(2,2,200,400);
            check(f.out.x == .2f && f.out.y == .3f && f.out.edges.isEmpty(), "mouse-source finger scroll");
            check(f.out.scrolls.equals(List.of("0,2")), "source capabilities do not override tool/count");
            """);
    }

    @Test public void classifiedScrollUsesAllPixelDeltaSamplesNotPointerPosition() throws Exception {
        verify("""
            var f = new Input(); f.source=InputDevice.SOURCE_MOUSE; f.tool=MotionEvent.TOOL_TYPE_MOUSE;
            f.classification=MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE;
            f.send(0,1,100,100);
            var move=f.event(2,1,100,100); move.gy=20; move.historyY=new float[]{20,20}; f.input.event(move);
            f.send(1,1,100,100);
            check(f.out.edges.isEmpty(), "classified scroll does not click even with one mouse pointer");
            check(f.out.scrolls.equals(List.of("0,-1")), "history participates in accumulated pixel scroll");
            """);
    }

    @Test public void classificationMayArriveAfterAnUnclassifiedDown() throws Exception {
        verify("""
            var f = new Input(); f.source=InputDevice.SOURCE_MOUSE; f.tool=MotionEvent.TOOL_TYPE_MOUSE;
            f.send(0,1,100,100);
            f.classification=MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE;
            var move=f.event(2,1,100,100); move.gy=100; f.input.event(move);
            f.classification=0; f.send(1,1,100,100);
            check(f.out.edges.isEmpty() && f.out.scrolls.equals(List.of("0,-2")), "late classification");
            """);
    }

    @Test public void classifiedOffsetsMatchNaturalTwoFingerScrollOnBothAxes() throws Exception {
        verify("""
            var touch = new Input(); touch.send(0,1,100,100); touch.send(5,2,100,100);
            touch.send(2,2,150,200);
            var pad = new Input(); pad.source=InputDevice.SOURCE_MOUSE;
            pad.classification=MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE; pad.send(0,1,100,100);
            var move=pad.event(2,1,150,200); move.gx=-50; move.gy=-100; pad.input.event(move);
            check(touch.out.scrolls.equals(List.of("-2,2")), "right/down fingers move content right/down");
            check(pad.out.scrolls.equals(touch.out.scrolls), "AOSP offsets have opposite sign to fake-finger coordinates");
            """);
    }

    @Test public void lateMouseMotionCannotRepressAfterFocusLossOrRebinding() throws Exception {
        verify("""
            var f = new Input(); f.source=InputDevice.SOURCE_MOUSE; f.tool=MotionEvent.TOOL_TYPE_MOUSE;
            f.buttons=1; f.send(0,1,100,100); f.input.release(); f.send(2,1,200,200);
            check(f.out.edges.equals(List.of("PRIMARY:true", "PRIMARY:false")), "late mouse move after focus loss");
            f.send(0,1,100,100); var replacement = new Output(); f.input.bind(replacement); f.send(2,1,300,300);
            check(f.out.edges.size()==4 && replacement.edges.isEmpty(), "button released on old output only");
            """);
    }

    @Test public void focusLossAndResizeCancelPendingContactsButUnchangedFrameDoesNot() throws Exception {
        verify("""
            var f = new Input(); f.send(0,1,100,100); f.send(2,1,200,200);
            f.input.viewport(HostedViewport.fit(1000,1000,1000,1000));
            check(f.input.dragging(), "unchanged frame preserves contact");
            f.input.release(); f.send(2,1,300,300); f.send(1,1,300,300);
            check(f.out.edges.equals(List.of("PRIMARY:true", "PRIMARY:false")), "focus loss cannot resume gesture");
            f.out.edges.clear(); f.send(0,1,100,100);
            Runnable pending=f.view.pending;
            f.input.viewport(HostedViewport.fit(500,500,1000,1000)); pending.run(); f.send(1,1,100,100);
            check(f.out.edges.isEmpty(), "resize invalidates delayed tap");
            """);
    }

    @Test public void windowGestureRetainsButtonAcrossResizeButNotFocusLoss() throws Exception {
        verify("""
            var f = new Input(); f.send(0,1,100,100); f.send(2,1,200,200);
            f.input.windowGesture();
            f.input.viewport(HostedViewport.fit(500,500,1000,1000));
            check(f.input.dragging() && f.out.edges.equals(List.of("PRIMARY:true")), "host resizing retains guest press");
            f.input.release();
            check(f.out.edges.equals(List.of("PRIMARY:true", "PRIMARY:false")), "focus loss releases even a host gesture");
            f.send(0,1,100,100); f.send(2,1,200,200);
            f.input.viewport(HostedViewport.fit(600,600,1000,1000));
            check(!f.input.dragging(), "next ordinary contact does not inherit gesture ownership");
            """);
    }

    @Test public void contentDragTransfersButtonOwnershipWithoutAnExtraRelease() throws Exception {
        verify("""
            var f = new Input(); f.send(0,1,100,100); f.send(2,1,200,200);
            f.input.handoff(); f.input.release(); f.send(1,1,200,200);
            check(f.out.edges.equals(List.of("PRIMARY:true")), "drop protocol alone owns release");
            check(!f.input.dragging() && f.view.pending == null, "local ownership relinquished");
            """);
    }

    @Test public void physicalFingerDragDoesNotBecomeScrollAndSecondContactCancelsSyntheticDrag() throws Exception {
        verify("""
            var f = new Input(); f.buttons=1; f.source=InputDevice.SOURCE_MOUSE;
            f.send(0,1,100,100); f.send(5,2,100,100); f.send(2,2,200,200);
            check(f.input.dragging() && f.out.scrolls.isEmpty(), "held physical button wins over raw contacts");
            f.buttons=0; f.send(1,1,200,200);
            f.out.edges.clear(); f.send(0,1,100,100); f.send(2,1,200,200); f.send(5,2,200,200);
            check(f.out.edges.equals(List.of("PRIMARY:true", "PRIMARY:false")), "second finger releases committed synthetic drag");
            f.send(2,2,200,300); f.send(1,1,200,300);
            check(f.out.edges.size()==2, "no trailing click");
            """);
    }

    @Test public void diagnosticsAreBoundedAndDeduplicateRepeatedMotion() throws Exception {
        verify("""
            var f = new Input(); f.send(0,1,100,100);
            for (int i=0;i<1000;i++) f.send(2,1,100+i,100);
            check(DesktopAutomationEventJournal.count == 2, "steady movement is not logged repeatedly");
            for (int i=0;i<100;i++) { f.source=i; f.send(2,1,100,100); }
            check(DesktopAutomationEventJournal.count == 32, "bounded per-host sampling");
            """);
    }

    @Test public void diagnosticsDistinguishDevicesWithIdenticalEvents() throws Exception {
        verify("""
            var f = new Input(); f.source=InputDevice.SOURCE_MOUSE; f.tool=MotionEvent.TOOL_TYPE_MOUSE;
            f.device=11; f.send(8,1,100,100);
            f.device=12; f.send(8,1,100,100);
            check(DesktopAutomationEventJournal.count == 2, "physical wheel must not be hidden by virtual wheel");
            check(DesktopAutomationEventJournal.details.get(0).contains(" device=11 ")
                && DesktopAutomationEventJournal.details.get(1).contains(" device=12 "), "preserve device identity");
            for (int i=0;i<1000;i++) { f.device=11+i%2; f.send(8,1,100+i,100); }
            check(DesktopAutomationEventJournal.count == 2, "interleaved devices do not log repeated events");
            f.device=-1; f.send(8,1,100,100); f.send(8,1,100,100);
            check(DesktopAutomationEventJournal.count == 3, "negative virtual device id remains distinct");
            """);
    }

    @Test public void diagnosticsLimitIsSharedAcrossDevicesAndSurvivesRebinding() throws Exception {
        verify("""
            var f = new Input(); f.source=InputDevice.SOURCE_MOUSE; f.tool=MotionEvent.TOOL_TYPE_MOUSE;
            for (int i=0;i<100;i++) { f.device=i; f.send(8,1,100,100); }
            check(DesktopAutomationEventJournal.count == 32, "one host budget, not a budget for every device");
            f.input.release(); f.input.bind(new Output()); f.send(7,1,200,200);
            check(DesktopAutomationEventJournal.count == 32, "focus loss and rebinding cannot replenish budget");
            var other = new Input(); other.send(8,1,100,100);
            check(DesktopAutomationEventJournal.count == 33, "a different host has its own budget");
            """);
    }

    private static final String STUBS = """
        interface HostedSurfaceOutput {
            enum Button { PRIMARY, MIDDLE, SECONDARY }
            void pointer(float x,float y); void button(float x,float y,Button button,boolean down);
            void scroll(float x,float y,float h,float v);
        }
        static class Output implements HostedSurfaceOutput {
            final List<String> edges=new ArrayList<>(), scrolls=new ArrayList<>();
            float x,y,pressX;
            public void pointer(float x,float y) { this.x=x; this.y=y; }
            public void button(float x,float y,Button b,boolean down) { edges.add(b+":"+down); if(down)pressX=x; }
            public void scroll(float x,float y,float h,float v) { scrolls.add((int)h+","+(int)v); }
        }
        static class View {
            Runnable pending; int clicks;
            Object getContext(){return this;}
            void postDelayed(Runnable action,long delay){pending=action;}
            void removeCallbacks(Runnable action){if(pending==action)pending=null;}
            void performClick(){clicks++;}
        }
        static class ViewConfiguration {
            static ViewConfiguration get(Object context){return new ViewConfiguration();}
            int getScaledTouchSlop(){return 8;}
            float getScaledHorizontalScrollFactor(){return 25;}
            float getScaledVerticalScrollFactor(){return 50;}
            static int getLongPressTimeout(){return 500;}
        }
        static class InputDevice { static final int SOURCE_TOUCHPAD=0x100008, SOURCE_MOUSE=0x2002; }
        static class DesktopAutomationEventJournal {
            static int count;
            static final List<String> details=new ArrayList<>();
            static void record(String type,String op,boolean ok,String detail){count++;details.add(detail);}
        }
        static class MotionEvent {
            static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,
                ACTION_POINTER_DOWN=5,ACTION_POINTER_UP=6,ACTION_HOVER_MOVE=7,ACTION_SCROLL=8,
                ACTION_HOVER_ENTER=9,ACTION_HOVER_EXIT=10,ACTION_BUTTON_PRESS=11,ACTION_BUTTON_RELEASE=12;
            static final int TOOL_TYPE_FINGER=1,TOOL_TYPE_MOUSE=3,CLASSIFICATION_TWO_FINGER_SWIPE=3;
            static final int BUTTON_PRIMARY=1,BUTTON_SECONDARY=2,BUTTON_TERTIARY=4;
            static final int AXIS_HSCROLL=10,AXIS_VSCROLL=9,
                AXIS_GESTURE_SCROLL_X_DISTANCE=50,AXIS_GESTURE_SCROLL_Y_DISTANCE=51;
            int action,count,source,tool,buttons,classification,device; float x,y,h,v,gx,gy;
            float[] historyY=new float[0];
            int getActionMasked(){return action;} int getPointerCount(){return count;}
            int getActionIndex(){return count-1;} int getSource(){return source;}
            int getToolType(int i){return tool;} int getClassification(){return classification;}
            int getButtonState(){return buttons;} int getDeviceId(){return device;}
            boolean isFromSource(int mask){return (source & mask)==mask;}
            float getX(){return x;} float getY(){return y;}
            float getX(int i){return x;} float getY(int i){return y;}
            float getAxisValue(int axis){return switch(axis){case 9->v;case 10->h;case 50->gx;case 51->gy;default->0;};}
            int getHistorySize(){return historyY.length;}
            float getHistoricalAxisValue(int axis,int i){return axis==51?historyY[i]:0;}
        }
        static class Input {
            final View view=new View(); final Output out=new Output();
            final HostedPointerInput input=new HostedPointerInput(view);
            int source=0x1002,tool=1,device=1,buttons,classification;
            Input(){input.bind(out); input.viewport(HostedViewport.fit(1000,1000,1000,1000));}
            MotionEvent event(int action,int count,float x,float y) {
                var e=new MotionEvent();e.action=action;e.count=count;e.x=x;e.y=y;e.tool=tool;
                e.source=source;e.buttons=buttons;e.classification=classification;e.device=device;return e;
            }
            void send(int action,int count,float x,float y){input.event(event(action,count,x,y));}
        }
        """;
}
