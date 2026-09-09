package io.github.mekhontsev.magicdesk;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.*;

public final class AppUpdateReceiptTest {
    private JSONObject result(String state) throws Exception {
        return new JSONObject().put("updateId", "0123456789abcdef")
                .put("sessionId", 17).put("state", state).put("detail", "installer result");
    }

    private byte[] encode(JSONObject result) throws Exception {
        final var out = new ByteArrayOutputStream();
        AppUpdateReceipt.write(out, result);
        return out.toByteArray();
    }

    @Test public void preservesResultAcrossProcessReplacement() throws Exception {
        final JSONObject completed = result("installed").put("installerStatus", 0);
        final JSONObject receipt = result("submitted").put("versionCode", 193).put("sha256", "expected");
        AppUpdateReceipt.merge(receipt, AppUpdateReceipt.read(new ByteArrayInputStream(encode(completed))));
        assertEquals("installed", receipt.getString("state"));
        assertEquals(193, receipt.getInt("versionCode"));
        assertEquals("expected", receipt.getString("sha256"));
        assertEquals(0, receipt.getInt("installerStatus"));
    }

    @Test public void everyIncompletePrefixIsStillPending() throws Exception {
        final byte[] bytes = encode(result("installed"));
        for (int size = 0; size < bytes.length; size++) {
            assertNull("prefix=" + size, AppUpdateReceipt.read(
                    new ByteArrayInputStream(Arrays.copyOf(bytes, size))));
        }
    }

    @Test public void detectsCorruption() throws Exception {
        final byte[] bytes = encode(result("installed"));
        bytes[8] ^= 1;
        assertThrows(IOException.class, () -> AppUpdateReceipt.read(new ByteArrayInputStream(bytes)));
    }

    @Test public void rejectsUnboundedReceipts() throws Exception {
        assertThrows(IOException.class, () -> encode(result("failed").put("detail", "x".repeat(9000))));
        assertThrows(IOException.class, () -> AppUpdateReceipt.read(
                new ByteArrayInputStream(new byte[] {127, -1, -1, -1})));
    }

    @Test public void rejectsMismatchedOperationAndSession() throws Exception {
        assertThrows(IOException.class, () -> AppUpdateReceipt.merge(result("submitted"),
                result("installed").put("updateId", "different-operation")));
        assertThrows(IOException.class, () -> AppUpdateReceipt.merge(result("submitted"),
                result("installed").put("sessionId", 18)));
    }

    @Test public void preservesInstallerFailureAndUncertainty() throws Exception {
        for (String state : new String[] {"failed", "user_action_required", "completion_unknown"}) {
            final JSONObject receipt = result("submitted");
            AppUpdateReceipt.merge(receipt, AppUpdateReceipt.read(new ByteArrayInputStream(encode(result(state)))));
            assertEquals(state, receipt.getString("state"));
            assertFalse(receipt.has("installerStatus"));
        }
        assertThrows(IOException.class, () -> AppUpdateReceipt.merge(result("submitted"), result("made-up")));
    }
}
