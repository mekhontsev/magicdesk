package io.github.mekhontsev.magicdesk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded response from a MagicDesk-owned native input relay. */
final class NativeInputBridgeStats {
    private static final Pattern REQUEST = Pattern.compile(
            "(?:^|\\s)request=(\\d+)(?:\\s|$)");

    final long requestId;
    final String detail;

    private NativeInputBridgeStats(
            final long requestId,
            final String detail) {
        this.requestId = requestId;
        this.detail = detail;
    }

    static NativeInputBridgeStats parse(
            final String line,
            final String prefix) {
        if (line == null || prefix == null || !line.startsWith(prefix)) {
            return null;
        }
        final String payload = line.substring(prefix.length()).trim();
        final Matcher request = REQUEST.matcher(payload);
        if (!request.find()) {
            return null;
        }
        final long requestId;
        try {
            requestId = Long.parseLong(request.group(1));
        } catch (NumberFormatException error) {
            return null;
        }
        final String before = payload.substring(
                0, request.start()).trim();
        final String after = payload.substring(request.end()).trim();
        final String detail = before.isEmpty()
                ? after : after.isEmpty() ? before : before + ' ' + after;
        return new NativeInputBridgeStats(requestId, normalize(detail));
    }

    private static String normalize(final String value) {
        final String normalized = value == null
                ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 1_000
                ? normalized : normalized.substring(0, 1_000);
    }
}
