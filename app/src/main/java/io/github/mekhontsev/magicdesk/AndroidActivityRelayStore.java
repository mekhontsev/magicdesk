package io.github.mekhontsev.magicdesk;

import android.content.Intent;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded idempotent store for one-shot Activity intents kept in app process. */
final class AndroidActivityRelayStore {
    private static final int MAX_REQUESTS = 64;
    private static final Map<String, Entry> REQUESTS =
            new LinkedHashMap<>();

    private AndroidActivityRelayStore() {
    }

    static synchronized String put(
            final Intent target,
            final boolean chooser,
            final String chooserTitle) {
        if (target == null) {
            throw new IllegalArgumentException("missing Activity relay target");
        }
        final String id = UUID.randomUUID().toString();
        REQUESTS.put(id, new Entry(
                new Request(target, chooser, chooserTitle)));
        while (REQUESTS.size() > MAX_REQUESTS) {
            final Iterator<String> oldest = REQUESTS.keySet().iterator();
            if (!oldest.hasNext()) {
                break;
            }
            oldest.next();
            oldest.remove();
        }
        return id;
    }

    static synchronized Claim claim(final String id) {
        if (id == null) {
            return Claim.missing();
        }
        final Entry entry = REQUESTS.get(id);
        if (entry == null) {
            return Claim.missing();
        }
        if (entry.request == null) {
            return Claim.alreadyClaimed();
        }
        final Request request = entry.request;
        // Task handoff can create the relay Activity more than once. Keep only
        // the claimed token so a duplicate cannot launch the nested Intent.
        entry.request = null;
        return Claim.ready(request);
    }

    static synchronized void discard(final String id) {
        if (id != null) {
            REQUESTS.remove(id);
        }
    }

    static final class Request {
        final Intent target;
        final boolean chooser;
        final String chooserTitle;

        Request(
                final Intent target,
                final boolean chooser,
                final String chooserTitle) {
            this.target = new Intent(target);
            this.chooser = chooser;
            this.chooserTitle = chooserTitle == null ? "" : chooserTitle;
        }
    }

    static final class Claim {
        final Request request;
        final boolean alreadyClaimed;

        private Claim(
                final Request request,
                final boolean alreadyClaimed) {
            this.request = request;
            this.alreadyClaimed = alreadyClaimed;
        }

        static Claim ready(final Request request) {
            return new Claim(request, false);
        }

        static Claim alreadyClaimed() {
            return new Claim(null, true);
        }

        static Claim missing() {
            return new Claim(null, false);
        }
    }

    private static final class Entry {
        Request request;

        Entry(final Request request) {
            this.request = request;
        }
    }
}
