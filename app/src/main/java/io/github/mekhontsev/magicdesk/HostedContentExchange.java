package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ClipDescription;
import android.os.Handler;
import android.os.Looper;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.View;

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

/** Android clipboard focus, drag gestures and temporary URI grants for a hosted surface. */
final class HostedContentExchange implements AutoCloseable, View.OnDragListener, HostedContentBackend.Listener {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long DROP_DEADLINE_MILLIS = 30_000;
    private static final String DRAG_ID = "io.github.mekhontsev.magicdesk.hosted.drag";
    // Android has one global drag; localState is delivered only to its source ViewRoot.
    // Other hosts match this short-lived offer by the ClipDescription token.
    private static Outgoing activeDrag;
    private final Activity activity;
    private final HostedSurfaceView surface;
    private final HostedContentBackend backend;
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), r -> new Thread(r, "HostedContent"));
    private Future<?> clipboardRead;
    private Future<?> dragRead;
    private HostedContentBackend.DragOffer pendingDrag;
    private final String identity;
    private AutoCloseable clipboardObserver;
    private boolean focused, closed;
    private long clipboardRevision;
    private Outgoing outgoing;
    private Incoming incoming;

    private static final class Outgoing {
        final HostedContentExchange source;
        final HostedContentBackend.DragOffer offer;
        final AndroidContentPayload content;
        final String id = UUID.randomUUID().toString();
        boolean hostedTarget, completed;
        Outgoing(HostedContentExchange source, HostedContentBackend.DragOffer offer, AndroidContentPayload content) {
            this.source = source; this.offer = offer; this.content = content;
        }
        void complete(boolean success) {
            if (completed) return;
            completed = true;
            offer.finish(success);
            source.surface.endContentDrag();
            source.outgoing = null;
            if (activeDrag == this) activeDrag = null;
        }
    }

    private final class Incoming implements HostedContentBackend.Content {
        final Outgoing local;
        final CompletableFuture<AndroidContentPayload> available = new CompletableFuture<>();
        final Runnable deadline = () -> finishIncoming(this, false);
        DragAndDropPermissions permissions;
        final HostedContentBackend.Drop target;
        boolean entered, dropped, finished;
        Incoming(DragEvent event) {
            var extras = event.getClipDescription() == null ? null : event.getClipDescription().getExtras();
            local = activeDrag != null && extras != null && activeDrag.id.equals(extras.getString(DRAG_ID))
                    ? activeDrag : null;
            if (local != null) available.complete(local.content);
            target = backend.createDrop(dragTypes(event.getClipDescription()), local == null ? null : local.content,
                    this, local == null ? null : local.offer);
        }
        @Override public AndroidContentPayload read() throws IOException {
            try { return available.get(DROP_DEADLINE_MILLIS, TimeUnit.MILLISECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException(); }
            catch (java.util.concurrent.ExecutionException error) { throw new IOException("Drop cancelled", error.getCause()); }
            catch (java.util.concurrent.TimeoutException error) { throw new IOException("Drop data deadline expired", error); }
        }
    }

    HostedContentExchange(Activity activity, HostedSurfaceView surface, HostedContentBackend backend) {
        this.activity = activity; this.surface = surface; this.backend = backend;
        identity = backend.clipboardIdentity();
        backend.listen(this);
        surface.setOnDragListener(this);
        // Window-focus callbacks can follow the first click. Import the Android
        // selection before that click can copy/paste inside the guest, never after it.
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
            backend.focus(true);
            clipboardObserver = AndroidClipboardGateway.get(activity).observe(this::publishClipboard);
            publishClipboard();
        } else backend.focus(false);
    }

    private void publishClipboard() {
        if (!focused || closed) return;
        var read = AndroidClipboardGateway.get(activity).readContent();
        if (read.metadata.access == AndroidClipboardGateway.Access.EMPTY) {
            clipboardRevision++;
            cancelClipboardRead();
            try { backend.publishClipboard(AndroidContentPayload.empty(AndroidContentPayload.Origin.CLIPBOARD)); }
            catch (RuntimeException error) { report(error); }
            return;
        }
        if (read.content == null || read.metadata.access != AndroidClipboardGateway.Access.AVAILABLE) return;
        if (read.source.startsWith(identity)) return;
        clipboardRevision++;
        cancelClipboardRead();
        try { backend.publishClipboard(read.content); }
        catch (RuntimeException error) { report(error); }
    }

    @Override public void clipboardOffered(HostedContentBackend.Content offer) {
        if (closed || !focused) return;
        cancelClipboardRead();
        long revision = ++clipboardRevision;
        try { clipboardRead = io.submit(() -> {
            try {
                AndroidContentPayload result = offer.read();
                MAIN.post(() -> {
                    if (!closed && focused && clipboardRevision == revision && !result.isEmpty()) {
                        var write = AndroidClipboardGateway.get(activity).writeContent(result, identity + UUID.randomUUID());
                        if (!write.successful) report(new IOException(write.error));
                    }
                });
            } catch (IOException | RuntimeException error) { report(error); }
        }); } catch (RejectedExecutionException error) { report(error); }
    }

    @Override public void dragOffered(HostedContentBackend.DragOffer offer) {
        if (!closed && outgoing == null && surface.canStartContentDrag()) {
            cancelDragRead();
            pendingDrag = offer;
            try { dragRead = io.submit(() -> {
                try {
                    AndroidContentPayload result = offer.read();
                    AndroidContentPayload transport = dragPayload(result);
                    MAIN.post(() -> startOutgoing(offer, result, transport));
                } catch (IOException | RuntimeException error) { report(error); }
            }); } catch (RejectedExecutionException error) { report(error); }
        }
    }

    private void cancelClipboardRead() {
        if (clipboardRead != null) { clipboardRead.cancel(true); clipboardRead = null; io.purge(); }
    }

    private void cancelDragRead() {
        pendingDrag = null;
        if (dragRead != null) { dragRead.cancel(true); dragRead = null; io.purge(); }
    }

    private void startOutgoing(HostedContentBackend.DragOffer offer, AndroidContentPayload payload, AndroidContentPayload transport) {
        if (closed || pendingDrag != offer || outgoing != null || payload.isEmpty() || !surface.canStartContentDrag()) return;
        pendingDrag = null;
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
        offer.begin();
    }

    @Override public boolean onDrag(View view, DragEvent event) {
        if (closed) return false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                if (incoming != null) finishIncoming(incoming, false);
                Incoming next = new Incoming(event);
                if (next.target == null) return false;
                incoming = next;
                return true;
            case DragEvent.ACTION_DRAG_ENTERED:
                if (incoming == null) return false;
                enter(incoming);
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                if (incoming == null) return false;
                if (!incoming.entered) enter(incoming);
                point(event);
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                if (incoming != null && incoming.entered) {
                    incoming.target.leave();
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
                if (incoming.local != null) incoming.local.hostedTarget = true;
                incoming.available.complete(payload);
                MAIN.postDelayed(incoming.deadline, DROP_DEADLINE_MILLIS);
                point(event);
                incoming.target.drop();
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                if (incoming != null && !incoming.dropped) finishIncoming(incoming, false);
                if (outgoing != null && !outgoing.hostedTarget) outgoing.complete(event.getResult());
                return true;
            default: return true;
        }
    }

    private void enter(Incoming value) {
        value.target.enter();
        value.entered = true;
    }

    private void point(DragEvent event) {
        android.graphics.PointF point = surface.contentPoint(event.getX(), event.getY());
        incoming.target.move(point.x, point.y);
    }

    @Override public void dropFinished(HostedContentBackend.Drop drop, boolean accepted) {
        if (incoming != null && incoming.target == drop) finishIncoming(incoming, accepted);
    }

    @Override public void dragCancelled() {
        cancelDragRead();
        if (outgoing != null) {
            surface.cancelDragAndDrop();
            outgoing.complete(false);
        }
    }

    private void finishIncoming(Incoming value, boolean success) {
        if (value.finished) return;
        value.finished = true;
        MAIN.removeCallbacks(value.deadline);
        value.available.completeExceptionally(new IOException("Drag ended"));
        value.target.close();
        if (value.permissions != null) value.permissions.release();
        if (value.local != null && value.dropped) value.local.complete(success);
        if (incoming == value) incoming = null;
    }

    private static List<String> dragTypes(ClipDescription description) {
        if (description == null) return List.of();
        List<String> types = new ArrayList<>();
        for (int i = 0; i < description.getMimeTypeCount(); i++) types.add(description.getMimeType(i));
        return List.copyOf(types);
    }

    private void report(Exception error) {
        if (!Thread.currentThread().isInterrupted()) android.util.Log.w("MagicDesk", "Hosted content transfer: " + ShellAccess.usefulMessage(error));
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
        backend.close();
        io.shutdownNow();
    }

    private AndroidContentPayload dragPayload(AndroidContentPayload payload) throws IOException {
        // ClipData is broadcast to windows through Binder; retain large inline content
        // for local hosts and give other applications a readable document instead.
        if ((long) payload.text.length() + payload.htmlText.length() <= 32 * 1024) return payload;
        boolean html = !payload.htmlText.isEmpty();
        byte[] bytes = (html ? payload.htmlText : payload.text).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        android.net.Uri uri = GeneratedContentProvider.publish(activity, html ? "selection.html" : "selection.txt", out -> out.write(bytes));
        List<AndroidContentPayload.UriItem> items = new ArrayList<>(payload.uriItems);
        items.add(new AndroidContentPayload.UriItem(uri, html ? "text/html" : "text/plain"));
        return AndroidContentPayload.uris(payload.label, items, List.of(), payload.origin);
    }
}
