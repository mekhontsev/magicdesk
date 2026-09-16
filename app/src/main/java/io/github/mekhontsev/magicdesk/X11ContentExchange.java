package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import com.termux.x11.X11DataExchange;
import com.termux.x11.X11Session;
import java.io.IOException;
import java.util.List;

/** Translates host content transactions to X11 selections and per-output XDND messages. */
final class X11ContentExchange implements HostedContentBackend, X11Sessions.Listener {
    private final X11Sessions.Session session;
    private final X11Session.Output output;
    private final X11DataExchange exchange;
    private final X11ContentTransfer transfer;
    private HostedContentBackend.Listener listener;
    private Incoming incoming;

    X11ContentExchange(Context context, X11Sessions.Session session, X11Session.Output output) {
        this.session = session;
        this.output = output;
        exchange = session.dataExchange();
        transfer = new X11ContentTransfer(context, session);
    }

    @Override public String clipboardIdentity() { return "x11:" + session.id() + ":"; }
    @Override public void listen(HostedContentBackend.Listener listener) {
        this.listener = listener;
        session.listen(this);
    }
    @Override public void focus(boolean focused) {
        if (focused) session.claimClipboard(this);
        else session.releaseClipboard(this);
    }
    @Override public void publishClipboard(AndroidContentPayload payload) {
        exchange.publish(X11DataExchange.CLIPBOARD, transfer.offer(payload));
    }
    @Override public void onChanged() { }
    @Override public void onDataOffer(X11DataExchange.Offer offer) {
        if (listener == null) return;
        if (offer.channel() == X11DataExchange.CLIPBOARD) listener.clipboardOffered(() -> transfer.receive(offer));
        else if (offer.output() == output.id()) listener.dragOffered(new Outgoing(offer));
    }
    @Override public void onDragEvent(int operation, int outputId, boolean accepted) {
        if (listener == null || outputId != output.id()) return;
        if (operation == X11DataExchange.FINISH && incoming != null) listener.dropFinished(incoming, accepted);
        else if (operation == X11DataExchange.CANCEL) listener.dragCancelled();
    }

    private final class Outgoing implements DragOffer {
        final X11Sessions.Session source = session;
        final X11DataExchange.Offer offer;
        Outgoing(X11DataExchange.Offer offer) { this.offer = offer; }
        @Override public AndroidContentPayload read() throws IOException { return transfer.receive(offer); }
        @Override public void begin() { drag(X11DataExchange.BEGIN, offer.id(), 0, 0, false); }
        @Override public void finish(boolean accepted) { drag(X11DataExchange.FINISH, offer.id(), 0, 0, accepted); }
    }

    @Override public Drop createDrop(List<String> mimeTypes, AndroidContentPayload localContent,
            Content content, DragOffer localOffer) {
        List<String> targets = localContent == null ? X11ContentFormats.dragTypes(mimeTypes) : X11ContentTransfer.formats(localContent);
        if (targets.isEmpty()) return null;
        Outgoing local = localOffer instanceof Outgoing candidate && candidate.source == session ? candidate : null;
        return new Incoming(targets, content, local);
    }

    private final class Incoming implements Drop {
        final X11DataExchange.Source source;
        final Outgoing local;
        int offer;
        Incoming(List<String> targets, Content content, Outgoing local) {
            this.local = local;
            source = new X11DataExchange.Source() {
                private X11DataExchange.Source resolved;
                @Override public List<String> types() { return targets; }
                @Override public synchronized ParcelFileDescriptor open(String type) throws IOException {
                    if (resolved == null) resolved = transfer.offer(content.read());
                    return resolved.open(type);
                }
            };
        }
        @Override public void enter() {
            incoming = this;
            offer = local == null ? exchange.publish(X11DataExchange.DRAG, source) : local.offer.id();
            drag(X11DataExchange.ENTER, offer, 0, 0, local != null);
        }
        @Override public void move(float x, float y) { drag(X11DataExchange.MOVE, offer, x, y, false); }
        @Override public void leave() { drag(X11DataExchange.LEAVE, offer, 0, 0, false); }
        @Override public void drop() { drag(X11DataExchange.DROP, offer, 0, 0, false); }
        @Override public void close() { if (incoming == this) incoming = null; }
    }

    private void drag(int operation, int offer, float x, float y, boolean accepted) {
        exchange.drag(operation, offer, output.id(), output.windowId(), x, y, accepted);
    }

    @Override public void close() {
        focus(false);
        session.unlisten(this);
        incoming = null;
        listener = null;
    }
}
