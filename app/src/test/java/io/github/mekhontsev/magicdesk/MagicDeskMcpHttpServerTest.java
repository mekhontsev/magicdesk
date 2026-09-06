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
    public void closeBeforeStartReleasesBackendOnlyOnce() {
        final var backend = new EmptyBackend();
        final var server = new MagicDeskMcpHttpServer(
                new McpJsonRpcHandler(backend), () -> "test-token");
        server.close();
        server.close();
        assertEquals(1, backend.closes);
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
        try (Socket socket = new Socket("127.0.0.1", port);
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
