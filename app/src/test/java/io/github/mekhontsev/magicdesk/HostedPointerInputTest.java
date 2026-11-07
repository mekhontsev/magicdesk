package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;

public final class HostedPointerInputTest {
    @Test public void mouseNavigationKeysAreDuplicatesButKeyboardBackRemainsAndroidOwned() throws Exception {
        verify("""
            for (int source : new int[]{InputDevice.SOURCE_MOUSE, InputDevice.SOURCE_MOUSE_RELATIVE}) {
                for (int action : new int[]{0,1}) for (int code : new int[]{4,125})
                    check(HostedPointerInput.navigationKey(new KeyEvent(source,action,code)), "mouse key duplicate");
                check(!HostedPointerInput.navigationKey(new KeyEvent(source,2,4)), "multiple is not a button edge");
                check(!HostedPointerInput.navigationKey(new KeyEvent(source,0,29)), "typing is not a pointer button");
            }
            check(!HostedPointerInput.navigationKey(new KeyEvent(0x101,0,4)), "keyboard Back belongs to Android");
            check(!HostedPointerInput.navigationKey(new KeyEvent(0,1,4)), "system Back belongs to Android");
            """);
    }

    @Test public void sideButtonsStayHeldAcrossPrimaryClicksAndReleaseAtTheirOwnPosition() throws Exception {
        verify("""
            var f = new Input(); f.source=InputDevice.SOURCE_MOUSE; f.tool=MotionEvent.TOOL_TYPE_MOUSE;
            f.buttons=24; f.send(11,1,200,300); f.send(11,1,200,300);
            check(f.input.navigationHeld() && f.out.pressX==.2f, "side buttons use pointer coordinates");
            f.buttons=25; f.send(0,1,200,300); f.send(11,1,200,300);
            f.buttons=24; f.send(12,1,400,300); f.send(1,1,400,300);
            check(f.input.navigationHeld(), "primary UP cannot release held side buttons");
            f.buttons=16; f.send(12,1,400,300);
            f.buttons=0; f.send(12,1,400,300);
            check(f.out.edges.equals(List.of("BACK:true","FORWARD:true","PRIMARY:true","PRIMARY:false",
                "BACK:false","FORWARD:false")), "one press/release per physical button");
            check(!f.input.navigationHeld(), "final release clears lease");
            """);
    }

    @Test public void capturedNavigationButtonsAreBalancedAndReleasedOnLoss() throws Exception {
        verify("""
            var f=new Input(); f.source=InputDevice.SOURCE_MOUSE_RELATIVE; f.tool=MotionEvent.TOOL_TYPE_MOUSE;
            f.buttons=24; f.input.captured(f.event(11,1,0,0),7); f.input.captured(f.event(11,1,0,0),7);
            f.buttons=16; f.input.captured(f.event(12,1,0,0),7);
            f.input.release(); f.input.release();
            check(f.out.edges.equals(List.of("BACK:true","FORWARD:true","BACK:false","FORWARD:false")),
                "capture loss releases each button once");
            check(f.out.deltas.isEmpty(), "button edges do not synthesize motion");
            """);
    }

    @Test public void sideButtonsDoNotLeakAcrossOutputReplacementOrViewportLoss() throws Exception {
        verify("""
            var f=new Input(); f.source=InputDevice.SOURCE_MOUSE; f.tool=MotionEvent.TOOL_TYPE_MOUSE;
            f.buttons=24; f.send(11,1,100,100);
            var replacement=new Output(); f.input.bind(replacement);
            f.buttons=0; f.send(12,1,100,100);
            check(f.out.edges.equals(List.of("BACK:true","FORWARD:true","BACK:false","FORWARD:false")), "old lease released");
            check(replacement.edges.isEmpty(), "late UP cannot create an edge on new output");
            f.buttons=24; f.send(11,1,100,100); f.input.viewport(HostedViewport.EMPTY);
            check(replacement.edges.equals(f.out.edges), "viewport loss releases side buttons");
            """);
    }

    @Test public void capturedMouseKeepsEveryDeltaAndDoesNotClampOrWarp() throws Exception {
        verify("""
            var f=new Input(); f.source=InputDevice.SOURCE_MOUSE_RELATIVE; f.tool=MotionEvent.TOOL_TYPE_MOUSE;
            var e=f.event(2,1,2000,-100); e.historyY=new float[]{1,2};
            check(!f.input.captured(e,0),"unconfirmed capture cannot send movement");
            check(f.input.captured(e,7),"captured event accepted");
            check(f.out.deltas.equals(List.of("7:2,-0.1","7:2,-0.1","7:2,-0.1")),"history, scale and unclamped delta");
            check(f.out.x==0 && f.out.y==0,"relative motion must not change absolute cursor");
            f.buttons=1; f.input.captured(f.event(11,1,999,999),7);
            f.input.captured(f.event(0,1,999,999),7);
            f.buttons=0; f.input.captured(f.event(12,1,999,999),7);
            check(f.out.edges.equals(List.of("PRIMARY:true","PRIMARY:false")),"balanced captured button edges");
            check(f.out.deltas.size()==3,"buttons cannot replay motion deltas");
            """);
    }

    @Test public void capturedTouchpadUsesContactDifferencesAndRebasesEachTouch() throws Exception {
        verify("""
            var f=new Input(); f.source=InputDevice.SOURCE_TOUCHPAD;
            f.input.captured(f.event(0,1,800,800),9);
            f.input.captured(f.event(2,1,900,700),9);
            f.input.captured(f.event(1,1,900,700),9);
            f.input.captured(f.event(0,1,100,100),9);
            f.input.captured(f.event(2,1,200,100),9);
            f.input.captured(f.event(1,1,200,100),9);
            check(f.out.deltas.equals(List.of("9:0.1,-0.1","9:0.1,0")),"raw contacts must not be mistaken for relative coordinates");
            check(f.out.edges.isEmpty(),"movement is not a tap");
            """);
    }
    @Test public void directContactsKeepIdsAndCancelWithoutResurrectingLateUpdates() throws Exception {
        verify("""
            var f=new Input(); f.out.direct=true;
            f.send(0,1,100,200); f.send(5,2,300,400); f.send(2,2,500,600);
            check(f.out.contacts.equals(List.of("0:BEGIN","1:BEGIN","0:UPDATE","1:UPDATE")),"XI contact identity");
            check(f.out.x==.5f && f.out.y==.6f && f.out.pressure==.5f,"normalized coordinates and pressure");
            check(f.out.edges.isEmpty() && f.out.scrolls.isEmpty(),"direct touchscreen must not duplicate pointer gestures");
            f.send(6,2,600,700); f.input.release();
            check(f.out.cancels==1 && !f.input.dragging(),"release contacts exactly once");
            int count=f.out.contacts.size(); f.send(2,1,700,700); f.send(1,1,700,700);
            check(f.out.contacts.size()==count,"late stream cannot resurrect a cancelled contact");
            f.send(0,1,100,100); f.device=2; f.send(2,1,500,500);
            check(f.out.cancels==2 && !f.input.dragging(),"device replacement cancels the previous device");
            f.send(0,1,100,100); f.input.handoff();
            count=f.out.contacts.size(); f.send(1,1,100,100);
            check(f.out.cancels==2 && f.out.contacts.size()==count,"DND keeps native ownership of the terminal release");
            """);
    }

    @Test public void tabletHoverPressureAndBarrelsRemainSeparateFromMouseButtons() throws Exception {
        verify("""
            var f=new Input(); f.out.direct=true; f.tool=MotionEvent.TOOL_TYPE_STYLUS;
            f.send(9,1,300,400);
            check(f.out.penButtons==0 && f.out.pressure==0,"hover is not a tip press");
            f.buttons=MotionEvent.BUTTON_STYLUS_PRIMARY;
            f.send(0,1,300,400);
            check(f.out.penButtons==3 && f.out.pressure==.5f && f.input.dragging(),"tip and first barrel");
            f.send(1,1,300,400);
            check(f.out.penButtons==2 && f.out.pressure==0 && !f.input.dragging(),"tip up retains barrel");
            f.tool=MotionEvent.TOOL_TYPE_ERASER; f.send(9,1,300,400);
            check(f.out.eraser,"eraser identity survives transport");
            f.send(10,1,300,400);
            check(f.out.cancels==1 && f.out.edges.isEmpty(),"proximity loss releases the logical tool only");
            """);
    }
    private static void verify(String body) throws Exception {
        String source = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "HostedPointerInput.java"));
        String direct = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "HostedDirectInput.java"));
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk",
                "static " + source.substring(source.indexOf("final class HostedPointerInput"))
                        + "static " + direct.substring(direct.indexOf("final class HostedDirectInput")) + STUBS
                        + "public static void verify() { " + body + " }", "HostedViewport");
    }

    @Test public void twoFingersScrollWithoutSelectionAndDoNotClickWhenLifted() throws Exception {
        verify("""
            var f = new Input();
            f.send(0, 1, 100, 100);
            check(f.out.edges.isEmpty(), "first contact must not start selection");
            f.send(5, 2, 100, 100);
            f.send(2, 2, 130, 200);
            check(f.out.scrolls.equals(List.of("-1.2,2")), "centroid produces fractional natural scroll in wheel units");
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

    @Test public void fractionalMotionIsDeliveredWithoutHostQuantization() throws Exception {
        verify("""
            var f = new Input(); f.send(0, 1, 100, 100); f.send(5, 2, 100, 100);
            for (int i=1; i<=49; i++) f.send(2, 2, 100, 100+i);
            check(f.out.scrolls.equals(Collections.nCopies(49, "0,0.02")), "fractional units, not a click per sample");
            f.send(2, 2, 100, 151);
            check(f.out.scrolls.get(49).equals("0,0.04"), "remaining delta");
            f.send(2, 2, 100, 99);
            check(f.out.scrolls.get(50).equals("0,-1.04"), "reversed scroll");
            f.send(3, 2, 100, 99);
            f.send(0, 1, 100, 100); f.send(5, 2, 100, 100); f.send(2, 2, 100, 101);
            check(f.out.scrolls.get(51).equals("0,0.02"), "new gesture has no previous remainder");
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
            check(f.out.scrolls.equals(List.of("0,-0.4", "0,-0.4", "0,-0.4")), "all historical fractions are delivered");
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
        enum HostedTouchPhase { BEGIN, UPDATE, END }
        interface HostedSurfaceOutput {
            enum Button { PRIMARY, MIDDLE, SECONDARY, BACK, FORWARD }
            void pointer(float x,float y); void button(float x,float y,Button button,boolean down);
            void scroll(float x,float y,float h,float v);
            void relative(long serial,float dx,float dy);
            default boolean supportsTouch(){return false;} default boolean supportsTablet(){return false;}
            default void touch(int id,HostedTouchPhase phase,float x,float y,float pressure){}
            default void tablet(boolean eraser,boolean proximity,float x,float y,float pressure,float tx,float ty,int buttons){}
            default void cancelContacts(){}
        }
        static class Output implements HostedSurfaceOutput {
            final List<String> edges=new ArrayList<>(), scrolls=new ArrayList<>();
            final List<String> contacts=new ArrayList<>();
            final List<String> deltas=new ArrayList<>();
            public void relative(long serial,float dx,float dy){deltas.add(serial+":"+number(dx)+","+number(dy));}
            boolean direct,eraser; int cancels,penButtons; float pressure;
            public boolean supportsTouch(){return direct;} public boolean supportsTablet(){return direct;}
            public void touch(int id,HostedTouchPhase phase,float x,float y,float pressure){
                contacts.add(id+":"+phase);this.x=x;this.y=y;this.pressure=pressure;
            }
            public void tablet(boolean eraser,boolean proximity,float x,float y,float pressure,float tx,float ty,int buttons){
                this.eraser=eraser;this.pressure=pressure;penButtons=buttons;
            }
            public void cancelContacts(){cancels++;}
            float x,y,pressX;
            public void pointer(float x,float y) { this.x=x; this.y=y; }
            public void button(float x,float y,Button b,boolean down) { edges.add(b+":"+down); if(down)pressX=x; }
            static String number(float n){return n==(int)n?""+(int)n:Float.toString(n);}
            public void scroll(float x,float y,float h,float v) { scrolls.add(number(h)+","+number(v)); }
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
        static class InputDevice { static final int SOURCE_TOUCHPAD=0x100008, SOURCE_MOUSE=0x2002, SOURCE_TOUCHSCREEN=0x1002, SOURCE_MOUSE_RELATIVE=0x20004; }
        record KeyEvent(int source, int action, int keyCode) {
            static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_BACK=4,KEYCODE_FORWARD=125;
            int getAction(){return action;} int getKeyCode(){return keyCode;}
            boolean isFromSource(int mask){return (source & mask)==mask;}
        }
        static class DesktopAutomationEventJournal {
            static int count;
            static final List<String> details=new ArrayList<>();
            static void record(String type,String op,boolean ok,String detail){count++;details.add(detail);}
        }
        static class MotionEvent {
            static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,
                ACTION_POINTER_DOWN=5,ACTION_POINTER_UP=6,ACTION_HOVER_MOVE=7,ACTION_SCROLL=8,
                ACTION_HOVER_ENTER=9,ACTION_HOVER_EXIT=10,ACTION_BUTTON_PRESS=11,ACTION_BUTTON_RELEASE=12;
            static final int TOOL_TYPE_FINGER=1,TOOL_TYPE_STYLUS=2,TOOL_TYPE_MOUSE=3,TOOL_TYPE_ERASER=4,CLASSIFICATION_TWO_FINGER_SWIPE=3;
            static final int BUTTON_STYLUS_PRIMARY=32,BUTTON_STYLUS_SECONDARY=64,AXIS_TILT=25,AXIS_ORIENTATION=8;
            static final int BUTTON_PRIMARY=1,BUTTON_SECONDARY=2,BUTTON_TERTIARY=4,BUTTON_BACK=8,BUTTON_FORWARD=16;
            static final int AXIS_HSCROLL=10,AXIS_VSCROLL=9,
                AXIS_GESTURE_SCROLL_X_DISTANCE=50,AXIS_GESTURE_SCROLL_Y_DISTANCE=51;
            int action,count,source,tool,buttons,classification,device; float x,y,h,v,gx,gy;
            float[] historyY=new float[0];
            int getActionMasked(){return action;} int getPointerCount(){return count;}
            int getActionIndex(){return count-1;} int getSource(){return source;}
            int getToolType(int i){return tool;} int getClassification(){return classification;}
            int getButtonState(){return buttons;} int getDeviceId(){return device;}
            int getPointerId(int i){return i;}
            float getPressure(){return .5f;} float getPressure(int i){return .5f;}
            float getHistoricalPressure(int i,int h){return .4f;}
            float getHistoricalX(int i,int h){return x;} float getHistoricalY(int i,int h){return y;}
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
