package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class HostedApplicationStartupTest {
    @Test public void catalogAdmissionAndRedirectHaveNoPlaceholderActivity() throws Exception {
        RuntimeSourceFixture.verify("static " + RuntimeSourceFixture.nestedClass("HostedLaunchWindows", "HostedLaunchWindows") + """
            enum GraphicalProtocol { X11, WAYLAND }
            record NativeWindow(long id, boolean mapped, String role) { }
            record Application(Session session, long window) { }
            enum RecentLaunchScope {
                INDEPENDENT;
                static RecentLaunchScope of(Object destination) { return INDEPENDENT; }
            }
            static class Session {
                boolean ready, stopped;
                long recordedWindow;
                Application redirect;
                String error="";
                GraphicalProtocol protocol=GraphicalProtocol.X11;
                List<NativeWindow> windows=List.of();
                Application redirect() { return redirect; }
                boolean ready() { return ready; }
                boolean stopped() { return stopped; }
                String error() { return error; }
                GraphicalProtocol protocol() { return protocol; }
                List<NativeWindow> windows() { return windows; }
                void recordUse(long window, RecentLaunchScope scope) { recordedWindow=window; }
            }
            static class Graphics { boolean desktop; boolean desktop() { return desktop; } }
            static class Exec { Graphics graphics=new Graphics(); }
            static class Request { Exec exec=new Exec(); }
            static class Host { boolean unavailable; boolean isUnavailable() { return unavailable; } }
            record DesktopActivityLaunchResult(String error) {
                boolean succeeded() { return error.isEmpty(); }
                static DesktopActivityLaunchResult failed(String error) { return new DesktopActivityLaunchResult(error); }
                static DesktopActivityLaunchResult failed(Exception error) { return failed(error.getMessage()); }
            }
            static class GraphicalApplicationLaunch {
                static Application reopened;
                static String error="";
                static void reopen(Host host, Request request, Application application,
                        java.util.function.Consumer<DesktopActivityLaunchResult> done) {
                    reopened=application; done.accept(new DesktopActivityLaunchResult(error));
                }
            }
            static class Startup {
                boolean ended, primarySubmitted;
                Session session=new Session();
                Host host=new Host(); Request request=new Request();
                Object destination=new Object();
                HostedLaunchWindows windows=new HostedLaunchWindows();
                List<HostedLaunchWindows.Offer> launches=new ArrayList<>();
                DesktopActivityLaunchResult result;
                void complete(DesktopActivityLaunchResult result) { this.result=result; ended=true; }
                void launch(long id, boolean primary, boolean replacing) {
                    if(primary) primarySubmitted=true;
                    launches.add(new HostedLaunchWindows.Offer(id,primary,replacing));
                }
            """ + RuntimeSourceFixture.methods("HostedApplicationStartup", "changed") + """
            }
            public static void verify() {
                var startup=new Startup();
                startup.changed(); check(startup.launches.isEmpty(), "server startup opened a placeholder Activity");
                startup.session.ready=true; startup.changed();
                check(startup.launches.isEmpty(), "server readiness is not application readiness");
                startup.session.windows=List.of(new NativeWindow(1,true,"splash")); startup.changed();
                check(startup.launches.size()==1 && !startup.launches.get(0).primary(), "splash consumed main presentation");
                startup.session.windows=List.of(); startup.changed();
                check(!startup.ended && startup.launches.size()==1, "gap between splash and main ended startup");
                startup.session.windows=List.of(new NativeWindow(2,true,"application")); startup.changed(); startup.changed();
                check(startup.launches.size()==2 && startup.launches.get(1).id()==2
                        && startup.launches.get(1).primary(), "main launch was duplicated or lost");

                var redirected=new Startup(); redirected.session.stopped=true;
                redirected.session.redirect=new Application(new Session(), 55);
                redirected.changed();
                check(GraphicalApplicationLaunch.reopened.window()==55 && redirected.result.error().isEmpty(),
                        "closed single-instance source lost the exact redirected document");
                check(redirected.session.redirect.session().recordedWindow==55 && redirected.session.recordedWindow==0,
                        "redirect must record the target document, not the discarded startup session");
                var rejected=new Startup(); rejected.session.redirect=new Application(new Session(), 56);
                GraphicalApplicationLaunch.error="placement failed"; rejected.changed();
                check(rejected.ended && rejected.session.redirect.session().recordedWindow==0,
                        "failed redirect was recorded as a successful launch");
                GraphicalApplicationLaunch.error="";

                var failed=new Startup(); failed.session.stopped=true; failed.session.error="client crashed"; failed.changed();
                check(failed.ended && failed.result.error().equals("client crashed"), "startup failure was hidden");
                var unavailable=new Startup(); unavailable.session.ready=true; unavailable.host.unavailable=true;
                unavailable.changed(); check(unavailable.ended && unavailable.launches.isEmpty(), "closed host accepted a window");

                var desktop=new Startup(); desktop.request.exec.graphics.desktop=true; desktop.session.ready=true;
                desktop.changed(); check(desktop.launches.get(0).id()==0, "whole X11 desktop needs its root output");
                var wayland=new Startup(); wayland.session.protocol=GraphicalProtocol.WAYLAND; wayland.session.ready=true;
                wayland.changed(); check(wayland.launches.isEmpty(), "Wayland opened before its first mapped toplevel");
                wayland.session.windows=List.of(new NativeWindow(9,true,"application")); wayland.changed();
                check(wayland.launches.get(0).id()==9 && wayland.launches.get(0).primary(), "Wayland bypassed common admission");
            }
            """);
    }
}
