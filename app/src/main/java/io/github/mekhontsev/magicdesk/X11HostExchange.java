package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ClipDescription;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.View;
import com.termux.x11.X11DataExchange;
import com.termux.x11.X11Session;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Android focus/gesture adapter. Content conversion and X11 protocol have separate owners. */
final class X11HostExchange implements AutoCloseable, View.OnDragListener {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long DROP_DEADLINE_MILLIS = 30_000;
    private static final String DRAG_ID = "io.github.mekhontsev.magicdesk.x11.drag";
    // Android has one global drag; localState is delivered only to its source ViewRoot.
    // Other hosts match this short-lived offer by the ClipDescription token.
    private static Outgoing activeDrag;
    private final Activity activity;
    private final X11Sessions.Session session;
    private final X11Sessions.Listener owner;
    private final X11Session.Output output;
    private final X11SurfaceView surface;
    private final X11DataExchange exchange;
    private final X11ContentTransfer content;
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), r -> new Thread(r, "X11HostContent"));
    private Future<?> clipboardRead;
    private Future<?> dragRead;
    private int pendingDrag;
    private final String identity;
    private AutoCloseable clipboardObserver;
    private boolean focused, closed;
    private long clipboardRevision;
    private Outgoing outgoing;
    private Incoming incoming;

    private static final class Outgoing {
        final X11HostExchange source;
        final X11DataExchange.Offer offer;
        final AndroidContentPayload content;
        final String id = UUID.randomUUID().toString();
        boolean x11Target, completed;
        Outgoing(X11HostExchange source, X11DataExchange.Offer offer, AndroidContentPayload content) {
            this.source = source; this.offer = offer; this.content = content;
        }
        void complete(boolean success) {
            if (completed) return;
            completed = true;
            source.exchange.drag(X11DataExchange.FINISH, offer.id(), source.output.id(), source.output.windowId(), 0, 0, success);
            source.surface.endContentDrag();
            source.outgoing = null;
            if (activeDrag == this) activeDrag = null;
        }
    }

    private final class Incoming implements X11DataExchange.Source {
        final List<String> types;
        final Outgoing local;
        final CompletableFuture<X11DataExchange.Source> available = new CompletableFuture<>();
        final Runnable deadline = () -> finishIncoming(this, false);
        DragAndDropPermissions permissions;
        int offer;
        boolean entered, dropped, finished;
        Incoming(DragEvent event) {
            var extras = event.getClipDescription() == null ? null : event.getClipDescription().getExtras();
            local = activeDrag != null && extras != null && activeDrag.id.equals(extras.getString(DRAG_ID))
                    ? activeDrag : null;
            types = local == null ? dragTypes(event.getClipDescription()) : X11ContentTransfer.formats(local.content);
            if (local != null) available.complete(content.offer(local.content));
        }
        @Override public List<String> types() { return types; }
        @Override public ParcelFileDescriptor open(String type) throws IOException {
            try { return available.get(DROP_DEADLINE_MILLIS, TimeUnit.MILLISECONDS).open(type); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException(); }
            catch (java.util.concurrent.ExecutionException error) { throw new IOException("Drop cancelled", error.getCause()); }
            catch (java.util.concurrent.TimeoutException error) { throw new IOException("Drop data deadline expired", error); }
        }
    }

    X11HostExchange(Activity activity, X11Sessions.Session session, X11Sessions.Listener owner,
            X11SurfaceView surface, X11Session.Output output) {
        this.activity = activity; this.session = session; this.owner = owner; this.surface = surface; this.output = output;
        exchange = session.dataExchange();
        content = new X11ContentTransfer(activity, session);
        identity = "x11:" + session.id() + ":";
        surface.setOnDragListener(this);
        // Window-focus callbacks can follow the first click. Import the Android
        // selection before that click can copy/paste inside X11, never after it.
        surface.beforeInteraction(() -> focus(true));
    }

    void focus(boolean value) {
        if (closed || focused == value) return;
        focused = value;
        cancelClipboardRead();
        clipboardRevision++;
        if (clipboardObserver != null) {
            try { clipboardObserver.close(); } catch (Exception error) { report(error); }
            clipboardObserver = null;
        }
        if (focused) {
            session.claimClipboard(owner);
            clipboardObserver = AndroidClipboardGateway.get(activity).observe(this::publishClipboard);
            publishClipboard();
        } else session.releaseClipboard(owner);
    }

    private void publishClipboard() {
        if (!focused || closed) return;
        var read = AndroidClipboardGateway.get(activity).readContent();
        if (read.metadata.access == AndroidClipboardGateway.Access.EMPTY) {
            clipboardRevision++;
            cancelClipboardRead();
            try { exchange.publish(X11DataExchange.CLIPBOARD, content.offer(AndroidContentPayload.empty(AndroidContentPayload.Origin.CLIPBOARD))); }
            catch (RuntimeException error) { report(error); }
            return;
        }
        if (read.content == null || read.metadata.access != AndroidClipboardGateway.Access.AVAILABLE) return;
        if (read.source.startsWith(identity)) return;
        clipboardRevision++;
        cancelClipboardRead();
        try { exchange.publish(X11DataExchange.CLIPBOARD, content.offer(read.content)); }
        catch (RuntimeException error) { report(error); }
    }

    void offer(X11DataExchange.Offer offer) {
        if (closed) return;
        if (offer.channel() == X11DataExchange.CLIPBOARD) {
            if (!focused) return;
            cancelClipboardRead();
            long revision = ++clipboardRevision;
            try { clipboardRead = io.submit(() -> {
                try {
                    AndroidContentPayload result = content.receive(offer);
                    MAIN.post(() -> {
                        if (!closed && focused && clipboardRevision == revision && !result.isEmpty()) {
                            var write = AndroidClipboardGateway.get(activity).writeContent(result, identity + UUID.randomUUID());
                            if (!write.successful) report(new IOException(write.error));
                        }
                    });
                } catch (IOException | RuntimeException error) { report(error); }
            }); } catch (RejectedExecutionException error) { report(error); }
        } else if (offer.output() == output.id() && outgoing == null && surface.canStartContentDrag()) {
            cancelDragRead();
            pendingDrag = offer.id();
            try { dragRead = io.submit(() -> {
                try {
                    AndroidContentPayload result = content.receive(offer);
                    AndroidContentPayload transport = content.dragPayload(result);
                    MAIN.post(() -> startOutgoing(offer, result, transport));
                } catch (IOException | RuntimeException error) { report(error); }
            }); } catch (RejectedExecutionException error) { report(error); }
        }
    }

    private void cancelClipboardRead() {
        if (clipboardRead != null) { clipboardRead.cancel(true); clipboardRead = null; io.purge(); }
    }

    private void cancelDragRead() {
        pendingDrag = 0;
        if (dragRead != null) { dragRead.cancel(true); dragRead = null; io.purge(); }
    }

    private void startOutgoing(X11DataExchange.Offer offer, AndroidContentPayload payload, AndroidContentPayload transport) {
        if (closed || pendingDrag != offer.id() || outgoing != null || payload.isEmpty() || !surface.canStartContentDrag()) return;
        pendingDrag = 0;
        Outgoing next = new Outgoing(this, offer, payload);
        outgoing = next;
        activeDrag = next;
        surface.beginContentDrag();
        android.widget.TextView preview = new android.widget.TextView(activity);
        preview.setText(payload.uriItems.isEmpty() ? "Text" : payload.uriItems.size() == 1 ? "File" : "Files");
        preview.setTextColor(android.graphics.Color.WHITE);
        preview.setBackgroundColor(0xcc333333);
        int padding = Math.round(12 * activity.getResources().getDisplayMetrics().density);
        preview.setPadding(padding, padding / 2, padding, padding / 2);
        preview.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        preview.layout(0, 0, preview.getMeasuredWidth(), preview.getMeasuredHeight());
        boolean started;
        try {
            var clip = transport.toClipData();
            var metadata = clip.getDescription().getExtras();
            metadata = metadata == null ? new android.os.PersistableBundle() : new android.os.PersistableBundle(metadata);
            metadata.putString(DRAG_ID, next.id);
            clip.getDescription().setExtras(metadata);
            started = surface.startDragAndDrop(clip, new View.DragShadowBuilder(preview), next,
                    View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ);
        } catch (RuntimeException error) { report(error); next.complete(false); return; }
        if (!started) { next.complete(false); return; }
        exchange.drag(X11DataExchange.BEGIN, offer.id(), output.id(), output.windowId(), 0, 0, false);
    }

    @Override public boolean onDrag(View view, DragEvent event) {
        if (closed) return false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                if (incoming != null) finishIncoming(incoming, false);
                Incoming next = new Incoming(event);
                if (next.types.isEmpty()) return false;
                incoming = next;
                return true;
            case DragEvent.ACTION_DRAG_ENTERED:
                if (incoming == null) return false;
                enter(incoming);
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                if (incoming == null) return false;
                if (!incoming.entered) enter(incoming);
                point(X11DataExchange.MOVE, event);
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                if (incoming != null && incoming.entered) {
                    exchange.drag(X11DataExchange.LEAVE, incoming.offer, output.id(), output.windowId(), 0, 0, false);
                    incoming.entered = false;
                }
                return true;
            case DragEvent.ACTION_DROP:
                if (incoming == null || event.getClipData() == null) return false;
                if (!incoming.entered) enter(incoming);
                incoming.permissions = activity.requestDragAndDropPermissions(event);
                AndroidContentPayload payload = AndroidContentPayload.fromClipData(event.getClipData(), AndroidContentPayload.Origin.DRAG);
                if (payload.isEmpty() || payload.truncated) { finishIncoming(incoming, false); return false; }
                incoming.dropped = true;
                if (incoming.local != null) incoming.local.x11Target = true;
                incoming.available.complete(content.offer(payload));
                MAIN.postDelayed(incoming.deadline, DROP_DEADLINE_MILLIS);
                point(X11DataExchange.MOVE, event);
                exchange.drag(X11DataExchange.DROP, incoming.offer, output.id(), output.windowId(), 0, 0, false);
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                if (incoming != null && !incoming.dropped) finishIncoming(incoming, false);
                if (outgoing != null && !outgoing.x11Target) outgoing.complete(event.getResult());
                return true;
            default: return true;
        }
    }

    private void enter(Incoming value) {
        boolean sameServer = value.local != null && value.local.source.session == session;
        value.offer = sameServer ? value.local.offer.id() : exchange.publish(X11DataExchange.DRAG, value);
        exchange.drag(X11DataExchange.ENTER, value.offer, output.id(), output.windowId(), 0, 0, sameServer);
        value.entered = true;
    }

    private void point(int operation, DragEvent event) {
        android.graphics.PointF point = surface.contentPoint(event.getX(), event.getY());
        exchange.drag(operation, incoming.offer, output.id(), output.windowId(), point.x, point.y, false);
    }

    void dragEvent(int operation, int sourceOutput, boolean accepted) {
        if (sourceOutput != output.id()) return;
        if (operation == X11DataExchange.FINISH && incoming != null) finishIncoming(incoming, accepted);
        else if (operation == X11DataExchange.CANCEL) {
            cancelDragRead();
            if (outgoing != null) {
                surface.cancelDragAndDrop();
                outgoing.complete(false);
            }
        }
    }

    private void finishIncoming(Incoming value, boolean success) {
        if (value.finished) return;
        value.finished = true;
        MAIN.removeCallbacks(value.deadline);
        value.available.completeExceptionally(new IOException("Drag ended"));
        if (value.permissions != null) value.permissions.release();
        if (value.local != null && value.dropped) value.local.complete(success);
        if (incoming == value) incoming = null;
    }

    private static List<String> dragTypes(ClipDescription description) {
        if (description == null) return List.of();
        List<String> types = new ArrayList<>();
        for (int i = 0; i < description.getMimeTypeCount(); i++) types.add(description.getMimeType(i));
        return X11ContentFormats.dragTypes(types);
    }

    private void report(Exception error) {
        if (!Thread.currentThread().isInterrupted()) android.util.Log.w("MagicDesk", "X11 content transfer: " + ShellAccess.usefulMessage(error));
    }

    @Override public void close() {
        if (closed) return;
        focus(false);
        cancelDragRead();
        if (outgoing != null) { surface.cancelDragAndDrop(); outgoing.complete(false); }
        if (incoming != null) finishIncoming(incoming, false);
        closed = true;
        surface.setOnDragListener(null);
        surface.beforeInteraction(null);
        io.shutdownNow();
    }
}
