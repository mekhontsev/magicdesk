package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class X11HostBindingTest {
    @Test public void borrowedResourcesAndWindowIntentRetainTheirLifetimes() throws Exception {
        RuntimeSourceFixture.verify("static " + RuntimeSourceFixture.nestedClass("X11HostBinding", "X11HostBinding") + """
            static final List<String> events = new ArrayList<>();
            static class Configuration { int densityDpi = 320; }
            static class Resources {
                final Configuration configuration = new Configuration();
                Configuration getConfiguration() { return configuration; }
            }
            static class Activity {
                final int task;
                boolean focused = true;
                final Resources resources = new Resources();
                Activity(int task) { this.task = task; }
                int getTaskId() { return task; }
                boolean hasWindowFocus() { return focused; }
                Resources getResources() { return resources; }
            }
            record X11WindowManagement(boolean managed, Request request, State actual) {
                record Request(int serial, boolean fullscreen) { }
                record State(boolean fullscreen) { }
            }
            static class X11Session {
                record Window(long id, X11WindowManagement management) { }
                static class Output {
                    boolean closed;
                    void close() { if (!closed) { closed = true; events.add("output"); } }
                }
            }
            static class X11Sessions {
                interface Listener {
                    void onChanged();
                    default void onFrame(X11Session.Output output, int width, int height, boolean available) { }
                }
                static class Session {
                    final List<Listener> listeners = new ArrayList<>();
                    final Set<Object> densities = new HashSet<>();
                    final Set<Integer> hosts = new HashSet<>();
                    final Map<Long, Object> owners = new HashMap<>();
                    List<X11Session.Window> windows = List.of();
                    X11Session.Output output;
                    X11WindowManagement.Request confirmed;
                    X11WindowManagement.State actual;
                    int opens, clientCloses, serverCloses, confirmations;
                    void listen(Listener value) { listeners.add(value); }
                    void unlisten(Listener value) { listeners.remove(value); events.add("unlisten"); }
                    void host(int task, boolean focused) { hosts.add(task); }
                    void releaseHost(int task) { hosts.remove(task); events.add("host"); }
                    void hostDensity(Object host, int dpi, boolean focused) { densities.add(host); }
                    void releaseDensity(Object host) { densities.remove(host); events.add("density"); }
                    X11Session.Output openOutput(long id) { opens++; return output = new X11Session.Output(); }
                    List<X11Session.Window> windows() { return windows; }
                    boolean claimFullscreen(long id, Object host) { return owners.computeIfAbsent(id, key -> host) == host; }
                    void releaseFullscreen(Object host) { owners.values().removeIf(value -> value == host); events.add("owner"); }
                    void confirmFullscreen(long id, Object host, X11WindowManagement.Request request, X11WindowManagement.State state) {
                        check(owners.get(id) == host, "only the owner may confirm");
                        confirmed = request; actual = state; confirmations++;
                    }
                    void closeWindow(long id) { clientCloses++; events.add("client"); }
                    void close() { serverCloses++; events.add("server"); }
                    void window(int serial, boolean requested, boolean actual) {
                        windows = List.of(new X11Session.Window(31, new X11WindowManagement(true,
                                new X11WindowManagement.Request(serial, requested), new X11WindowManagement.State(actual))));
                    }
                }
            }
            record X11SurfaceOutput(X11Session.Output output) { }
            static class HostedSurfaceView {
                X11SurfaceOutput output;
                boolean failBind;
                int width, height;
                void bind(X11SurfaceOutput output) { this.output = output; if (failBind) throw new IllegalStateException("bind failed"); }
                void release() { if (output != null) output.output().close(); output = null; }
                void requestFocus() { }
                void frame(int width, int height) { this.width = width; this.height = height; }
            }
            record X11ContentExchange(Activity activity, X11Sessions.Session session, X11Session.Output output) { }
            static class HostedContentExchange {
                HostedContentExchange(Activity activity, HostedSurfaceView surface, X11ContentExchange backend) { }
                void focus(boolean focused) { }
                void close() { events.add("exchange"); }
            }
            static class BuiltInWindowRegistry { record ImmersiveRequest(boolean requested) { } }
            static class HostedFullscreen {
                static final List<HostedFullscreen> created = new ArrayList<>();
                final java.util.function.Consumer<Boolean> completed;
                boolean requested, closed;
                int requests;
                HostedFullscreen(Activity activity, HostedSurfaceView surface, java.util.function.Consumer<Boolean> completed) {
                    this.completed = completed; created.add(this);
                }
                void request(boolean requested) { this.requested = requested; requests++; completed.accept(requested); }
                void changed() { }
                void reject() { requested = false; completed.accept(false); }
                BuiltInWindowRegistry.ImmersiveRequest snapshot() { return new BuiltInWindowRegistry.ImmersiveRequest(requested); }
                void close() { closed = true; events.add("fullscreen"); }
            }
            public static void verify() {
                var session = new X11Sessions.Session(); session.window(-1, true, false);
                var activity = new Activity(10); var surface = new HostedSurfaceView();
                int[] changes = {0};
                var host = new X11HostBinding(activity, surface, session, 31, true, () -> changes[0]++);
                check(session.listeners.size() == 1 && session.densities.contains(host) && session.hosts.contains(10), "host registration");
                host.refresh(31, true);
                var output = session.output;
                check(session.opens == 1 && session.confirmed.serial() == -1 && session.actual.fullscreen(), "request identity and acknowledgement");
                var fullscreen = HostedFullscreen.created.get(0);
                host.refresh(31, true);
                check(fullscreen.requests == 1 && session.opens == 1, "same snapshot does not reopen or repeat request");
                session.window(2, false, true); host.refresh(31, true);
                check(fullscreen.requests == 2 && session.confirmed.serial() == 2 && !session.actual.fullscreen(), "new request replaces prior serial");
                host.onFrame(new X11Session.Output(), 12, 34, true);
                check(surface.width == 0, "foreign frame ignored");
                host.onFrame(output, 12, 34, true);
                check(surface.width == 12 && surface.height == 34, "own frame accepted");
                host.onFrame(output, 12, 34, false);
                check(surface.width == 0 && surface.height == 0, "unavailable frame clears geometry");

                var other = new X11HostBinding(new Activity(20), new HostedSurfaceView(), session, 31, false, () -> {});
                other.refresh(31, true);
                check(other.immersiveRequest() == null && HostedFullscreen.created.size() == 1, "only one fullscreen responder per window");
                events.clear(); host.close(false);
                check(events.equals(List.of("fullscreen", "owner", "exchange", "unlisten", "density", "host", "output")), "recreation release order: " + events);
                check(output.closed && fullscreen.closed && session.clientCloses == 0 && session.serverCloses == 0, "recreation retains client and server");
                check(!session.densities.contains(host) && session.densities.contains(other), "other host retains density registration");
                int eventCount = events.size(); host.close(true); host.onChanged(); host.onFrame(output, 20, 40, true);
                host.refresh(31, true); host.focusChanged(true); host.updateDensity(); host.presentationChanged(); host.rejectImmersive();
                check(events.size() == eventCount && changes[0] == 0 && surface.width == 0, "closed binding ignores late callbacks");
                other.refresh(31, true);
                check(other.immersiveRequest() != null && HostedFullscreen.created.size() == 2, "remaining host acquires released fullscreen lease");
                events.clear(); other.close(true);
                check(events.equals(List.of("fullscreen", "owner", "exchange", "client", "unlisten", "density", "host", "output")), "client close order: " + events);
                check(session.clientCloses == 1 && session.listeners.isEmpty() && session.densities.isEmpty() && session.hosts.isEmpty(), "individual host releases all leases");

                var desktopSession = new X11Sessions.Session();
                var desktop = new X11HostBinding(activity, new HostedSurfaceView(), desktopSession, 0, false, () -> {});
                desktop.refresh(0, true); desktop.close(true);
                check(desktopSession.serverCloses == 0 && desktopSession.clientCloses == 0, "whole desktop viewer retains server");
                var pending = new X11HostBinding(activity, new HostedSurfaceView(), desktopSession, 0, true, () -> {});
                pending.close(true);
                check(desktopSession.serverCloses == 1, "cancelled application startup stops owned session");

                var failedSurface = new HostedSurfaceView(); failedSurface.failBind = true;
                var failing = new X11HostBinding(activity, failedSurface, desktopSession, 0, false, () -> {});
                try { failing.refresh(0, true); throw new AssertionError("expected failed bind"); }
                catch (IllegalStateException expected) { }
                check(desktopSession.output.closed && failedSurface.output == null, "failed bind releases borrowed output");
                failedSurface.failBind = false; failing.refresh(0, true);
                check(!desktopSession.output.closed, "normal refresh can bind after local failure");
                failing.refresh(0, false);
                check(desktopSession.output.closed, "not-ready session releases output");
                failing.close(false);
                check(desktopSession.listeners.isEmpty(), "failed and recovered binding unsubscribes");
            }
            """);
    }
}
