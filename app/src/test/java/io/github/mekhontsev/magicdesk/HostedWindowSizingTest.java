package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class HostedWindowSizingTest {
    @Test public void graphicalHostsAllowClientSizedDialogsBelowTheAndroidDefault() throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var document = factory.newDocumentBuilder().parse(java.nio.file.Path.of("src/main/AndroidManifest.xml").toFile());
        String android = "http://schemas.android.com/apk/res/android";
        var activities = document.getElementsByTagName("activity");
        var hosts = new java.util.HashSet<>(java.util.Set.of(".X11Activity", ".WaylandActivity"));
        for (int i = 0; i < activities.getLength(); i++) {
            var activity = (org.w3c.dom.Element) activities.item(i);
            if (!hosts.remove(activity.getAttributeNS(android, "name"))) continue;
            var layouts = activity.getElementsByTagName("layout");
            org.junit.Assert.assertEquals(1, layouts.getLength());
            var layout = (org.w3c.dom.Element) layouts.item(0);
            org.junit.Assert.assertEquals("1dp", layout.getAttributeNS(android, "minWidth"));
            org.junit.Assert.assertEquals("1dp", layout.getAttributeNS(android, "minHeight"));
        }
        org.junit.Assert.assertTrue("Both protocol hosts must declare client-owned sizing", hosts.isEmpty());
    }

    @Test public void clientUpdatesFitTheCaptionWithoutOverridingManualGeometry() throws Exception {
        RuntimeSourceFixture.verify("static " + RuntimeSourceFixture.nestedClass("HostedWindowSizing", "HostedWindowSizing") + """
            static class Rect {
                final int left, top, right, bottom;
                Rect(int l,int t,int r,int b) { left=l;top=t;right=r;bottom=b; }
                int width() { return right-left; } int height() { return bottom-top; }
                int centerX() { return (left+right)/2; } int centerY() { return (top+bottom)/2; }
                boolean isEmpty() { return width()<=0 || height()<=0; }
                public boolean equals(Object other) { return other instanceof Rect r && left==r.left
                        && top==r.top && right==r.right && bottom==r.bottom; }
            }
            static class Insets extends Rect {
                static final Insets NONE = new Insets(0);
                Insets(int top) { super(0,top,0,0); }
                static Insets max(Insets a,Insets b) { return new Insets(Math.max(a.top,b.top)); }
            }
            static class WindowInsets {
                final Insets insets;
                WindowInsets(int top) { insets=new Insets(top); }
                Insets getInsets(int types) { return insets; }
                static class Type { static int captionBar() { return 1; } static int systemBars() { return 2; } }
            }
            record Constraints(int fixedWidth,int fixedHeight) {
                int width(int value) { return fixedWidth>0 ? fixedWidth : value; }
                int height(int value) { return fixedHeight>0 ? fixedHeight : value; }
            }
            record HostedWindowLayout(int width,int height,Constraints constraints) {
                HostedWindowLayout(int width,int height) { this(width,height,new Constraints(width,height)); }
            }
            static class Activity {
                boolean multi=true,finishing,destroyed;
                WindowInsets published=new WindowInsets(0),metrics=new WindowInsets(0);
                Rect bounds=new Rect(750,309,1170,707);
                boolean isInMultiWindowMode() { return multi; }
                boolean isFinishing() { return finishing; } boolean isDestroyed() { return destroyed; }
                Activity getWindow() { return this; } Activity getDecorView() { return this; }
                WindowInsets getRootWindowInsets() { return published; }
                Activity getWindowManager() { return this; } Activity getCurrentWindowMetrics() { return this; }
                WindowInsets getWindowInsets() { return metrics; } Rect getBounds() { return bounds; }
                int getTaskId() { return 12; }
            }
            static class ToolApplications {
                static class Target { boolean desktop=true; int displayId=7; }
                record WindowPlacement(Target target) { }
            }
            static class DesktopRuntimeBridge {
                static Rect work=new Rect(0,0,1920,1016);
                static Rect getDesktopWorkAreaBounds(int id) { check(id==7,"display"); return work; }
            }
            static class MagicDeskRuntime {
                static int calls;
                static Rect last;
                static java.util.function.Consumer<Result> pending;
                static class Result { boolean success=true; String message=""; }
                static void setWindowBounds(int display,int task,Rect bounds,java.util.function.Consumer<Result> done) {
                    check(display==7 && task==12,"wrong destination");
                    check(pending==null,"only one bounds command in flight");
                    calls++; last=bounds; pending=done;
                }
                static void complete() {
                    var done=pending; pending=null; done.accept(new Result());
                }
            }
            static class CompatibilityDiagnostics { static void record(String code,String message,String detail) { } }
            public static void verify() {
                var activity=new Activity();
                var target=new ToolApplications.Target();
                var placement=new ToolApplications.WindowPlacement(target);
                var sizing=new HostedWindowSizing();
                var layout=new HostedWindowLayout(420,398);
                Runnable changed=()->{};
                sizing.apply(activity,null,layout,1,changed);
                sizing.apply(activity,placement,layout,1,changed);
                check(MagicDeskRuntime.calls==0,"must await caption and ownership");
                activity.published=new WindowInsets(40);
                sizing.apply(activity,placement,layout,1,changed);
                check(MagicDeskRuntime.calls==1 && MagicDeskRuntime.last.equals(new Rect(750,289,1170,727)),
                        "actual caption must extend client area, preserving its center");
                sizing.apply(activity,placement,layout,1,changed);
                check(MagicDeskRuntime.calls==1,"pending command is not repeated");
                MagicDeskRuntime.complete();
                sizing.apply(activity,placement,layout,1,changed);
                check(MagicDeskRuntime.calls==1,"receipt is not a View geometry acknowledgement");
                activity.bounds=MagicDeskRuntime.last;
                activity.metrics=new WindowInsets(40);
                sizing.apply(activity,placement,layout,1,changed);
                check(MagicDeskRuntime.calls==1,"correct geometry is not resized or caption doubled");

                layout=new HostedWindowLayout(144,129);
                sizing.apply(activity,placement,layout,1,changed);
                check(MagicDeskRuntime.calls==2 && MagicDeskRuntime.last.width()==144
                        && MagicDeskRuntime.last.height()==169 && MagicDeskRuntime.last.centerX()==960
                        && Math.abs(MagicDeskRuntime.last.centerY()-508)<=1,
                        "late fixed-size hints refit the existing host without letterboxing");
                activity.bounds=MagicDeskRuntime.last; MagicDeskRuntime.complete();
                sizing.apply(activity,placement,layout,1,changed);
                activity.bounds=new Rect(300,300,444,469);
                sizing.apply(activity,placement,layout,1,changed);
                check(MagicDeskRuntime.calls==2,"manual movement is preserved");
                layout=new HostedWindowLayout(180,150);
                sizing.apply(activity,placement,layout,1,changed);
                check(MagicDeskRuntime.calls==3 && MagicDeskRuntime.last.centerX()==372,
                        "client update follows the moved host's center");
                activity.bounds=MagicDeskRuntime.last; MagicDeskRuntime.complete();
                sizing.apply(activity,placement,layout,1,changed);
                activity.bounds=new Rect(300,300,360,600);
                sizing.apply(activity,placement,layout,1,changed);
                sizing.apply(activity,placement,new HostedWindowLayout(200,160),1,changed);
                check(MagicDeskRuntime.calls==3,"manual resize relinquishes automatic fitting, even below client minimum");

                sizing=new HostedWindowSizing();
                target.desktop=false;
                sizing.apply(activity,placement,layout,1,changed);
                check(MagicDeskRuntime.calls==3,"independent host remains Android-owned");
                target.desktop=true; activity.multi=false;
                sizing.apply(activity,placement,layout,1,changed);
                check(MagicDeskRuntime.calls==3,"fullscreen destination cannot be resized");
                activity.multi=true;
                sizing.apply(activity,placement,new HostedWindowLayout(420,398),1.5f,changed);
                check(MagicDeskRuntime.calls==4 && MagicDeskRuntime.last.width()==630 && MagicDeskRuntime.last.height()==637,
                        "content scaling must precede Android decoration");
                activity.bounds=MagicDeskRuntime.last; MagicDeskRuntime.complete();
                DesktopRuntimeBridge.work=new Rect(100,100,400,400);
                new HostedWindowSizing().apply(activity,placement,new HostedWindowLayout(420,398),1,changed);
                check(MagicDeskRuntime.last.equals(DesktopRuntimeBridge.work),"limited work area clamps size and position");
                MagicDeskRuntime.complete();
            }
            """);
    }
}
