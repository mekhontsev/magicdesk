package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class HostedWindowCommandsTest {
    @Test public void gesturesCoalesceAndRespectOwnershipConstraintsAndCancellation() throws Exception {
        RuntimeSourceFixture.verify("static " + RuntimeSourceFixture.nestedClass("HostedWindowCommands", "HostedWindowCommands")
                .replace("BiConsumer<", "java.util.function.BiConsumer<")
                + "static " + RuntimeSourceFixture.nestedClass("WindowMaximization", "WindowMaximization")
                + """
            enum HostedMaximization {
                NONE(false,false),HORIZONTAL(true,false),VERTICAL(false,true),BOTH(true,true);
                final boolean horizontal,vertical;
                HostedMaximization(boolean h,boolean v) { horizontal=h; vertical=v; }
                static HostedMaximization of(boolean h,boolean v) { return h ? (v ? BOTH:HORIZONTAL):(v ? VERTICAL:NONE); }
            }
            static class Rect {
                int left, top, right, bottom;
                Rect(int l, int t, int r, int b) { left=l; top=t; right=r; bottom=b; }
                Rect(Rect r) { this(r.left,r.top,r.right,r.bottom); }
                int width() { return right-left; } int height() { return bottom-top; }
                void offset(int x,int y) { left+=x; right+=x; top+=y; bottom+=y; }
                public boolean equals(Object o) { return o instanceof Rect r && left==r.left && top==r.top && right==r.right && bottom==r.bottom; }
            }
            record Display(int id) { int getDisplayId() { return id; } }
            static class Activity {
                boolean focused=true, multi=true;
                Rect bounds=new Rect(10,20,810,620);
                boolean hasWindowFocus() { return focused; }
                boolean isInMultiWindowMode() { return multi; }
                Display getDisplay() { return new Display(3); } int getTaskId() { return 42; }
                Activity getWindowManager() { return this; } Activity getCurrentWindowMetrics() { return this; }
                Rect getBounds() { return bounds; }
                void runOnUiThread(Runnable r) { r.run(); }
            }
            record MotionEvent(int action,float x,float y) {
                static final int ACTION_MOVE=2,ACTION_UP=1,ACTION_CANCEL=3,ACTION_BUTTON_RELEASE=12;
                int getActionMasked() { return action; } float getRawX() { return x; } float getRawY() { return y; }
            }
            static class Point { float x=100,y=100; }
            static class HostedSurfaceView {
                java.util.function.Predicate<MotionEvent> receiver;
                int cancels;
                void windowMotion(java.util.function.Predicate<MotionEvent> p) { receiver=p; }
                Point pressedPointer() { return new Point(); }
                int getWidth() { return 780; } int getHeight() { return 560; }
                void cancelPointer() { cancels++; }
                void beginWindowGesture() { }
            }
            record HostedWindowConstraints(int minW,int minH,int maxW,int maxH) {
                static final HostedWindowConstraints NONE=new HostedWindowConstraints(1,1,4000,4000);
                int width(int v) { return Math.max(minW,Math.min(maxW,v)); }
                int height(int v) { return Math.max(minH,Math.min(maxH,v)); }
            }
            enum HostedWindowGesture {
                MOVE(false,false,false,false),BOTTOM_RIGHT(false,false,true,true),CANCEL(false,false,false,false);
                final boolean left,top,right,bottom;
                HostedWindowGesture(boolean l,boolean t,boolean r,boolean b) { left=l;top=t;right=r;bottom=b; }
            }
            static class DesktopRuntimeBridge {
                static boolean managed=true;
                static boolean hasWorkspace(int display) { return managed; }
                static Rect getDesktopWorkAreaBounds(int display) { return new Rect(0,0,1280,736); }
            }
            record Result(boolean success) { }
            static class MagicDeskRuntime {
                static Rect target;
                static int calls;
                static java.util.function.Consumer<Result> pending;
                static void setWindowBounds(int display,int task,Rect bounds,java.util.function.Consumer<Result> cb) {
                    check(pending==null,"one in-flight command"); calls++; target=bounds; pending=cb;
                }
                static void setMaximized(int display,int task,HostedMaximization max,java.util.function.Consumer<Result> cb) { cb.accept(new Result(true)); }
                static void finish(boolean ok) { var p=pending; pending=null; p.accept(new Result(ok)); }
            }
            static class TaskRepository {
                static class Task { int taskId=42; Rect bounds=DesktopRuntimeBridge.getDesktopWorkAreaBounds(3); boolean isFreeform() { return true; } }
                static class Snapshot { boolean available=true; List<Task> tasks=List.of(new Task()); }
                static void load(int display,java.util.function.Consumer<Snapshot> cb) { cb.accept(new Snapshot()); }
            }
            public static void verify() {
                Activity activity=new Activity(); HostedSurfaceView surface=new HostedSurfaceView();
                var commands=new HostedWindowCommands(activity,surface,(serial,max)->{});
                commands.begin(HostedWindowGesture.MOVE);
                check(surface.cancels==0,"client press retained until the host gesture ends");
                surface.receiver.test(new MotionEvent(2,110,120));
                surface.receiver.test(new MotionEvent(2,120,130));
                surface.receiver.test(new MotionEvent(1,140,150));
                check(surface.cancels==1,"guest press released at gesture end");
                check(MagicDeskRuntime.calls==1,"motion is coalesced");
                MagicDeskRuntime.finish(true);
                check(MagicDeskRuntime.target.equals(new Rect(50,70,850,670)),"final pointer position retained");
                MagicDeskRuntime.finish(true);
                commands.update(0,HostedMaximization.NONE,new HostedWindowConstraints(200,100,500,300),1);
                commands.begin(HostedWindowGesture.BOTTOM_RIGHT);
                surface.receiver.test(new MotionEvent(2,2000,2000));
                check(MagicDeskRuntime.target.width()==520 && MagicDeskRuntime.target.height()==340,"client limits plus Android decor");
                surface.receiver.test(new MotionEvent(2,150,150));
                activity.focused=false; commands.focusChanged();
                int calls=MagicDeskRuntime.calls; MagicDeskRuntime.finish(true);
                check(MagicDeskRuntime.calls==calls,"focus loss cancels queued motion");
                activity.focused=true; DesktopRuntimeBridge.managed=false;
                commands.begin(HostedWindowGesture.MOVE);
                check(!surface.receiver.test(new MotionEvent(2,120,120)),"independent host cannot mutate task");
                commands.close(); check(surface.receiver==null,"host closure releases handler");
            }
            """);
    }
}
