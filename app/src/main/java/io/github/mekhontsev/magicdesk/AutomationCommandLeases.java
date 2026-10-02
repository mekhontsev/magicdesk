package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** A delegated key lives only while its launch owner holds the registration connection. */
final class AutomationCommandLeases implements AutoCloseable {
    private final Map<String, Lease> leases = new HashMap<>();
    private boolean closed;

    synchronized Lease create(Socket owner, BooleanSupplier valid, Runnable cleanup) throws IOException {
        if (closed || leases.size() >= 32) throw new IOException("Command lease limit reached or runtime closed");
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        Lease lease = new Lease(Base64.getUrlEncoder().withoutPadding().encodeToString(secret), owner, valid, cleanup);
        leases.put(lease.key, lease);
        return lease;
    }

    synchronized Lease acquire(String key, Socket client) throws IOException {
        Lease lease = leases.get(key);
        if (lease == null || lease.closed || !lease.valid.getAsBoolean()) throw new IOException("Command launch access is unavailable");
        lease.clients.add(client);
        return lease;
    }

    final class Lease implements AutoCloseable {
        final String key;
        private final Socket owner;
        private final BooleanSupplier valid;
        private final Runnable cleanup;
        private final Set<Socket> clients = new HashSet<>();
        private boolean closed;
        private Lease(String key, Socket owner, BooleanSupplier valid, Runnable cleanup) {
            this.key = key; this.owner = owner; this.valid = valid; this.cleanup = cleanup;
        }
        void requireActive() throws IOException {
            synchronized (AutomationCommandLeases.this) {
                if (closed || !valid.getAsBoolean()) throw new IOException("Command launch access was revoked");
            }
        }
        void release(Socket client) { synchronized (AutomationCommandLeases.this) { clients.remove(client); } }
        @Override public void close() {
            final Set<Socket> connections;
            synchronized (AutomationCommandLeases.this) {
                if (closed) return;
                closed = true; leases.remove(key);
                connections = new HashSet<>(clients); clients.clear();
            }
            disconnect(owner);
            for (Socket client : connections) disconnect(client);
            cleanup.run();
        }
    }

    @Override public void close() {
        final Set<Lease> owners;
        synchronized (this) { closed = true; owners = new HashSet<>(leases.values()); }
        for (Lease lease : owners) lease.close();
    }
    private static void disconnect(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
