package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class X11ActivityHandoffTest {
    @Test public void closedLaunchSessionHandsOffBeforeCheckingPresentationClosure() throws Exception {
        RuntimeSourceFixture.verify("""
            static class X11Session {
                record Window(long id, boolean provisional) {
                    boolean mapped() { return true; }
                    String title() { return "document"; }
                    Object icon() { return null; }
                }
            }
            static class X11Sessions {
                enum State { READY, CLOSED, FAILED }
                record Application(Session session, long window) { }
                static class Presentation {
                    boolean closed;
                    boolean isClosed() { return closed; }
                }
                static class Session {
                    Presentation presentation=new Presentation();
                    Application redirect;
                    State state=State.READY;
                    String name="test";
                    List<X11Session.Window> windows=List.of();
                    Application redirect() { return redirect; }
                    State state() { return state; }
                    List<X11Session.Window> windows() { return windows; }
                    void claimWindow(long id) { }
                    Object windowRecipe(long id) { return null; }
                    String error() { return ""; }
                }
            }
            static class X11WindowSelection {
            """ + RuntimeSourceFixture.methods("X11WindowSelection", "select") + """
            }
            static class X11ApplicationLaunch {
                static Object reference(Object context, Object recipe) { return recipe; }
            }
            static class DesktopRuntimeBridge { static void refreshTaskPresentations() { } }
            static class View { static final int GONE=8, VISIBLE=0; }
            static class R { static class string { static int x11_no_session, x11_waiting_application; } }
            static class ShellAccess { static String usefulMessage(Exception error) { return error.toString(); } }
            static class Status {
                void setText(Object value) { }
                void setVisibility(int value) { }
            }
            static class Binding {
                long window;
                void refresh(long window, boolean ready) { if (ready) this.window=window; }
            }
            static class Activity {
                X11Sessions.Session session;
                long window;
                boolean application=true, provisional=true, seenWindow, finishing, destroyed;
                Object identityRecipe, windowApplication;
                Status status=new Status(); Binding binding=new Binding();
                boolean isDestroyed() { return destroyed; }
                boolean isFinishing() { return finishing; }
                void finish() { finishing=true; }
                void select(X11Sessions.Session next) { session=next; onChanged(); }
                void present(String title,Object icon) { }
                String getString(int id) { return ""; }
            """ + RuntimeSourceFixture.methods("X11Activity", "onChanged") + """
            }
            public static void verify() {
                var target=new X11Sessions.Session();
                target.windows=List.of(new X11Session.Window(1,false),new X11Session.Window(2,false));
                var origin=new X11Sessions.Session();
                origin.state=X11Sessions.State.CLOSED; origin.presentation.closed=true;
                origin.redirect=new X11Sessions.Application(target,2);
                var activity=new Activity(); activity.session=origin; activity.onChanged();
                check(!activity.finishing && activity.session==target, "closed launch host did not follow redirect");
                check(activity.window==2 && !activity.provisional && activity.binding.window==2,
                        "Writer was replaced with the first Calc window");
                activity.onChanged();
                check(activity.window==2 && !activity.finishing, "subsequent snapshot lost selected window");
                target.presentation.closed=true; activity.onChanged();
                check(activity.finishing, "ordinary session stop did not finish its host");
                var finishing=new Activity(); finishing.session=origin; finishing.finishing=true; finishing.onChanged();
                check(finishing.session==origin, "late redirect resurrected a finishing host");
            }
            """);
    }
}
