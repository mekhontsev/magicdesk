package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import io.github.mekhontsev.magicdesk.wayland.WaylandDataExchange;
import io.github.mekhontsev.magicdesk.wayland.WaylandSession;
import io.github.mekhontsev.magicdesk.hosted.HostedDataSource;
import static io.github.mekhontsev.magicdesk.wayland.WaylandDataExchange.DragAction.*;
import java.io.IOException;
import java.util.List;

/** Wayland protocol adapter for the common Android content lifecycle. */
final class WaylandContentExchange implements HostedContentBackend, WaylandSessions.Listener {
    private final WaylandSessions.Session session;
    private final WaylandSession.Output output;
    private final WaylandDataExchange exchange;
    private final HostedContentTransfer transfer;
    private HostedContentBackend.Listener listener;
    private Incoming incoming;
    private long outgoingOffer;

    WaylandContentExchange(Context context, WaylandSessions.Session session, WaylandSession.Output output) {
        this.session = session; this.output = output;
        exchange = session.content();
        transfer = new HostedContentTransfer(context, new HostedContentTransfer.FilesAccess() {
            @Override public ParcelFileDescriptor open(String uri) throws IOException, RemoteException {
                return session.contentFiles().openContentFile(uri);
            }
            @Override public String importFile(ParcelFileDescriptor fd, String name) throws IOException, RemoteException {
                return session.contentFiles().importContentFile(fd, name);
            }
        }, session::stopped, HostedContentFormats.MIME, "Wayland");
    }
    @Override public String clipboardIdentity() { return "wayland:" + session.id() + ":"; }
    @Override public void listen(HostedContentBackend.Listener listener) {
        this.listener = listener; session.listen(this);
    }
    @Override public void changed() { }
    @Override public void focus(boolean focused) {
        if (focused) session.claimClipboard(this); else session.releaseClipboard(this);
    }
    @Override public void publishClipboard(AndroidContentPayload payload) {
        exchange.publish(WaylandDataExchange.CLIPBOARD, transfer.offer(payload));
    }
    @Override public void contentOffer(WaylandDataExchange.Offer offer) {
        if (listener == null) return;
        if (offer.channel() == WaylandDataExchange.CLIPBOARD) listener.clipboardOffered(() -> transfer.receive(offer));
        else if (offer.output() == output.id) {
            outgoingOffer = offer.id();
            listener.dragOffered(new Outgoing(offer));
        }
    }
    @Override public void dragEvent(long id, long offer, boolean finished, boolean accepted) {
        if (listener == null || id != output.id) return;
        if (finished && incoming != null && incoming.offer == offer) listener.dropFinished(incoming, accepted);
        else if (!finished && outgoingOffer == offer) {
            outgoingOffer = 0;
            listener.dragCancelled();
        }
    }
    private final class Outgoing implements DragOffer {
        final WaylandSessions.Session source = session;
        final WaylandDataExchange.Offer offer;
        Outgoing(WaylandDataExchange.Offer offer) { this.offer = offer; }
        @Override public AndroidContentPayload read() throws IOException { return transfer.receive(offer); }
        @Override public void begin() { exchange.drag(output.id, BEGIN, offer.id(), 0, 0, false); }
        @Override public void finish(boolean accepted) {
            exchange.drag(output.id, FINISH, offer.id(), 0, 0, accepted);
            if (outgoingOffer == offer.id()) outgoingOffer = 0;
        }
    }
    @Override public Drop createDrop(List<String> types, AndroidContentPayload local, Content content, DragOffer offer) {
        var targets = local == null ? HostedContentFormats.MIME.dragTypes(types) : HostedContentFormats.MIME.formats(local);
        if (targets.isEmpty()) return null;
        Outgoing same = offer instanceof Outgoing candidate && candidate.source == session ? candidate : null;
        return new Incoming(targets, content, same, local != null);
    }
    private final class Incoming implements Drop {
        final List<String> types;
        final Content content;
        final Outgoing local;
        final boolean preview;
        long offer;
        boolean entered, dropped;
        float x, y;
        Incoming(List<String> types, Content content, Outgoing local, boolean preview) {
            this.types = types; this.content = content; this.local = local; this.preview = preview;
        }
        @Override public void enter() { incoming = this; if (preview) begin(); }
        private void begin() {
            if (offer == 0) offer = local != null ? local.offer.id() : exchange.publish(WaylandDataExchange.DRAG, new HostedDataSource() {
                private HostedDataSource resolved;
                @Override public List<String> types() { return types; }
                @Override public synchronized ParcelFileDescriptor open(String type) throws IOException {
                    if (resolved == null) resolved = transfer.offer(content.read());
                    return resolved.open(type);
                }
            });
            exchange.drag(output.id, ENTER, offer, 0, 0, false); entered = true;
        }
        @Override public void move(float x, float y) {
            this.x = x; this.y = y;
            if (entered) exchange.drag(output.id, MOVE, offer, x, y, false);
        }
        @Override public void leave() {
            if (entered) exchange.drag(output.id, LEAVE, offer, 0, 0, false);
            entered = false;
            if (!dropped && offer != 0 && local == null) {
                exchange.drag(output.id, ABORT, offer, 0, 0, false);
                offer = 0;
            }
        }
        @Override public void drop() {
            dropped = true;
            // Android grants external data only at DROP; do not provoke early client reads.
            if (!entered) { begin(); exchange.drag(output.id, MOVE, offer, x, y, false); }
            exchange.drag(output.id, DROP, offer, 0, 0, false);
        }
        @Override public void close() {
            leave();
            if (offer != 0 && local == null) exchange.drag(output.id, ABORT, offer, 0, 0, false);
            if (incoming == this) incoming = null;
        }
    }
    @Override public void close() {
        focus(false);
        if (incoming != null) incoming.close();
        session.unlisten(this); listener = null;
    }
}
