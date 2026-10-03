package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class HttpDownloadTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final URI URL = URI.create("https://download.test/file");

    static final class Response extends HttpURLConnection {
        int status = 200;
        long length = -1;
        boolean disconnected;
        IOException failure;
        InputStream body = new ByteArrayInputStream(new byte[0]);
        final Map<String, String> headers = new HashMap<>();
        Response(URI uri) throws IOException { super(uri.toURL()); }
        Response data(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            body = new ByteArrayInputStream(bytes);
            length = bytes.length;
            return this;
        }
        @Override public int getResponseCode() throws IOException {
            if (failure != null) throw failure;
            return status;
        }
        @Override public long getContentLengthLong() { return length; }
        @Override public String getHeaderField(String name) { return headers.get(name); }
        @Override public InputStream getInputStream() { return body; }
        @Override public void disconnect() { disconnected = true; }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
    }

    private Path target() throws IOException {
        Path path = temporary.newFolder().toPath().resolve("script.sh");
        Files.writeString(path, "old");
        return path;
    }

    private static void clean(Path target, String expected) throws IOException {
        assertEquals(expected, Files.readString(target));
        try (var files = Files.list(target.getParent())) { assertEquals(1, files.count()); }
    }

    @Test public void replacesOnlyAfterCompleteVerifiedTransfer() throws Exception {
        Path target = target();
        Response response = new Response(URL).data("new script");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("new script".getBytes(StandardCharsets.UTF_8)));
        AtomicLong seen = new AtomicLong();
        assertEquals(10, HttpDownload.save(uri -> response, URL, target, hash.toUpperCase(java.util.Locale.ROOT), (bytes, total) -> {
            assertEquals(10, total);
            try { assertEquals("old", Files.readString(target)); }
            catch (IOException error) { throw new AssertionError(error); }
            seen.set(bytes);
        }));
        assertEquals(10, seen.get());
        clean(target, "new script");
        assertTrue(response.disconnected);
        assertFalse(response.getInstanceFollowRedirects());
        assertFalse(response.getUseCaches());
        assertEquals("identity", response.getRequestProperty("Accept-Encoding"));
        assertEquals(30_000, response.getConnectTimeout());
        assertEquals(60_000, response.getReadTimeout());
    }

    @Test public void failuresKeepOldFileAndRemovePartialData() throws Exception {
        for (String scenario : List.of("http", "partial", "short", "long", "hash", "read", "connect", "encoding")) {
            Path target = target();
            Response response = new Response(URL).data("new script");
            String hash = null;
            switch (scenario) {
                case "http" -> response.status = 404;
                case "partial" -> response.status = 206;
                case "short" -> response.length = 20;
                case "long" -> response.length = 2;
                case "hash" -> hash = "0".repeat(64);
                case "connect" -> response.failure = new InterruptedIOException("connect timeout");
                case "encoding" -> response.headers.put("Content-Encoding", "gzip");
                case "read" -> response.body = new InputStream() {
                    private int count;
                    @Override public int read() throws IOException {
                        if (++count > 4) throw new InterruptedIOException("read timeout");
                        return 'x';
                    }
                };
            }
            String expectedHash = hash;
            assertThrows(scenario, IOException.class,
                    () -> HttpDownload.save(uri -> response, URL, target, expectedHash, (bytes, total) -> { }));
            assertTrue(scenario, response.disconnected);
            clean(target, "old");
        }
    }

    @Test public void unknownLengthStreamsLargeFilesInBoundedChunks() throws Exception {
        Path target = target();
        Response response = new Response(URL);
        long size = 12 * 1024 * 1024 + 17;
        response.body = new InputStream() {
            long remaining = size;
            @Override public int read() { throw new AssertionError("unbuffered read"); }
            @Override public int read(byte[] bytes, int offset, int length) {
                assertTrue(bytes.length <= 64 * 1024);
                if (remaining == 0) return -1;
                int count = (int) Math.min(remaining, length);
                remaining -= count;
                return count;
            }
        };
        assertEquals(size, HttpDownload.save(uri -> response, URL, target, null,
                (bytes, total) -> assertEquals(-1, total)));
        assertEquals(size, Files.size(target));
        assertTrue(response.disconnected);
    }

    @Test public void emptyResponsePublishesAnEmptyFile() throws Exception {
        Path target = target();
        Response response = new Response(URL).data("");
        response.status = 204;
        assertEquals(0, HttpDownload.save(uri -> response, URL, target, null, (bytes, total) -> { }));
        clean(target, "");
    }

    @Test public void cancellationNeverPublishesAnUnfinishedFile() throws Exception {
        Path target = target();
        Response response = new Response(URL).data("new script");
        try {
            assertThrows(InterruptedIOException.class, () -> HttpDownload.save(uri -> response, URL, target, null,
                    (bytes, total) -> Thread.currentThread().interrupt()));
        } finally { Thread.interrupted(); }
        clean(target, "old");
    }

    @Test public void redirectsResolveRelativeUrlsAndAllowHttpToHttps() throws Exception {
        Path target = target();
        URI start = URI.create("http://download.test/start");
        List<URI> requested = new ArrayList<>();
        List<Response> responses = new ArrayList<>();
        assertEquals(3, HttpDownload.save(uri -> {
            requested.add(uri);
            Response response = new Response(uri).data("new");
            if (requested.size() == 1) { response.status = 301; response.headers.put("Location", "https://cdn.test/dir/one"); }
            if (requested.size() == 2) { response.status = 307; response.headers.put("Location", "../two"); }
            responses.add(response);
            return response;
        }, start, target, null, (bytes, total) -> { }));
        assertEquals(List.of(start, URI.create("https://cdn.test/dir/one"), URI.create("https://cdn.test/two")), requested);
        assertTrue(responses.stream().allMatch(r -> r.disconnected));
        clean(target, "new");
    }

    @Test public void redirectErrorsNeverOpenUnsafeDestinations() throws Exception {
        for (String location : List.of("http://plain.test/file", "file:///etc/passwd", "https://user:pass@host.test/a",
                "https://host.test/a#fragment", "http://[", "")) {
            Path target = target();
            List<Response> calls = new ArrayList<>();
            assertThrows(IOException.class, () -> HttpDownload.save(uri -> {
                Response response = new Response(uri);
                response.status = 302;
                if (!location.isEmpty()) response.headers.put("Location", location);
                calls.add(response);
                return response;
            }, URL, target, null, (bytes, total) -> { }));
            assertEquals(location, 1, calls.size());
            assertTrue(calls.get(0).disconnected);
            clean(target, "old");
        }
    }

    @Test public void redirectLoopsAreBoundedAndDisconnected() throws Exception {
        Path target = target();
        List<Response> calls = new ArrayList<>();
        assertThrows(IOException.class, () -> HttpDownload.save(uri -> {
            Response response = new Response(uri);
            response.status = 308;
            response.headers.put("Location", "/loop");
            calls.add(response);
            return response;
        }, URL, target, null, (bytes, total) -> { }));
        assertEquals(HttpGet.MAX_REQUESTS, calls.size());
        assertTrue(calls.stream().allMatch(r -> r.disconnected));
        clean(target, "old");
    }

    @Test public void destinationProblemsDoNotStartNetworkOrCreateDirectories() throws Exception {
        Path directory = temporary.newFolder().toPath();
        HttpGet.Connections forbidden = uri -> { throw new AssertionError("network should not start"); };
        for (Path path : List.of(directory, directory.resolve("missing/file"))) {
            assertThrows(IOException.class, () -> HttpDownload.save(forbidden, URL, path, null, (bytes, total) -> { }));
        }
        assertFalse(Files.exists(directory.resolve("missing")));
    }
}
