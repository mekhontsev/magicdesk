package io.github.mekhontsev.magicdesk.wayland;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ClientAdmissionTest {
    @Test public void admissionRequiresUidAndExactNonceAndCannotBeReplayed() {
        ClientAdmission admission = new ClientAdmission(2000);
        byte[] nonce = admission.token.getBytes(StandardCharsets.US_ASCII);
        assertEquals(64, nonce.length);
        assertFalse(admission.accept(0, nonce));
        assertFalse(admission.accept(2000, null));
        assertFalse(admission.accept(2000, new byte[64]));
        assertFalse(admission.accept(2000, new byte[63]));
        assertTrue(admission.accept(2000, nonce));
        assertFalse(admission.accept(2000, nonce));
    }

    @Test public void channelsUseIndependentSecrets() {
        ClientAdmission first = new ClientAdmission(2000);
        ClientAdmission second = new ClientAdmission(2000);
        assertNotEquals(first.token, second.token);
        assertFalse(second.accept(2000, first.token.getBytes(StandardCharsets.US_ASCII)));
        assertTrue(second.accept(2000, second.token.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownIdentity() { new ClientAdmission(-1); }
}