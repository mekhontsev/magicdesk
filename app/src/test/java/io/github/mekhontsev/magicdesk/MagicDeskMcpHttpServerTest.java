package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public final class MagicDeskMcpHttpServerTest {
    @Test
    public void validatesOriginWithoutDnsResolution() {
        assertTrue(MagicDeskMcpHttpServer.isAllowedOrigin(null));
        assertTrue(MagicDeskMcpHttpServer.isAllowedOrigin(
                "http://localhost:8765"));
        assertTrue(MagicDeskMcpHttpServer.isAllowedOrigin(
                "https://127.0.0.1"));
        assertFalse(MagicDeskMcpHttpServer.isAllowedOrigin(
                "https://example.com"));
        assertFalse(MagicDeskMcpHttpServer.isAllowedOrigin(
                "https://localhost.example.com"));
        assertFalse(MagicDeskMcpHttpServer.isAllowedOrigin(
                "https://localhost/path"));
    }

    @Test
    public void validatesBearerToken() {
        assertTrue(MagicDeskMcpHttpServer.isAuthorized(
                "Bearer exact-token", "exact-token"));
        assertFalse(MagicDeskMcpHttpServer.isAuthorized(
                "Bearer wrong", "exact-token"));
        assertFalse(MagicDeskMcpHttpServer.isAuthorized(
                "Basic exact-token", "exact-token"));
    }

    @Test
    public void servesAuthenticatedJsonRpcOnLoopback() throws Exception {
        final MagicDeskMcpHttpServer server = new MagicDeskMcpHttpServer(
                new McpJsonRpcHandler(new EmptyBackend()),
                () -> "test-token");
        try {
            server.start("127.0.0.1", 0);
            final String body = "{\"jsonrpc\":\"2.0\",\"id\":1,"
                    + "\"method\":\"ping\"}";
            final String response = request(
                    server.snapshot().boundPort,
                    "POST /mcp HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Authorization: Bearer test-token\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: "
                            + body.getBytes(StandardCharsets.UTF_8).length
                            + "\r\n\r\n" + body);

            assertTrue(response.startsWith("HTTP/1.1 200 OK"));
            assertTrue(response.contains("\"result\":{}"));
        } finally {
            server.close();
        }
    }

    @Test
    public void rejectsUnauthenticatedRequests() throws Exception {
        final MagicDeskMcpHttpServer server = new MagicDeskMcpHttpServer(
                new McpJsonRpcHandler(new EmptyBackend()),
                () -> "test-token");
        try {
            server.start("127.0.0.1", 0);
            final String response = request(
                    server.snapshot().boundPort,
                    "POST /mcp HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: 0\r\n\r\n");

            assertTrue(response.startsWith(
                    "HTTP/1.1 401 Unauthorized"));
        } finally {
            server.close();
        }
    }

    @Test(timeout = 10_000)
    public void closeReleasesActiveAndQueuedConnections() throws Exception {
        final var server = new MagicDeskMcpHttpServer(
                new McpJsonRpcHandler(new EmptyBackend()), () -> "test-token");
        final var sockets = new ArrayList<Socket>();
        try {
            server.start("127.0.0.1", 0);
            for (int index = 0; index < 8; index++) {
                final var socket = new Socket("127.0.0.1", server.snapshot().boundPort);
                socket.setSoTimeout(500);
                sockets.add(socket);
            }
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (server.snapshot().connections < sockets.size()
                    && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(sockets.size(), server.snapshot().connections);
            server.close();
            for (final var socket : sockets) {
                try {
                    assertEquals(-1, socket.getInputStream().read());
                } catch (SocketException closed) {
                    // A reset and an orderly EOF both mean ownership was released.
                }
            }
        } finally {
            server.close();
            for (final var socket : sockets) {
                socket.close();
            }
        }
    }

    @Test
    public void closingAListenerDoesNotCloseTheSharedBackend() {
        final var backend = new EmptyBackend();
        final var handler = new McpJsonRpcHandler(backend);
        final var server = new MagicDeskMcpHttpServer(
                handler, () -> "test-token");
        server.close();
        server.close();
        assertEquals(0, backend.closes);
        handler.close();
        assertEquals(1, backend.closes);
    }

    @Test
    public void networkOriginMustMatchTheExplicitEndpoint() {
        final String allowed = "http://192.168.1.10:8765";
        assertTrue(MagicDeskMcpHttpServer.isAllowedNetworkOrigin(null, allowed));
        assertTrue(MagicDeskMcpHttpServer.isAllowedNetworkOrigin(allowed, allowed));
        for (String other : java.util.List.of("", "null", "http://localhost:8765",
                "http://192.168.1.10:8766", "http://192.168.1.10:8765.evil.test",
                "https://192.168.1.10:8765")) {
            assertFalse(MagicDeskMcpHttpServer.isAllowedNetworkOrigin(other, allowed));
        }
    }

    @Test
    public void networkBindingNeverAcceptsWildcardLoopbackOrPublicAddresses() throws Exception {
        for (String address : java.util.List.of("0.0.0.0", "127.0.0.1", "8.8.8.8",
                "224.0.0.1", "::")) {
            assertFalse(McpNetworkInterfaces.isAllowed(java.net.InetAddress.getByName(address)));
        }
        for (String address : java.util.List.of("192.168.1.2", "10.87.232.121",
                "172.16.0.1", "100.64.0.1", "100.127.255.254")) {
            assertTrue(McpNetworkInterfaces.isAllowed(java.net.InetAddress.getByName(address)));
        }
        assertFalse(McpNetworkInterfaces.isAllowed(java.net.InetAddress.getByName("100.128.0.1")));
    }

    @Test
    public void closedServerCannotAdvertiseADeadWorkerPool() throws Exception {
        final var server = new MagicDeskMcpHttpServer(
                new McpJsonRpcHandler(new EmptyBackend()), () -> "test-token");
        try {
            server.start("127.0.0.1", 0);
            server.close();
            try {
                server.start("127.0.0.1", 0);
                fail("closed server must reject restart");
            } catch (IOException expected) {
                assertFalse(server.snapshot().running);
            }
        } finally {
            server.close();
        }
    }

    private static String request(final int port, final String request)
            throws Exception {
        return request(port, request, "127.0.0.1");
    }

    private static String request(final int port, final String request, final String host)
            throws Exception {
        try (Socket socket = new Socket(host, port);
                OutputStream output = socket.getOutputStream();
                InputStream input = socket.getInputStream();
                ByteArrayOutputStream response = new ByteArrayOutputStream()) {
            output.write(request.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            final byte[] buffer = new byte[2048];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                response.write(buffer, 0, read);
            }
            return response.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Test public void separateTokensAndListenerShutdownPreserveTheOtherConnection() throws Exception {
        final var backend = new EmptyBackend();
        final var handler = new McpJsonRpcHandler(backend);
        final var remoteToken = new java.util.concurrent.atomic.AtomicReference<>("remote-token");
        final var local = new MagicDeskMcpHttpServer(handler, () -> "local-token");
        final var remote = new MagicDeskMcpHttpServer(handler, remoteToken::get);
        try {
            local.start("127.0.0.1", 0);
            remote.start("127.0.0.1", 0);
            assertTrue(ping(local, "remote-token").contains("401 Unauthorized"));
            assertTrue(ping(remote, "local-token").contains("401 Unauthorized"));
            assertTrue(ping(remote, "remote-token").contains("200 OK"));
            remoteToken.set("replacement-token");
            assertTrue(ping(remote, "remote-token").contains("401 Unauthorized"));
            assertTrue(ping(remote, "replacement-token").contains("200 OK"));
            remote.close();
            assertEquals(0, backend.closes);
            assertTrue(ping(local, "local-token").contains("200 OK"));
        } finally {
            local.close();
            remote.close();
            handler.close();
        }
        assertEquals(1, backend.closes);
    }

    private static String ping(final MagicDeskMcpHttpServer server, final String token) throws Exception {
        final String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        return request(server.snapshot().boundPort,
                "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + token
                        + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length()
                        + "\r\n\r\n" + body);
    }

    private static final class EmptyBackend implements McpBackend {
        int closes;

        @Override
        public void close() {
            closes++;
        }

        @Override
        public JSONArray listTools() {
            return new JSONArray();
        }

        @Override
        public JSONObject callTool(
                final String name, final JSONObject arguments) {
            return new JSONObject();
        }

        @Override
        public JSONArray listResources() {
            return new JSONArray();
        }

        @Override
        public String readResource(final String uri) {
            return "{}";
        }
    }
}
