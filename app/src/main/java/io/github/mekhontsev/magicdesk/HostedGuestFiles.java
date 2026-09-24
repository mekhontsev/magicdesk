package io.github.mekhontsev.magicdesk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Authenticated access to files as the selected guest user, independent of graphics protocol. */
final class HostedGuestFiles {
    private final String helper;
    private final Map<String, String> environment;

    HostedGuestFiles(String nativeDirectory, boolean enabled) {
        helper = nativeDirectory + "/libmagicdesk_guest_files.so";
        if (!enabled) { environment = Map.of(); return; }
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("MAGICDESK_GUEST_FILES_SOCKET", "magicdesk-files-" + UUID.randomUUID());
        values.put("MAGICDESK_GUEST_FILES_TOKEN", java.util.HexFormat.of().formatHex(secret));
        environment = java.util.Collections.unmodifiableMap(values);
    }
    void configure(Map<String, String> target) { target.putAll(environment); }
    String exports() {
        if (environment.isEmpty()) return "";
        StringBuilder result = new StringBuilder(" MAGICDESK_GUEST_FILES_HELPER=").append(ShellCommandLine.quote(helper));
        environment.forEach((key, value) -> result.append(' ').append(key).append('=').append(ShellCommandLine.quote(value)));
        return result.toString();
    }
}
