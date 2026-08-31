package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.util.Patterns;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;

/** Extracts one safe web shortcut from Android's generic share contract. */
final class WebShortcutShareRequest {
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_SHARED_TEXT_LENGTH = 32 * 1024;
    private static final int MAX_CLIP_ITEMS = 16;

    final String name;
    final String url;

    private WebShortcutShareRequest(final String name, final String url) {
        this.name = name;
        this.url = url;
    }

    static WebShortcutShareRequest from(final Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            return null;
        }
        final AndroidContentPayload content =
                AndroidContentPayload.fromSendIntent(intent);
        String url = findHttpUrl(content.text);
        if (url == null) {
            final int count = Math.min(
                    content.uriItems.size(), MAX_CLIP_ITEMS);
            for (int index = 0; index < count; index++) {
                url = normalizeCandidate(
                        content.uriItems.get(index).uri.toString());
                if (url != null) {
                    break;
                }
            }
        }
        if (url == null) {
            return null;
        }
        return new WebShortcutShareRequest(
                normalizeName(content.subject, url), url);
    }

    static String findHttpUrl(final CharSequence text) {
        if (text == null) {
            return null;
        }
        final String value = text.toString().trim();
        if (value.length() > MAX_SHARED_TEXT_LENGTH) {
            return null;
        }
        final String exact = normalizeCandidate(value);
        if (exact != null) {
            return exact;
        }
        final Matcher matcher = Patterns.WEB_URL.matcher(value);
        while (matcher.find()) {
            final String candidate = matcher.group();
            final String normalized = normalizeCandidate(
                    candidate.contains("://")
                            ? candidate : "https://" + candidate);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static String normalizeCandidate(final String candidate) {
        if (candidate == null) {
            return null;
        }
        try {
            return DesktopWebShortcut.normalizeUrl(candidate);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String normalizeName(
            final CharSequence title, final String url) {
        String name = title == null ? "" : title.toString();
        if (name.length() > MAX_SHARED_TEXT_LENGTH) {
            name = name.substring(0, MAX_SHARED_TEXT_LENGTH);
        }
        name = name.replaceAll("\\s+", " ").trim();
        if (name.isEmpty()) {
            try {
                final String host = new URI(url).getHost();
                name = host == null ? url : IDN.toUnicode(host);
            } catch (URISyntaxException | RuntimeException error) {
                name = url;
            }
        }
        return name.length() <= MAX_NAME_LENGTH
                ? name : name.substring(0, MAX_NAME_LENGTH).trim();
    }
}
