package io.github.mekhontsev.magicdesk;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Authenticated localhost-only MCP Streamable HTTP transport. */
final class MagicDeskMcpHttpServer implements Closeable {
    private static final int SOCKET_TIMEOUT_MILLIS = 30_000;
    private static final int MAX_REQUEST_LINE_BYTES = 8 * 1024;
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final McpJsonRpcHandler mHandler;
    private final Supplier<String> mTokenSupplier;
    private final AtomicLong mConnections = new AtomicLong();
    private final AtomicLong mRequests = new AtomicLong();
    private final AtomicLong mRejected = new AtomicLong();
    private final ThreadPoolExecutor mWorkers = new ThreadPoolExecutor(
            2,
            4,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),
            runnable -> daemonThread(runnable, "MagicDeskMcpRequest"),
            new ThreadPoolExecutor.AbortPolicy());

    private volatile boolean mRunning;
    private volatile String mLastError = "";
    private ServerSocket mServerSocket;
    private Thread mAcceptThread;

    MagicDeskMcpHttpServer(
            final McpJsonRpcHandler handler,
            final Supplier<String> tokenSupplier) {
        if (handler == null || tokenSupplier == null) {
            throw new IllegalArgumentException(
                    "MCP HTTP dependencies are required");
        }
        mHandler = handler;
        mTokenSupplier = tokenSupplier;
    }

    synchronized void start(final String host, final int port)
            throws IOException {
        if (mRunning) {
            return;
        }
        final InetAddress address = InetAddress.getByName(host);
        if (!address.isLoopbackAddress()) {
            throw new IOException("MCP server must bind to loopback");
        }
        final ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(address, port), 16);
        mServerSocket = server;
        mRunning = true;
        mLastError = "";
        mAcceptThread = daemonThread(this::acceptLoop,
                "MagicDeskMcpAccept");
        mAcceptThread.start();
        DesktopAutomationEventJournal.record(
                "mcp", "server_start", true, host + ':' + port);
    }

    @Override
    public synchronized void close() {
        if (!mRunning && mServerSocket == null) {
            return;
        }
        mRunning = false;
        final ServerSocket server = mServerSocket;
        mServerSocket = null;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
        final Thread accept = mAcceptThread;
        mAcceptThread = null;
        if (accept != null) {
            accept.interrupt();
        }
        mWorkers.shutdownNow();
        DesktopAutomationEventJournal.record(
                "mcp", "server_stop", true, "server stopped");
    }

    Snapshot snapshot() {
        final ServerSocket server = mServerSocket;
        return new Snapshot(
                mRunning,
                server == null ? -1 : server.getLocalPort(),
                mConnections.get(),
                mRequests.get(),
                mRejected.get(),
                mLastError);
    }

    private void acceptLoop() {
        while (mRunning) {
            Socket socket = null;
            try {
                final ServerSocket server = mServerSocket;
                if (server == null) {
                    return;
                }
                socket = server.accept();
                mConnections.incrementAndGet();
                final Socket accepted = socket;
                socket = null;
                try {
                    mWorkers.execute(() -> handle(accepted));
                } catch (RejectedExecutionException error) {
                    mRejected.incrementAndGet();
                    writeAndClose(accepted, 503,
                            "Service Unavailable", "", null);
                }
            } catch (SocketException error) {
                if (mRunning) {
                    noteError(error);
                }
            } catch (IOException | RuntimeException error) {
                if (mRunning) {
                    noteError(error);
                }
            } finally {
                closeQuietly(socket);
            }
        }
    }

    private void handle(final Socket socket) {
        try (Socket connection = socket;
                BufferedInputStream input = new BufferedInputStream(
                        connection.getInputStream());
                BufferedOutputStream output = new BufferedOutputStream(
                        connection.getOutputStream())) {
            connection.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            final Request request;
            try {
                request = readRequest(input);
            } catch (IOException | RuntimeException error) {
                mRejected.incrementAndGet();
                writeResponse(output, 400, "Bad Request", "", null);
                return;
            }
            mRequests.incrementAndGet();
            final Response response = route(request);
            writeResponse(output, response.status, response.reason,
                    response.body, response.extraHeaders);
        } catch (IOException | RuntimeException error) {
            noteError(error);
        }
    }

    private Response route(final Request request) {
        if (!"/mcp".equals(request.path)) {
            return new Response(404, "Not Found", "", null);
        }
        if (!isAllowedOrigin(request.headers.get("origin"))) {
            mRejected.incrementAndGet();
            return new Response(403, "Forbidden", "", null);
        }
        if (!isAuthorized(
                request.headers.get("authorization"),
                mTokenSupplier.get())) {
            mRejected.incrementAndGet();
            final Map<String, String> headers = new HashMap<>();
            headers.put("WWW-Authenticate", "Bearer");
            return new Response(401, "Unauthorized", "", headers);
        }
        if ("GET".equals(request.method)) {
            final Map<String, String> headers = new HashMap<>();
            headers.put("Allow", "POST");
            return new Response(405, "Method Not Allowed", "", headers);
        }
        if (!"POST".equals(request.method)) {
            return new Response(405, "Method Not Allowed", "", null);
        }
        final String contentType = request.headers.get("content-type");
        if (contentType == null
                || !contentType.toLowerCase(Locale.ROOT)
                        .startsWith("application/json")) {
            return new Response(415, "Unsupported Media Type", "", null);
        }
        final McpJsonRpcResponse response = mHandler.handle(request.body);
        return new Response(
                response.httpStatus,
                reason(response.httpStatus),
                response.body,
                null);
    }

    private static Request readRequest(final BufferedInputStream input)
            throws IOException {
        final int[] consumed = new int[] {0};
        final String requestLine = readLine(
                input, MAX_REQUEST_LINE_BYTES, consumed);
        final String[] parts = requestLine.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
            throw new IOException("invalid HTTP request line");
        }
        final String method = parts[0].toUpperCase(Locale.ROOT);
        final String rawPath = parts[1];
        final int query = rawPath.indexOf('?');
        final String path = query < 0
                ? rawPath : rawPath.substring(0, query);
        final Map<String, String> headers = new HashMap<>();
        while (true) {
            final String line = readLine(input, MAX_HEADER_BYTES, consumed);
            if (line.isEmpty()) {
                break;
            }
            final int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IOException("invalid HTTP header");
            }
            final String name = line.substring(0, separator)
                    .trim().toLowerCase(Locale.ROOT);
            final String value = line.substring(separator + 1).trim();
            if (headers.put(name, value) != null) {
                throw new IOException("duplicate HTTP header");
            }
        }
        if (headers.containsKey("transfer-encoding")) {
            throw new IOException("chunked requests are unsupported");
        }
        final String lengthValue = headers.get("content-length");
        final int length;
        if (lengthValue == null) {
            length = 0;
        } else {
            try {
                length = Integer.parseInt(lengthValue);
            } catch (NumberFormatException error) {
                throw new IOException("invalid Content-Length", error);
            }
        }
        if (length < 0 || length > MAX_BODY_BYTES) {
            throw new IOException("HTTP body is too large");
        }
        final byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            final int read = input.read(body, offset, length - offset);
            if (read < 0) {
                throw new EOFException("incomplete HTTP body");
            }
            offset += read;
        }
        return new Request(
                method,
                path,
                headers,
                new String(body, StandardCharsets.UTF_8));
    }

    private static String readLine(
            final BufferedInputStream input,
            final int limit,
            final int[] total) throws IOException {
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            final int value = input.read();
            if (value < 0) {
                throw new EOFException("incomplete HTTP headers");
            }
            total[0]++;
            if (total[0] > MAX_HEADER_BYTES + MAX_REQUEST_LINE_BYTES) {
                throw new IOException("HTTP headers are too large");
            }
            if (previous == '\r' && value == '\n') {
                break;
            }
            if (previous >= 0) {
                line.write(previous);
            }
            previous = value;
            if (line.size() > limit) {
                throw new IOException("HTTP line is too large");
            }
        }
        return line.toString(StandardCharsets.US_ASCII.name());
    }

    static boolean isAllowedOrigin(final String origin) {
        if (origin == null || origin.trim().isEmpty()) {
            return true;
        }
        try {
            final URI uri = new URI(origin.trim());
            final String scheme = uri.getScheme();
            final String host = uri.getHost();
            final String path = uri.getRawPath();
            return ("http".equalsIgnoreCase(scheme)
                            || "https".equalsIgnoreCase(scheme))
                    && host != null
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && (path == null || path.isEmpty() || "/".equals(path))
                    && ("localhost".equalsIgnoreCase(host)
                            || "127.0.0.1".equals(host)
                            || "::1".equals(host)
                            || "[::1]".equals(host));
        } catch (URISyntaxException error) {
            return false;
        }
    }

    static boolean isAuthorized(
            final String authorization,
            final String expectedToken) {
        if (authorization == null || expectedToken == null
                || expectedToken.isEmpty()
                || !authorization.startsWith("Bearer ")) {
            return false;
        }
        final byte[] provided = authorization.substring("Bearer ".length())
                .getBytes(StandardCharsets.UTF_8);
        final byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(provided, expected);
    }

    private static void writeAndClose(
            final Socket socket,
            final int status,
            final String reason,
            final String body,
            final Map<String, String> headers) {
        try (Socket connection = socket;
                BufferedOutputStream output = new BufferedOutputStream(
                        connection.getOutputStream())) {
            writeResponse(output, status, reason, body, headers);
        } catch (IOException ignored) {
        }
    }

    private static void writeResponse(
            final BufferedOutputStream output,
            final int status,
            final String reason,
            final String body,
            final Map<String, String> extraHeaders) throws IOException {
        final byte[] encoded = body == null
                ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        final StringBuilder headers = new StringBuilder()
                .append("HTTP/1.1 ").append(status).append(' ')
                .append(reason).append("\r\n")
                .append("Content-Type: application/json; charset=utf-8\r\n")
                .append("Content-Length: ").append(encoded.length)
                .append("\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Connection: close\r\n");
        if (extraHeaders != null) {
            for (final Map.Entry<String, String> header
                    : extraHeaders.entrySet()) {
                headers.append(header.getKey()).append(": ")
                        .append(header.getValue()).append("\r\n");
            }
        }
        headers.append("\r\n");
        output.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
        output.write(encoded);
        output.flush();
    }

    private void noteError(final Throwable error) {
        final String message = ShellAccess.usefulMessage(error);
        mLastError = message;
        DesktopAutomationEventJournal.record(
                "mcp", "transport", false, message);
    }

    private static void closeQuietly(final Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static Thread daemonThread(
            final Runnable runnable, final String name) {
        final Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String reason(final int status) {
        switch (status) {
            case 200:
                return "OK";
            case 202:
                return "Accepted";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 500:
                return "Internal Server Error";
            default:
                return "Error";
        }
    }

    static final class Snapshot {
        final boolean running;
        final int boundPort;
        final long connections;
        final long requests;
        final long rejected;
        final String lastError;

        Snapshot(
                final boolean running,
                final int boundPort,
                final long connections,
                final long requests,
                final long rejected,
                final String lastError) {
            this.running = running;
            this.boundPort = boundPort;
            this.connections = connections;
            this.requests = requests;
            this.rejected = rejected;
            this.lastError = lastError == null ? "" : lastError;
        }
    }

    private static final class Request {
        final String method;
        final String path;
        final Map<String, String> headers;
        final String body;

        Request(
                final String method,
                final String path,
                final Map<String, String> headers,
                final String body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }
    }

    private static final class Response {
        final int status;
        final String reason;
        final String body;
        final Map<String, String> extraHeaders;

        Response(
                final int status,
                final String reason,
                final String body,
                final Map<String, String> extraHeaders) {
            this.status = status;
            this.reason = reason;
            this.body = body;
            this.extraHeaders = extraHeaders;
        }
    }
}
