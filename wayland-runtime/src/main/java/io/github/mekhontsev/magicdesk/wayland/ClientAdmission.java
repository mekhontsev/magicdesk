package io.github.mekhontsev.magicdesk.wayland;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

final class ClientAdmission {
    final String token;
    private final int uid;
    private final byte[] expected;
    private final AtomicBoolean consumed = new AtomicBoolean();

    ClientAdmission(int uid) {
        if (uid < 0) throw new IllegalArgumentException("Invalid client UID");
        this.uid = uid;
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        token = HexFormat.of().formatHex(nonce);
        expected = token.getBytes(StandardCharsets.US_ASCII);
    }

    boolean accept(int peerUid, byte[] nonce) {
        return peerUid == uid && nonce != null && MessageDigest.isEqual(expected, nonce)
                && consumed.compareAndSet(false, true);
    }
}