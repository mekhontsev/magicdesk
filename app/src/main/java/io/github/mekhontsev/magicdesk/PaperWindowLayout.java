package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Session-local, platform-independent horizontal strip. No task or focus ownership. */
final class PaperWindowLayout {
    record Window(int id, int width, boolean suspended) {
        Window(int id, int width) { this(id, width, false); }
    }
    record Bounds(int left, int top, int right, int bottom) {
        int width() { return right - left; }
        int height() { return bottom - top; }
    }

    private final List<Integer> mOrder = new ArrayList<>();
    private final Map<Integer, Integer> mWidths = new LinkedHashMap<>();
    private final Set<Integer> mSuspended = new HashSet<>();
    private final Set<Integer> mMaximized = new HashSet<>();
    private Bounds mArea;
    private int mGap;
    private int mEdge;
    private long mOffset;
    private int mSelected = -1;

    PaperWindowLayout() {}

    PaperWindowLayout(PaperWindowLayout source) {
        mOrder.addAll(source.mOrder);
        mWidths.putAll(source.mWidths);
        mSuspended.addAll(source.mSuspended);
        mMaximized.addAll(source.mMaximized);
        mArea = source.mArea;
        mGap = source.mGap;
        mEdge = source.mEdge;
        mOffset = source.mOffset;
        mSelected = source.mSelected;
    }

    boolean contains(int id) { return mWidths.containsKey(id); }

    boolean update(final List<Window> windows, final Bounds area, final int gap, final int edge,
            final int activeId) {
        if (area.width() <= 0 || area.height() <= 0) return false;
        final int resolvedGap = Math.max(0,
                Math.min(gap, Math.min(area.width(), area.height()) / 8));
        final int resolvedEdge = Math.max(0, Math.min(edge, area.width() / 4));
        boolean changed = !area.equals(mArea) || mGap != resolvedGap || mEdge != resolvedEdge;
        mArea = area;
        mGap = resolvedGap;
        mEdge = resolvedEdge;
        final Set<Integer> live = new HashSet<>();
        for (final Window window : windows) live.add(window.id());
        final Set<Integer> suspended = new HashSet<>();
        for (final Window window : windows) if (window.suspended()) suspended.add(window.id());
        changed |= !mSuspended.equals(suspended);
        mSuspended.clear();
        mSuspended.addAll(suspended);
        changed |= mOrder.removeIf(id -> !live.contains(id));
        mWidths.keySet().retainAll(live);
        mMaximized.retainAll(live);
        int insertion = mOrder.indexOf(activeId);
        if (insertion < 0) insertion = mOrder.indexOf(mSelected);
        if (insertion < 0) insertion = mOrder.size() - 1;
        for (final Window window : windows) {
            if (mWidths.containsKey(window.id())) continue;
            mOrder.add(++insertion, window.id());
            mWidths.put(window.id(), Math.max(1, window.width()));
            if (window.id() == activeId || mSelected < 0) mSelected = window.id();
            changed = true;
        }
        final List<Integer> shown = shownOrder();
        if (shown.contains(activeId)) mSelected = activeId;
        else if (!shown.contains(mSelected)) mSelected = shown.isEmpty() ? -1 : shown.get(0);
        // PaperWM's default focus mode scrolls only enough to reveal the selection.
        // A z-order change never changes column order or preferred widths.
        final long previousOffset = mOffset;
        reveal(mSelected);
        return changed || previousOffset != mOffset;
    }

    int select(final int activeId, final int direction, final boolean move) {
        final List<Integer> shown = shownOrder();
        int index = shown.indexOf(activeId);
        if (index < 0 && activeId < 0) index = shown.indexOf(mSelected);
        if (index < 0) return -1;
        final int next = Math.max(0, Math.min(shown.size() - 1, index + direction));
        if (move) {
            final Integer id = shown.get(index);
            final int destination = mOrder.indexOf(shown.get(next));
            mOrder.remove(id);
            mOrder.add(destination, id);
            mSelected = id;
        } else {
            mSelected = shown.get(next);
        }
        reveal(mSelected);
        return mSelected;
    }

    void cycleWidth(final int taskId) {
        if (!mWidths.containsKey(taskId) || mSuspended.contains(taskId) || mArea == null) return;
        final int current = width(taskId);
        mMaximized.remove(taskId);
        mSelected = taskId;
        final int available = availableWidth();
        final int third = Math.max(1, available / 3);
        final int half = Math.max(1, available / 2);
        final int twoThirds = Math.max(1, (int) (available * 2L / 3));
        final int next = current < third ? third : current < half ? half
                : current < twoThirds ? twoThirds : current < available ? available : third;
        mWidths.put(taskId, next);
        mSelected = taskId;
        reveal(taskId);
    }

    void setMaximized(final int taskId, final boolean maximized) {
        if (!mWidths.containsKey(taskId) || mSuspended.contains(taskId) || mArea == null) return;
        if (maximized) mMaximized.add(taskId);
        else mMaximized.remove(taskId);
        mSelected = taskId;
        reveal(taskId);
    }

    void setWidth(final int taskId, final int width) {
        if (!mWidths.containsKey(taskId) || mSuspended.contains(taskId) || mArea == null) return;
        mWidths.put(taskId, Math.max(1, width));
        mMaximized.remove(taskId);
        reveal(mSelected);
    }

    boolean isMaximized(final int taskId) { return mMaximized.contains(taskId); }

    List<Integer> activationOrder(final int taskId) {
        final List<Integer> shown = shownOrder();
        final int selected = shown.indexOf(taskId);
        if (selected < 0) return List.of(taskId);
        final List<Integer> order = new ArrayList<>(shown);
        // Android clamps overflow tasks to a visible edge strip. Put the nearest
        // column above farther columns so an old focus order cannot cover its preview.
        order.sort((a, b) -> Integer.compare(
                Math.abs(shown.indexOf(b) - selected), Math.abs(shown.indexOf(a) - selected)));
        return order;
    }

    Map<Integer, Bounds> bounds() {
        final Map<Integer, Bounds> result = new LinkedHashMap<>();
        if (mArea == null) return result;
        final boolean fullView = mMaximized.contains(mSelected);
        long x = (long) mArea.left() + viewportEdge() + mGap - mOffset;
        for (final int id : shownOrder()) {
            final int width = width(id);
            // Ordinary overflow keeps Android's edge previews. In full view, tuck
            // peers inside the selected column's bounds so they cannot paint its
            // outer gap. Logical strip positions and preferred widths stay intact.
            final long minimum = (long) mArea.left() + (fullView ? mGap : mEdge - width);
            final long maximum = (long) mArea.right() - (fullView ? mGap + width : mEdge);
            final int left = (int) Math.max(minimum, Math.min(maximum, x));
            result.put(id, new Bounds(left, mArea.top() + mGap,
                    left + width, mArea.bottom() - mGap));
            x += (long) width + mGap;
        }
        return result;
    }

    private List<Integer> shownOrder() {
        return mOrder.stream().filter(id -> !mSuspended.contains(id)).toList();
    }

    private int viewportEdge() { return mMaximized.contains(mSelected) ? 0 : mEdge; }
    private int availableWidth() { return Math.max(1, mArea.width() - 2 * (mGap + viewportEdge())); }
    private int width(final int id) {
        return mMaximized.contains(id) ? Math.max(1, mArea.width() - 2 * mGap)
                : Math.min(mWidths.get(id), Math.max(1, mArea.width() - 2 * (mGap + mEdge)));
    }

    private void reveal(final int id) {
        if (mArea == null) return;
        long x = 0;
        for (final int candidate : shownOrder()) {
            final int width = width(candidate);
            if (candidate == id) {
                if (x < mOffset) mOffset = x;
                else if (x + width > mOffset + availableWidth()) {
                    mOffset = x + width - availableWidth();
                }
            }
            x += (long) width + mGap;
        }
        mOffset = Math.max(0, Math.min(mOffset,
                Math.max(0, x - mGap - availableWidth())));
    }

    boolean requiresReveal(final int id) {
        if (mArea == null || id == mSelected || mSuspended.contains(id)) return false;
        final Bounds frame = bounds().get(id);
        if (frame == null) return false;
        return mMaximized.contains(mSelected) || mMaximized.contains(id)
                || frame.left() < mArea.left() + mGap + mEdge
                || frame.right() > mArea.right() - mGap - mEdge;
    }

    boolean canSelect(final int direction) {
        final var shown = shownOrder();
        final int index = shown.indexOf(mSelected);
        return index >= 0 && index + direction >= 0 && index + direction < shown.size();
    }

    int selectedId() { return mSelected; }

    void clear() {
        mOrder.clear();
        mWidths.clear();
        mSuspended.clear();
        mMaximized.clear();
        mArea = null;
        mSelected = -1;
        mOffset = 0;
    }
}
