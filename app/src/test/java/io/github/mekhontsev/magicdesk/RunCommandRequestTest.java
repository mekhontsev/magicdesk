package io.github.mekhontsev.magicdesk;

import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;

public class RunCommandRequestTest {
    @Test public void userAndShellRequireDistinctExplicitIdentities() throws Exception {
        for (String backend : new String[]{"app", "shroot"}) {
            var request = shell().put("backend", backend).put("executorUid", 10647);
            if (backend.equals("shroot")) request.put("environmentId", "2e17c23d-f6c7-4c7b-b47a-74127b3a5bab");
            assertEquals(10647, RunCommandRequest.parse(request.toString()).executorUid());
        }
        for (int uid : new int[]{0, 2000, 9999}) {
            var request = shell().put("backend", "app").put("executorUid", uid);
            assertThrows(Exception.class, () -> RunCommandRequest.parse(request.toString()));
        }
    }

    private JSONObject shell() throws Exception {
        return new JSONObject().put("version", 1).put("backend", "shell").put("executorUid", 2000).put("program", "/system/bin/sh");
    }
    @Test public void explicitIdentityAndNoShellInterpolation() throws Exception {
        var json = shell().put("program", "/bin/printf").put("arguments", new JSONArray().put("%s").put("a'\n$(id) ; *"))
                .put("environment", new JSONObject().put("TEST", "a'b\n$HOME"));
        var request = RunCommandRequest.parse(json.toString());
        assertEquals(2000, request.executorUid());
        assertEquals("background", request.mode());
        // Use the host printf executable without changing argument handling.
        var result = PosixShellFixture.run(request.script().replace("'/bin/printf'", "printf"));
        assertEquals(result.stderr, 0, result.exitCode);
        assertEquals("a'\n$(id) ; *", new String(result.stdout, java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void rejectsAmbiguousAndMalformedRequests() throws Exception {
        for (String extra : new String[]{"\"executorUid\":0.5", "\"executorUid\":10000", "\"version\":2", "\"cwd\":\"relative\"",
                "\"program\":\"sh\"", "\"program\":null", "\"arguments\":[1]", "\"environment\":{\"BAD-NAME\":\"v\"}",
                "\"mode\":\"desktop\"", "\"displayId\":1", "\"keyboardDirectory\":\"/usr/share/X11/xkb\"",
                "\"unknown\":true", "\"timeoutMillis\":0", "\"stdin\":\"\\u0000\""}) {
            JSONObject json = shell(); var overlay = new JSONObject("{" + extra + "}");
            for (var keys = overlay.keys(); keys.hasNext();) { String key = keys.next(); json.put(key, overlay.get(key)); }
            assertThrows(extra, Exception.class, () -> RunCommandRequest.parse(json.toString()));
        }
        JSONObject missingUid = shell(); missingUid.remove("executorUid");
        assertThrows(Exception.class, () -> RunCommandRequest.parse(missingUid.toString()));
    }
    @Test public void backendAndPresentationFieldsAreNotSilentlyIgnored() throws Exception {
        JSONObject termux = shell().put("backend", "termux").put("termuxPackage", "com.termux");
        assertThrows(Exception.class, () -> RunCommandRequest.parse(termux.toString()));
        termux.remove("executorUid");
        assertEquals("com.termux", RunCommandRequest.parse(termux.toString()).termuxPackage());
        JSONObject guest = shell().put("backend", "shroot");
        assertThrows(Exception.class, () -> RunCommandRequest.parse(guest.toString()));
        guest.put("environmentId", "2e17c23d-f6c7-4c7b-b47a-74127b3a5bab");
        assertEquals("shroot", RunCommandRequest.parse(guest.toString()).backend());
        var interactive = shell().put("mode", "terminal").put("stdin", "text");
        assertThrows(Exception.class, () -> RunCommandRequest.parse(interactive.toString()));
        interactive.remove("stdin"); interactive.put("displayId", 3);
        assertEquals(3, RunCommandRequest.parse(interactive.toString()).displayId());
    }
    @Test public void requestSizeAndArgumentCountAreBounded() throws Exception {
        String huge = shell().put("stdin", "x".repeat(65536)).toString();
        assertThrows(Exception.class, () -> RunCommandRequest.parse(huge));
        JSONArray args = new JSONArray(); for (int i = 0; i < 257; ++i) args.put("");
        var many = shell().put("arguments", args);
        assertThrows(Exception.class, () -> RunCommandRequest.parse(many.toString()));
    }

    @Test public void outputIsDrainedAfterCaptureLimit() {
        var capture = new RunCommandService.BoundedText();
        byte[] block = "x".repeat(8192).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < 100; ++i) capture.write(block, 0, block.length);
        assertEquals(16384, capture.text().length());
        assertTrue(capture.truncated);
    }

    @Test public void exportedServiceIsGuardedByAnAndroidDangerousPermission() throws Exception {
        var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(java.nio.file.Path.of("src/main/AndroidManifest.xml").toFile());
        var permissions = document.getElementsByTagName("permission");
        boolean declared = false, guarded = false;
        for (int i = 0; i < permissions.getLength(); ++i) {
            var item = (org.w3c.dom.Element) permissions.item(i);
            if (item.getAttribute("android:name").equals("${applicationId}.permission.RUN_COMMAND"))
                declared = item.getAttribute("android:protectionLevel").equals("dangerous");
        }
        var services = document.getElementsByTagName("service");
        for (int i = 0; i < services.getLength(); ++i) {
            var item = (org.w3c.dom.Element) services.item(i);
            if (item.getAttribute("android:name").equals(".RunCommandService")) guarded =
                    item.getAttribute("android:permission").equals("${applicationId}.permission.RUN_COMMAND")
                    && item.getAttribute("android:exported").equals("true");
        }
        assertTrue(declared); assertTrue(guarded);
    }
}
