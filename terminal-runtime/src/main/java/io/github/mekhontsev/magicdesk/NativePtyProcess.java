package io.github.mekhontsev.magicdesk;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** A caller-owned native PTY relay. No app context, privilege service or guest policy. */
public final class NativePtyProcess implements AutoCloseable {
    private final Process process;
    private final DataOutputStream control;
    private final InputStream input;
    private final PtyEndpoint endpoint;
    private final OperationResources.Slot owner;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    public NativePtyProcess(ProcessBuilder builder) throws IOException {
        this(builder, null);
    }

    public NativePtyProcess(ProcessBuilder builder, OperationResources resources) throws IOException {
        this(builder.redirectErrorStream(true).start(), resources);
    }

    NativePtyProcess(Process child, OperationResources resources) throws IOException {
        process = child;
        input = process.getInputStream();
        control = new DataOutputStream(process.getOutputStream());
        owner = resources == null ? null : resources.reserve();
        if (owner != null) owner.attach(this::close);
        var ready = new CompletableFuture<PtyEndpoint>();
        new Thread(() -> {
            try { ready.complete(PtyControlProtocol.readEndpoint(input)); }
            catch (IOException error) { ready.completeExceptionally(error); }
        }, "NativePtyHandshake").start();
        try {
            // EVENT_WAIT: native PTY header; expiry cancels the relay and its owned child tree.
            endpoint = ready.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            close(); Thread.currentThread().interrupt(); throw new IOException("PTY startup interrupted", error);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            close(); throw new IOException("PTY startup failed", error);
        }
    }

    public InputStream inputStream() { return input; }
    public PtyEndpoint endpoint() { return endpoint; }
    public synchronized void write(byte[] data) throws IOException {
        if (closed.get()) throw new IOException("PTY is closed");
        PtyControlProtocol.writeData(control, data); control.flush();
    }
    public synchronized void resize(int rows, int columns) throws IOException {
        if (closed.get()) throw new IOException("PTY is closed");
        PtyControlProtocol.writeResize(control, rows, columns); control.flush();
    }
    @Override public void close() {
        // Cancellation must not wait behind a blocked control-pipe writer.
        if (!closed.compareAndSet(false,true)) return;
        process.destroy();
        try { control.close(); } catch (IOException ignored) { }
        try { input.close(); } catch (IOException ignored) { }
        if (owner != null) owner.close();
    }
}
