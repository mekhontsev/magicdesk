package io.github.mekhontsev.magicdesk;

import io.github.mekhontsev.magicdesk.hosted.HostedTextState;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.function.Predicate;

/** UI-thread edit stream for one guest field, shared by its Android connections. */
final class HostedEditorEdits {
    record Text(String value, int anchor, int cursor) {
        int start() { return Math.min(anchor, cursor); }
        int end() { return Math.max(anchor, cursor); }
        Text replace(int left, int right, String replacement) {
            if ((long)value.length() - (right - left) + replacement.length() > MAX_CONTEXT) return null;
            int caret = left + replacement.length();
            return new Text(value.substring(0, left) + replacement + value.substring(right), caret, caret);
        }
        Text delete(HostedTextState.Range range) {
            int start = start(), end = end();
            int left = range.left(), right = range.right();
            return new Text(value.substring(0, left) + value.substring(start, end) + value.substring(right),
                    anchor - (start - left), cursor - (start - left));
        }
    }

    private static final int MAX_CONTEXT = 4096, MAX_WAITING = 128;
    private record Edit(boolean exact, Text after, Predicate<HostedTextState> send) {}
    // A range edit needs a confirmed base. Until the next one, the only possible
    // changes are successive insertions at the caret, with one unchanged suffix.
    private Text first, last;
    private final BitSet stops = new BitSet();
    private final ArrayDeque<Edit> waiting = new ArrayDeque<>();
    private HostedTextState observed;
    private Text actual;
    private boolean opaque;
    private boolean corrected;

    Text text() {
        if (!waiting.isEmpty()) return waiting.getLast().after;
        return last == null ? (opaque ? null : actual) : last;
    }
    boolean pending() { return opaque || last != null || !waiting.isEmpty(); }

    private boolean acknowledge(Text seen) {
        if (seen == null || last == null) return false;
        if (last.equals(seen)) { forgetSent(); return true; }
        if (seen.equals(first)) { first = null; return true; }
        int pos = seen.cursor;
        if (pos != seen.anchor || !stops.get(pos) || pos > last.cursor
                || seen.value.length() - pos != last.value.length() - last.cursor
                || !seen.value.regionMatches(0, last.value, 0, pos)
                || !seen.value.regionMatches(pos, last.value, last.cursor, seen.value.length() - pos)) return false;
        first = null;
        stops.clear(0, pos + 1);
        return true;
    }

    /** False means a queued relative edit lost its base; retire this input connection. */
    boolean observe(HostedTextState state) {
        if (observed == state || (observed != null && state != null && observed.revision() == state.revision()))
            return true;
        observed = state;
        actual = state == null || state.privateText() || state.surrounding() == null ? null
                : new Text(state.surrounding(), state.anchor(), state.cursor());
        opaque = false;
        if (last == null && waiting.isEmpty()) return true;
        if (state == null || !state.inputMethodChange() || !acknowledge(actual)) {
            boolean safe = waiting.isEmpty();
            clear();
            corrected = true;
            return safe;
        }
        while (!waiting.isEmpty() && ready(waiting.getFirst())) {
            if (!dispatch(waiting.removeFirst())) { clear(); return false; }
        }
        return true;
    }

    boolean send(Text after, Predicate<HostedTextState> send) { return submit(new Edit(false, after, send)); }
    boolean sendAtConfirmedCaret(Text after, Predicate<HostedTextState> send) { return submit(new Edit(true, after, send)); }
    private boolean submit(Edit edit) {
        if (waiting.isEmpty() && ready(edit)) return dispatch(edit);
        if (opaque || waiting.size() >= MAX_WAITING) return false;
        waiting.addLast(edit);
        return true;
    }
    private boolean ready(Edit e) {
        if (e.exact) return !opaque && last == null;
        // A delete followed by retyping the same character can coalesce to no guest
        // revision at all. Let the deletion become observable before restoring it.
        return last == null || actual == null || !actual.equals(e.after);
    }
    private boolean dispatch(Edit e) {
        Text previous = last == null ? actual : last;
        if (!e.send.test(observed)) return false;
        if (e.after == null) {
            forgetSent();
            opaque = actual != null;
        } else if (!e.after.equals(previous)) {
            if (last == null) first = e.after;
            else stops.set(e.after.cursor);
            last = e.after;
        }
        return true;
    }
    private void forgetSent() { first = last = null; stops.clear(); }
    boolean takeCorrection() { boolean result = corrected; corrected = false; return result; }
    void clear() { forgetSent(); waiting.clear(); opaque = false; }
}
