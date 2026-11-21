package io.github.mekhontsev.magicdesk;

import android.os.Bundle;
import java.io.Closeable;
import java.io.IOException;

/** A client admission grant borrows a protocol server, never its command executor. */
final class GraphicalConnection implements Closeable {
    private final OperationResources resources = new OperationResources();
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    private final Bundle description;
    private final Runnable ended;

    private GraphicalConnection(Bundle description, Runnable ended) { this.description=description; this.ended=ended; }

    static GraphicalConnection acquire(OperationResources server, int uid, ExecutorSocketEndpoint.Factory factory,
            ExecutorSocketEndpoint.Sink sink, Bundle description, Runnable ended) throws IOException {
        var result=new GraphicalConnection(new Bundle(description),ended);
        var owner=server.reserve();
        result.resources.reserve().attach(owner);
        owner.attach(result);
        try {
            var streams=new UnixConnectionScope(android.os.Looper.getMainLooper().getQueue());
            result.resources.reserve().attach(streams);
            var slot=result.resources.reserve();
            var endpoint=new ExecutorSocketEndpoint(uid,0,factory,fd -> {
                Closeable retained=streams.retain(fd);
                try { sink.accept(fd); }
                catch (IOException | android.os.RemoteException | RuntimeException error) { retained.close(); throw error; }
            },error -> result.close());
            slot.attach(endpoint);
            result.description.putString("graphicsEndpoint",endpoint.name);
            if (result.closed.get()) throw new IOException("Graphical session closed during admission");
            return result;
        } catch (IOException | RuntimeException error) { result.close(); throw error; }
    }

    Bundle describe() {
        if (closed.get()) throw new IllegalStateException("Graphical connection is closed");
        return new Bundle(description);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false,true)) return;
        resources.close(); ended.run();
    }
}
