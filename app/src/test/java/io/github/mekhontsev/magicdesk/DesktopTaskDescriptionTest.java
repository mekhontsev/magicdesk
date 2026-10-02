package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.junit.Test;

public final class DesktopTaskDescriptionTest {
    @Test public void liveMetadataFollowsScopeWithoutRedundantPublicationOrLosingIdentity() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Bitmap { }
                static class View {
                    interface OnAttachStateChangeListener {
                        void onViewAttachedToWindow(View view);
                        void onViewDetachedFromWindow(View view);
                    }
                    interface Layout {
                        void changed(View view,int l,int t,int r,int b,int ol,int ot,int or,int ob);
                    }
                    List<OnAttachStateChangeListener> attach=new ArrayList<>();
                    List<Layout> layout=new ArrayList<>();
                    void addOnAttachStateChangeListener(OnAttachStateChangeListener listener) { attach.add(listener); }
                    void addOnLayoutChangeListener(Layout listener) { layout.add(listener); }
                    void moved() { for(var listener:layout) listener.changed(this,0,0,1,1,0,0,1,1); }
                }
                static class Window { View decor=new View(); View getDecorView() { return decor; } }
                static class Activity {
                    boolean destroyed, finishing;
                    Window window=new Window();
                    ShellAppearance theme=new ShellAppearance(0xff111827,0xff090d14);
                    List<Presentation> publications=new ArrayList<>();
                    boolean isDestroyed() { return destroyed; }
                    boolean isFinishing() { return finishing; }
                    Window getWindow() { return window; }
                    String getString(int resource) { return "title:"+resource; }
                }
                enum UiColor { PANEL, BACKGROUND }
                record ShellAppearance(int primary,int background) {
                    record Palette(int primary,int background) {
                        int color(UiColor role) { return role==UiColor.PANEL?primary:background; }
                    }
                    Palette palette() { return new Palette(primary,background); }
                }
                static class AppearanceStore {
                    record Resolved(ShellAppearance theme) {}
                    static Resolved resolved(Activity activity) { return new Resolved(activity.theme); }
                }
                static class FrameworkTaskDescriptionApi {
                    static void publish(Activity activity,String label,int resource,Bitmap bitmap,int primary,int background) {
                        activity.publications.add(new Presentation(new Identity(label,resource,bitmap),primary,background));
                    }
                }
                static final WeakHashMap<Activity,State> TASKS=new WeakHashMap<>();
                """ + RuntimeSourceFixture.nestedClass("DesktopTaskDescription", "Identity")
                + RuntimeSourceFixture.nestedClass("DesktopTaskDescription", "Presentation")
                + RuntimeSourceFixture.nestedClass("DesktopTaskDescription", "State")
                + RuntimeSourceFixture.methods("DesktopTaskDescription", "apply", "bind", "refresh") + """
                public static void verify() {
                    var first=new Activity();
                    var second=new Activity();
                    apply(first,1,7);
                    apply(second,"Console",9);
                    check(first.publications.size()==1,"initial publication");
                    check(first.publications.get(0).identity().label().equals("title:1"),"localized label");
                    refresh(); first.window.decor.moved();
                    apply(first,1,7);
                    check(first.publications.size()==1 && second.publications.size()==1,"unchanged state published");
                    var icon=new Bitmap();
                    apply(first,"GIMP",icon);
                    check(first.publications.size()==2,"new identity missing");
                    check(first.window.decor.layout.size()==1 && first.window.decor.attach.size()==1,"duplicate observers");
                    first.theme=new ShellAppearance(0xfffafbfc,0xffeef1f4);
                    refresh();
                    var light=first.publications.get(2);
                    check(light.background()==0xffeef1f4 && light.primary()==0xfffafbfc,"theme colors lost");
                    check(light.identity().bitmap()==icon && light.identity().label().equals("GIMP"),"theme lost client identity");
                    check(second.publications.size()==1,"another workspace changed");
                    first.theme=second.theme;
                    first.window.decor.moved();
                    check(first.publications.size()==4 && first.publications.get(3).background()==0xff090d14,"move/cancel did not resolve current scope");
                    first.window.decor.attach.get(0).onViewAttachedToWindow(first.window.decor);
                    check(first.publications.size()==4,"reattachment duplicated publication");
                    first.finishing=true; second.destroyed=true;
                    refresh();
                    check(TASKS.isEmpty(),"closed Activities retained");
                    first.window.decor.moved();
                    check(first.publications.size()==4,"closed task updated");
                }
                """);
    }

    @Test public void publicApiPublishesResourceAndBitmapMetadataAtSupportedSdkBoundaries() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Bitmap { }
                record Icon(Bitmap bitmap) { static Icon createWithBitmap(Bitmap value) { return new Icon(value); } }
                static class Build { static class VERSION { static int SDK_INT; } }
                static class TaskDescription {
                    String label; Object icon; int primary,background;
                    TaskDescription() { }
                    TaskDescription(String label,Bitmap icon,int primary) { this.label=label; this.icon=icon; this.primary=primary; }
                    static class Builder {
                        TaskDescription value=new TaskDescription();
                        Builder setLabel(String label) { value.label=label; return this; }
                        Builder setIcon(int icon) { value.icon=icon; return this; }
                        Builder setIcon(Icon icon) {
                            check(Build.VERSION.SDK_INT>=37,"new icon API on old Android");
                            value.icon=icon.bitmap(); return this;
                        }
                        Builder setPrimaryColor(int color) { value.primary=color; return this; }
                        Builder setBackgroundColor(int color) { value.background=color; return this; }
                        TaskDescription build() { return value; }
                    }
                }
                static class Activity {
                    TaskDescription value=new TaskDescription(); int calls;
                    void setTaskDescription(TaskDescription next) {
                        calls++; value.label=next.label; value.icon=next.icon; value.primary=next.primary;
                        // Activity preserves an unset background in copyFromPreserveHiddenFields.
                        if(next.background!=0) value.background=next.background;
                    }
                }
                """ + RuntimeSourceFixture.methods("FrameworkTaskDescriptionApi", "publish") + """
                public static void verify() {
                    for(int sdk:new int[]{34,35,36,37}) {
                        Build.VERSION.SDK_INT=sdk;
                        Activity activity=new Activity();
                        publish(activity,"Files",7,null,0xff123456,0xffabcdef);
                        check(activity.calls==1 && activity.value.icon.equals(7),"resource icon publication");
                        check(activity.value.primary==0xff123456 && activity.value.background==0xffabcdef,"resource colors");
                        Bitmap icon=new Bitmap();
                        publish(activity,"GIMP",0,icon,0xff334455,0xffeeeeee);
                        check(activity.calls==(sdk>=37?2:3),"bitmap publication count");
                        check(activity.value.icon==icon && activity.value.label.equals("GIMP"),"bitmap/label lost");
                        check(activity.value.primary==0xff334455 && activity.value.background==0xffeeeeee,"bitmap colors");
                    }
                }
                """);
    }

    @Test public void sharedStylingUsesOneMetadataOwnerForNativeAndHostedWindows() throws Exception {
        assertTrue(RuntimeSourceFixture.methods("UiAppearance", "refresh").contains("DesktopTaskDescription.refresh()"));
        for (String type : new String[] {"X11Activity", "WaylandActivity", "SettingsActivity", "FileManagerActivity",
                "CommandConsoleActivity", "DiagnosticsActivity", "DeviceSetupActivity", "ControlActivity",
                "AppLogViewerActivity", "CompatibilityOnboardingActivity", "MagicDeskTouchpadActivity"}) {
            assertTrue(type, RuntimeSourceFixture.methods(type, "onCreate").contains("DesktopTaskDescription.apply("));
        }
        for (String type : new String[] {"X11Activity", "WaylandActivity"}) {
            String source=java.nio.file.Files.readString(java.nio.file.Path.of(RuntimeSourceFixture.MAIN+type+".java"));
            assertFalse(type,source.contains("setTaskDescription("));
        }
    }
}
