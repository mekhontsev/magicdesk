package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public final class HostedContentBoundaryTest {
    private static String source(String name) throws Exception {
        return Files.readString(Path.of(RuntimeSourceFixture.MAIN + name + ".java"));
    }

    @Test public void repeatedDropCoordinatesDoNotRestartNegotiation() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk",
                RuntimeSourceFixture.methods("HostedContentExchange", "enter", "point") + """
            static class android {
                static class graphics {
                    static class PointF {
                        final float x, y;
                        PointF(float x, float y) { this.x=x; this.y=y; }
                    }
                }
            }
            record DragEvent(float x, float y) {
                float getX() { return x; }
                float getY() { return y; }
            }
            static class Target {
                int entries, moves;
                float x, y;
                void enter() { entries++; }
                void move(float x, float y) { moves++; this.x=x; this.y=y; }
            }
            static class Incoming {
                final Target target = new Target();
                boolean entered;
                float lastX = Float.NaN, lastY = Float.NaN;
            }
            static class Surface {
                android.graphics.PointF contentPoint(float x, float y) {
                    return new android.graphics.PointF(x / 100, y / 100);
                }
            }
            final Surface surface = new Surface();
            final Incoming incoming = new Incoming();
            public static void verify() {
                var fixture = new Fixture();
                var value = fixture.incoming;
                fixture.enter(value);
                fixture.point(new DragEvent(20, 30));
                fixture.point(new DragEvent(20, 30));
                check(value.target.moves == 1, "identical drop must reuse negotiated position");
                fixture.point(new DragEvent(25, 30));
                check(value.target.moves == 2 && value.target.x == .25f,
                        "changed release position must still be delivered");
                fixture.enter(value);
                fixture.point(new DragEvent(25, 30));
                check(value.target.moves == 3 && value.target.entries == 2,
                        "reentry must negotiate even at the same position");
            }
            """);
    }

    @Test public void androidHostsDoNotDependOnX11() throws Exception {
        for (String name : new String[]{"HostedViewport", "HostedSurfaceView", "HostedPointerInput", "HostedSurfaceOutput", "HostedContentExchange", "HostedContentBackend"}) {
            String source = source(name);
            assertFalse(name, source.contains("com.termux"));
            assertFalse(name, source.contains("X11"));
            assertFalse(name, source.contains("UTF8_STRING"));
            assertFalse(name, source.contains("windowId"));
        }
    }

    @Test public void sessionControlsNeverBorrowWindowResources() throws Exception {
        String manager = source("X11ManagerActivity");
        for (String forbidden : new String[]{"openOutput", "hostDensity", "claimClipboard", "closeWindow", "SurfaceView", "releaseHost"})
            assertFalse(forbidden, manager.contains(forbidden));
        String destroy = RuntimeSourceFixture.methods("X11ManagerActivity", "onDestroy");
        assertTrue(destroy.contains("session.unlisten(this)"));
        assertFalse(destroy.contains("session.close()"));
        assertFalse(source("X11Activity").contains("createSessionControls"));
        assertTrue(RuntimeSourceFixture.methods("ToolApplications", "intent").contains("X11ManagerActivity.createIntent(context)"));
        assertTrue(RuntimeSourceFixture.methods("BuiltInDesktopAppCatalog", "searchEntries").contains("entry != X11_WINDOW"));
    }

    @Test public void x11AdapterOwnsFocusFormatsAndDragTransactions() throws Exception {
        String backend = source("HostedContentBackend");
        backend = backend.substring(backend.indexOf("interface HostedContentBackend"));
        String adapter = source("X11ContentExchange");
        adapter = "static " + adapter.substring(adapter.indexOf("final class X11ContentExchange"));
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", backend + adapter + """
            static class Context { }
            record AndroidContentPayload(String text) { }
            record ParcelFileDescriptor(String text) { }
            static class X11Session {
                record Output(int id, long windowId) { }
            }
            static class X11DataExchange {
                static final int CLIPBOARD=1, DRAG=2, BEGIN=3, FINISH=4, CANCEL=5, ENTER=6, MOVE=7, LEAVE=8, DROP=9;
                interface Source { List<String> types(); ParcelFileDescriptor open(String type) throws IOException; }
                record Offer(int channel, int output, int id) { }
                int publishes, lastOperation, lastOffer, lastOutput;
                long lastWindow;
                boolean lastAccepted;
                float lastX, lastY;
                final List<Integer> operations = new ArrayList<>();
                Source source;
                int publish(int channel, Source source) { this.source=source; publishes++; return 100 + publishes; }
                void drag(int operation, int offer, int output, long window, float x, float y, boolean accepted) {
                    lastOperation=operation; lastOffer=offer; lastOutput=output; lastWindow=window; lastAccepted=accepted;
                    operations.add(operation);
                    if (operation == MOVE) { lastX=x; lastY=y; }
                }
            }
            static class X11Sessions {
                interface Listener {
                    void onChanged();
                    default void onDataOffer(X11DataExchange.Offer offer) { }
                    default void onDragEvent(int operation, int output, boolean accepted) { }
                }
                static class Session {
                    final String id;
                    final X11DataExchange exchange = new X11DataExchange();
                    final Set<Listener> listeners = new HashSet<>();
                    Listener owner;
                    Session(String id) { this.id=id; }
                    String id() { return id; }
                    X11DataExchange dataExchange() { return exchange; }
                    void listen(Listener listener) { listeners.add(listener); }
                    void unlisten(Listener listener) { listeners.remove(listener); }
                    void claimClipboard(Listener listener) { owner=listener; }
                    void releaseClipboard(Listener listener) { if (owner == listener) owner=null; }
                }
            }
            static class X11ContentTransfer {
                X11ContentTransfer(Context context, X11Sessions.Session session) { }
                AndroidContentPayload receive(X11DataExchange.Offer offer) { return new AndroidContentPayload("offer:" + offer.id()); }
                static List<String> formats(AndroidContentPayload payload) { return List.of("UTF8_STRING", "text/plain"); }
                X11DataExchange.Source offer(AndroidContentPayload payload) {
                    return new X11DataExchange.Source() {
                        public List<String> types() { return formats(payload); }
                        public ParcelFileDescriptor open(String type) { return new ParcelFileDescriptor(payload.text()); }
                    };
                }
            }
            static class Events implements HostedContentBackend.Listener {
                HostedContentBackend.Content clipboard;
                HostedContentBackend.DragOffer drag;
                HostedContentBackend.Drop finished;
                int cancellations;
                boolean accepted;
                public void clipboardOffered(HostedContentBackend.Content content) { clipboard=content; }
                public void dragOffered(HostedContentBackend.DragOffer offer) { drag=offer; }
                public void dropFinished(HostedContentBackend.Drop drop, boolean accepted) { finished=drop; this.accepted=accepted; }
                public void dragCancelled() { cancellations++; }
            }
            public static void verify() throws Exception {
                var session = new X11Sessions.Session("a");
                var first = new X11ContentExchange(new Context(), session, new X11Session.Output(1, 31));
                var second = new X11ContentExchange(new Context(), session, new X11Session.Output(2, 32));
                var otherSession = new X11Sessions.Session("b");
                var other = new X11ContentExchange(new Context(), otherSession, new X11Session.Output(3, 33));
                var events = new Events(); var secondEvents = new Events(); var otherEvents = new Events();
                first.listen(events); second.listen(secondEvents); other.listen(otherEvents);
                check(session.listeners.size() == 2, "subscribed outputs");
                check(first.clipboardIdentity().equals(second.clipboardIdentity()), "session clipboard feedback identity");
                check(!first.clipboardIdentity().equals(other.clipboardIdentity()), "separate sessions");
                first.focus(true); second.focus(true); first.focus(false);
                check(session.owner == second, "old host must not release new clipboard owner");
                first.onDataOffer(new X11DataExchange.Offer(X11DataExchange.CLIPBOARD, 0, 20));
                check(events.clipboard.read().text().equals("offer:20"), "clipboard conversion");
                first.onDataOffer(new X11DataExchange.Offer(X11DataExchange.DRAG, 2, 21));
                check(events.drag == null, "foreign output must not receive drag");
                first.onDataOffer(new X11DataExchange.Offer(X11DataExchange.DRAG, 1, 22));
                var drag = events.drag;
                drag.begin();
                check(session.exchange.lastOperation == X11DataExchange.BEGIN && session.exchange.lastWindow == 31, "drag origin");
                var payload = new AndroidContentPayload("data");
                var same = second.createDrop(List.of("text/plain"), payload, () -> payload, drag);
                same.enter();
                check(session.exchange.publishes == 0 && session.exchange.lastOffer == 22 && session.exchange.lastAccepted, "same server reuses native offer");
                same.move(.2f, .3f); same.leave(); same.enter(); same.drop();
                second.onDragEvent(X11DataExchange.FINISH, 1, true);
                check(secondEvents.finished == null, "foreign output finish");
                second.onDragEvent(X11DataExchange.FINISH, 2, true);
                check(secondEvents.finished == same && secondEvents.accepted, "drop identity and result");
                same.close(); secondEvents.finished = null;
                second.onDragEvent(X11DataExchange.FINISH, 2, true);
                check(secondEvents.finished == null, "closed transaction cannot finish again");
                final int[] reads = {0};
                var foreign = other.createDrop(List.of("text/plain"), payload, () -> { reads[0]++; return payload; }, drag);
                check(reads[0] == 0, "no eager content read before Android grants");
                foreign.enter();
                check(otherSession.exchange.publishes == 1 && !otherSession.exchange.lastAccepted, "cross server publishes selection");
                check(otherSession.exchange.source.types().contains("UTF8_STRING"), "protocol targets owned by adapter");
                check(otherSession.exchange.source.open("UTF8_STRING").text().equals("data"), "content read");
                otherSession.exchange.source.open("text/plain");
                check(reads[0] == 1, "materialize an incoming selection once");
                foreign.close();
                otherSession.exchange.operations.clear();
                final boolean[] granted = {false};
                var external = other.createDrop(List.of("text/plain"), null, () -> {
                    check(granted[0], "Android data cannot be read before DROP");
                    return payload;
                }, null);
                external.enter(); external.move(.1f, .2f); external.leave();
                external.enter(); external.move(.5f, .6f);
                check(otherSession.exchange.operations.isEmpty() && otherSession.exchange.publishes == 1,
                        "external hover must not provoke premature X11 selection reads");
                granted[0] = true;
                external.drop();
                check(otherSession.exchange.operations.equals(List.of(X11DataExchange.ENTER,
                        X11DataExchange.MOVE, X11DataExchange.DROP)), "deferred negotiation order");
                check(otherSession.exchange.lastX == .5f && otherSession.exchange.lastY == .6f,
                        "deferred drop uses the final position after reentry");
                check(otherSession.exchange.source.open("UTF8_STRING").text().equals("data"), "granted drop data");
                external.close();
                otherSession.exchange.operations.clear();
                var cancelled = other.createDrop(List.of("text/plain"), null, () -> {
                    throw new IOException("cancelled offer cannot be read");
                }, null);
                cancelled.enter(); cancelled.move(.2f, .3f); cancelled.leave(); cancelled.close();
                check(otherSession.exchange.operations.isEmpty(), "cancelled Android hover creates no X11 drag");
                check(other.createDrop(List.of(), null, () -> payload, null) == null, "unsupported offer rejected");
                drag.finish(true);
                check(session.exchange.lastOperation == X11DataExchange.FINISH && session.exchange.lastOutput == 1, "source acknowledgement");
                first.onDragEvent(X11DataExchange.CANCEL, 2, false);
                check(events.cancellations == 0, "foreign output cancellation");
                first.onDragEvent(X11DataExchange.CANCEL, 1, false);
                check(events.cancellations == 1, "source cancellation");
                first.close(); second.close(); other.close();
                check(session.listeners.isEmpty() && session.owner == null, "listener and clipboard cleanup");
                first.onDragEvent(X11DataExchange.CANCEL, 1, false);
                check(events.cancellations == 1, "closed adapter ignores late callbacks");
            }
            """, "X11ContentFormats");
    }
}
