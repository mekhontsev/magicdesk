package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public class GuestOciRegistryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final class Response extends HttpURLConnection {
        int status = 200;
        byte[] body;
        boolean interrupted;
        Map<String, String> headers = new HashMap<>();
        Response(URI uri, byte[] body) throws Exception { super(uri.toURL()); this.body = body; }
        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String name) { return headers.get(name); }
        @Override public java.io.InputStream getInputStream() {
            if (!interrupted) return new ByteArrayInputStream(body);
            return new java.io.InputStream() {
                int offset;
                @Override public int read() throws IOException {
                    if (offset == body.length / 2) throw new java.io.InterruptedIOException("fixture download interrupted");
                    return body[offset++] & 255;
                }
            };
        }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static JSONObject descriptor(byte[] data, String media) throws Exception {
        return new JSONObject().put("mediaType", media).put("size", data.length)
                .put("digest", "sha256:" + HexFormat.of().formatHex(GuestImageFiles.sha256().digest(data)));
    }

    @Test public void referencesRetainRegistryPortsAndDigestWithoutShellParsing() throws Exception {
        assertEquals(new GuestOciReference("registry-1.docker.io", "library/debian", "trixie"), GuestOciReference.parse("debian:trixie"));
        assertEquals("latest", GuestOciReference.parse("ghcr.io/team/image").reference());
        assertEquals("host.test:8443", GuestOciReference.parse("host.test:8443/repo:v1").registry());
        assertEquals("sha256:" + "a".repeat(64), GuestOciReference.parse("alpine@sha256:" + "a".repeat(64)).reference());
        for (String value : List.of("../foo", "a//b", "https://host/repo", "a:bad tag", "a@sha256:no", "a;id", "a@sha256:" + "0".repeat(64) + "/x"))
            assertThrows(IOException.class, () -> GuestOciReference.parse(value));
    }

    @Test public void bearerChallengeIsStructuredAndRejectsAmbiguity() throws Exception {
        assertEquals("https://auth.test/token", GuestOciRegistry.bearer("Bearer realm=\"https://auth.test/token\",service=\"r\"").get("realm"));
        for (String value : List.of("Basic realm=\"x\"", "Bearer realm=\"a\",realm=\"b\"", "Bearer realm=\"a\"junk"))
            assertThrows(IOException.class, () -> GuestOciRegistry.bearer(value));
    }

    @Test public void pullsArm64WithAuthenticationDigestChecksAndCredentialFreeCdnRedirect() throws Exception {
        Path root = temporary.newFolder().toPath();
        byte[] config = bytes("{\"os\":\"linux\",\"architecture\":\"arm64\",\"rootfs\":{\"type\":\"layers\",\"diff_ids\":[]}}");
        byte[] layer = bytes("layer payload");
        JSONObject cd = descriptor(config, "application/vnd.oci.image.config.v1+json");
        JSONObject ld = descriptor(layer, "application/vnd.oci.image.layer.v1.tar");
        byte[] manifest = bytes(new JSONObject().put("schemaVersion", 2).put("mediaType", "application/vnd.oci.image.manifest.v1+json")
                .put("config", cd).put("layers", new JSONArray().put(ld)).toString());
        JSONObject md = descriptor(manifest, "application/vnd.oci.image.manifest.v1+json");
        md.put("platform", new JSONObject().put("os", "linux").put("architecture", "arm64"));
        byte[] index = bytes(new JSONObject().put("schemaVersion", 2).put("manifests", new JSONArray().put(md)).toString());
        List<Response> calls = new ArrayList<>();
        int[] attempts = {0};
        GuestOciRegistry registry = new GuestOciRegistry(uri -> {
            try {
                String path = uri.getPath();
                Response response = new Response(uri, new byte[0]);
                if (uri.getHost().equals("auth.test")) response.body = bytes("{\"token\":\"test-token\"}");
                else if (uri.getHost().equals("cdn.test")) response.body = layer;
                else if (path.endsWith("/latest")) {
                    if (attempts[0]++ == 0) {
                        response.status = 401;
                        response.headers.put("WWW-Authenticate", "Bearer realm=\"https://auth.test/token\",service=\"test\"");
                    } else response.body = index;
                } else if (path.endsWith(md.getString("digest"))) response.body = manifest;
                else if (path.endsWith(cd.getString("digest"))) response.body = config;
                else if (path.endsWith(ld.getString("digest"))) {
                    response.status = 307; response.headers.put("Location", "https://cdn.test/payload");
                } else throw new AssertionError(uri);
                calls.add(response); return response;
            } catch (Exception error) { throw new IOException(error); }
        }, message -> { }, GuestImageFilesTest::assertDirectorySync);
        var pulled = registry.pull("registry.test/repo", root.resolve("cache"), root.resolve("layout"));
        assertEquals(md.getString("digest"), pulled.digest());
        for (Response call : calls) {
            if (!call.getURL().getHost().equals("registry.test")) assertNull(call.getRequestProperty("Authorization"));
        }
        assertEquals("Bearer test-token", calls.get(2).getRequestProperty("Authorization"));
        assertArrayEquals(layer, Files.readAllBytes(root.resolve("cache").resolve(GuestImageFiles.hex(ld.getString("digest")))));
        Files.writeString(root.resolve("cache").resolve(GuestImageFiles.hex(ld.getString("digest"))), "broken");
        assertThrows(IOException.class, () -> registry.pull("registry.test/repo", root.resolve("cache"), root.resolve("second")));
    }

    @Test public void redirectsCannotDowngradeTls() throws Exception {
        Path root = temporary.newFolder().toPath();
        var registry = new GuestOciRegistry(uri -> {
            try {
                var response = new Response(uri, new byte[0]); response.status = 302;
                response.headers.put("Location", "http://insecure.test/image"); return response;
            } catch (Exception error) { throw new IOException(error); }
        }, message -> { }, GuestImageFilesTest::assertDirectorySync);
        assertThrows(IOException.class, () -> registry.pull("alpine", root.resolve("cache"), root.resolve("layout")));
    }

    @Test public void interruptedDownloadKeepsVerifiedCacheAndRetryPublishesOnlyCompleteBlob() throws Exception {
        Path root = temporary.newFolder().toPath();
        byte[] config = bytes("{\"os\":\"linux\",\"architecture\":\"arm64\",\"rootfs\":{\"type\":\"layers\",\"diff_ids\":[]}}");
        byte[] layer = bytes("fixture layer to interrupt");
        var cd = descriptor(config, "application/vnd.oci.image.config.v1+json");
        var ld = descriptor(layer, "application/vnd.oci.image.layer.v1.tar");
        byte[] manifest = bytes(new JSONObject().put("schemaVersion", 2).put("mediaType", "application/vnd.oci.image.manifest.v1+json")
                .put("config", cd).put("layers", new JSONArray().put(ld)).toString());
        boolean[] interrupt = {true};
        var registry = new GuestOciRegistry(uri -> {
            try {
                var response = new Response(uri, manifest);
                if (uri.getPath().endsWith(cd.getString("digest"))) response.body = config;
                if (uri.getPath().endsWith(ld.getString("digest"))) { response.body = layer; response.interrupted = interrupt[0]; }
                return response;
            } catch (Exception failure) { throw new IOException(failure); }
        }, message -> { }, GuestImageFilesTest::assertDirectorySync);
        var cache = root.resolve("cache");
        assertThrows(IOException.class, () -> registry.pull("registry.test/repo", cache, root.resolve("broken")));
        assertFalse(Files.exists(cache.resolve(GuestImageFiles.hex(ld.getString("digest")))));
        assertArrayEquals(config, Files.readAllBytes(cache.resolve(GuestImageFiles.hex(cd.getString("digest")))));
        try (var files = Files.list(cache)) { assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".download-"))); }
        interrupt[0] = false;
        registry.pull("registry.test/repo", cache, root.resolve("retried"));
        assertArrayEquals(layer, Files.readAllBytes(cache.resolve(GuestImageFiles.hex(ld.getString("digest")))));
    }
}
