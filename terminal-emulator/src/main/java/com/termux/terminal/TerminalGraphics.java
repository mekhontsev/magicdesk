package com.termux.terminal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;

/** Session-owned raster quota and buffer-owned placements, independent of attached Android views. */
public final class TerminalGraphics {
    static final int MAX_IMAGES = 128;
    static final int MAX_PLACEMENTS = 256;
    private final LinkedHashMap<Long, TerminalImage> images = new LinkedHashMap<>();
    private final ArrayList<Placement> placements = new ArrayList<>();
    private long nextId = 0x1_0000_0000L;
    private int pixels;
    private int pixelBudget = 16 * 1024 * 1024;

    public void setByteBudget(long bytes) {
        pixelBudget = (int) Math.max(TerminalImage.MAX_PIXELS, Math.min(32 * 1024 * 1024, bytes / 4));
        while (pixels > pixelBudget) evict();
    }

    public static final class Placement {
        public final TerminalImage image;
        public final long imageId;
        public final int placementId, z;
        public final boolean sixel;
        public boolean virtual;
        final TerminalBuffer buffer;
        public float column, row, columns, rows;
        public final int sourceX, sourceY, sourceWidth, sourceHeight;
        // Cell-relative clip survives scrolling past the viewport and partial scroll margins.
        public float clipTop, clipBottom, clipLeft, clipRight;
        boolean reflowed;

        Placement(TerminalBuffer buffer, TerminalImage image, long imageId, int placementId,
                float column, float row, float columns, float rows, int x, int y, int w, int h,
                int z, boolean sixel) {
            this.buffer = buffer; this.image = image; this.imageId = imageId;
            this.placementId = placementId; this.column = column; this.row = row;
            this.columns = columns; this.rows = rows; this.sourceX = x; this.sourceY = y;
            this.sourceWidth = w; this.sourceHeight = h; this.z = z; this.sixel = sixel;
            clipBottom = rows;
            clipRight = columns;
        }
    }

    long put(long id, TerminalImage image) {
        if (id == 0) id = nextId++;
        remove(id);
        while (!images.isEmpty() && (pixels + image.width * image.height > pixelBudget
                || images.size() >= MAX_IMAGES)) evict();
        images.put(id, image);
        pixels += image.width * image.height;
        return id;
    }

    TerminalImage image(long id) { return images.get(id); }
    public int imageCount() { return images.size(); }
    public int pixelCount() { return pixels; }

    void remove(long id) {
        placements.removeIf(p -> p.imageId == id);
        TerminalImage old = images.remove(id);
        if (old != null) pixels -= old.width * old.height;
    }

    private boolean isPlaced(long id) {
        for (Placement p : placements) if (p.imageId == id) return true;
        return false;
    }

    private void evict() {
        for (long id : images.keySet()) if (!isPlaced(id)) { remove(id); return; }
        remove(images.keySet().iterator().next());
    }

    Placement place(TerminalBuffer buffer, long id, int placementId, float column, float row,
            float columns, float rows, int x, int y, int w, int h, int z, boolean sixel) {
        TerminalImage image = images.get(id);
        if (image == null) throw new IllegalArgumentException("ENOENT: image");
        if (placementId != 0) placements.removeIf(p -> p.buffer == buffer
                && p.imageId == id && p.placementId == placementId);
        if (placements.size() >= MAX_PLACEMENTS) placements.remove(0);
        Placement result = new Placement(buffer, image, id, placementId, column, row,
                columns, rows, x, y, w, h, z, sixel);
        placements.add(result);
        return result;
    }

    public List<Placement> visible(TerminalBuffer buffer, int top, int bottom) {
        if (placements.isEmpty()) return List.of();
        ArrayList<Placement> result = new ArrayList<>();
        for (Placement p : placements) if (!p.virtual && p.buffer == buffer && p.row + p.clipBottom > top
                && p.row + p.clipTop < bottom) result.add(p);
        result.sort(Comparator.comparingInt((Placement p) -> p.z).thenComparingLong(p -> p.imageId));
        return result;
    }

    public Placement virtualPlacement(TerminalBuffer buffer, long id, int placementId) {
        for (Placement p : placements) if (p.virtual && p.buffer == buffer && p.imageId == id
                && (placementId == 0 || p.placementId == placementId)) return p;
        return null;
    }

    void eraseSixel(TerminalBuffer buffer, int left, int top, int right, int bottom) {
        ArrayList<Placement> pieces = null;
        for (int i = placements.size() - 1; i >= 0; i--) {
            Placement p = placements.get(i);
            if (p.buffer != buffer || !p.sixel) continue;
            float l = Math.max(p.clipLeft, left - p.column), r = Math.min(p.clipRight, right - p.column);
            float t = Math.max(p.clipTop, top - p.row), b = Math.min(p.clipBottom, bottom - p.row);
            if (l >= r || t >= b) continue;
            placements.remove(i);
            if (pieces == null) pieces = new ArrayList<>();
            fragment(pieces, p, p.clipLeft, p.clipTop, p.clipRight, t);
            fragment(pieces, p, p.clipLeft, b, p.clipRight, p.clipBottom);
            fragment(pieces, p, p.clipLeft, t, l, b);
            fragment(pieces, p, r, t, p.clipRight, b);
        }
        if (pieces != null) {
            placements.addAll(pieces);
            while (placements.size() > MAX_PLACEMENTS) placements.remove(0);
            collectAnonymous();
        }
    }

    private static void fragment(List<Placement> result, Placement p, float l, float t, float r, float b) {
        if (l >= r || t >= b) return;
        Placement copy = new Placement(p.buffer, p.image, p.imageId, p.placementId,
                p.column, p.row, p.columns, p.rows, p.sourceX, p.sourceY, p.sourceWidth, p.sourceHeight, p.z, p.sixel);
        copy.clipLeft = l; copy.clipTop = t; copy.clipRight = r; copy.clipBottom = b;
        result.add(copy);
    }

    void delete(TerminalBuffer buffer, char what, long id, int placementId) {
        char kind = Character.toLowerCase(what);
        if (kind != 'a' && kind != 'i') throw new IllegalArgumentException("ENOTSUP: deletion selector");
        removePlacements(p -> p.buffer == buffer && (kind == 'a'
                ? !p.virtual && p.row + p.clipBottom > 0 && p.row + p.clipTop < buffer.mScreenRows
                : p.imageId == id && (placementId == 0 || placementId == p.placementId)), Character.isUpperCase(what));
        if (what == 'I' && !isPlaced(id)) remove(id);
    }

    void clear(TerminalBuffer buffer, boolean history) {
        removePlacements(p -> p.buffer == buffer && (history || !p.virtual && p.row + p.clipBottom > 0), true);
    }

    void clearHistory(TerminalBuffer buffer) {
        removePlacements(p -> !p.virtual && p.buffer == buffer && p.row + p.clipBottom <= 0, true);
        for (Placement p : placements) if (!p.virtual && p.buffer == buffer) p.clipTop = Math.max(p.clipTop, -p.row);
    }

    void scroll(TerminalBuffer buffer, int top, int bottom, int delta, boolean history, boolean reflowing) {
        scroll(buffer, 0, buffer.mColumns, top, bottom, delta, history, reflowing);
    }

    void scrollRegion(TerminalBuffer buffer, int left, int right, int top, int bottom, int delta) {
        scroll(buffer, left, right, top, bottom, delta, false, false);
    }

    private void scroll(TerminalBuffer buffer, int left, int right, int top, int bottom,
            int delta, boolean history, boolean reflowing) {
        for (Placement p : placements) {
            if (p.virtual || p.buffer != buffer || reflowing && !p.reflowed) continue;
            if (!history && (p.column + p.clipLeft < left || p.column + p.clipRight > right)) continue;
            if (history && p.row + p.clipTop < bottom || p.row + p.clipTop >= top
                    && p.row + p.clipBottom <= bottom) {
                p.row += delta;
                if (!history) {
                    p.clipTop = Math.max(p.clipTop, top - p.row);
                    p.clipBottom = Math.min(p.clipBottom, bottom - p.row);
                }
            }
        }
        placements.removeIf(p -> !p.virtual && p.buffer == buffer && (!reflowing || p.reflowed) && (p.clipTop >= p.clipBottom
                || p.row + p.clipBottom <= -(buffer.mTotalRows - buffer.mScreenRows)));
        collectAnonymous();
    }

    boolean hasContentAtRow(TerminalBuffer buffer, int row) {
        for (Placement p : placements) if (!p.virtual && p.buffer == buffer
                && p.row + p.clipTop < row + 1 && p.row + p.clipBottom > row) return true;
        return false;
    }

    // Reflow translates each placement's origin once, using the same old/new cell mapping as text.
    void beginReflow(TerminalBuffer buffer) {
        for (Placement p : placements) if (p.buffer == buffer) p.reflowed = false;
    }

    int lastColumn(TerminalBuffer buffer, int row) {
        int end = 0;
        for (Placement p : placements) if (!p.virtual && p.buffer == buffer && !p.reflowed && (int) p.row == row)
            end = Math.max(end, (int) p.column + 1);
        return end;
    }

    void reflow(TerminalBuffer buffer, int oldRow, int oldColumn, int row, int column) {
        for (Placement p : placements) if (!p.virtual && p.buffer == buffer && !p.reflowed
                && (int) p.row == oldRow && (int) p.column == oldColumn) {
            p.row = row + p.row - oldRow;
            p.column = column + p.column - oldColumn;
            p.reflowed = true;
        }
    }

    void shift(TerminalBuffer buffer, int delta) {
        for (Placement p : placements) if (!p.virtual && p.buffer == buffer) p.row += delta;
    }

    public void reset() { placements.clear(); images.clear(); pixels = 0; }

    private void collectAnonymous() {
        if (images.isEmpty()) return;
        ArrayList<Long> unused = new ArrayList<>();
        for (long id : images.keySet()) if (id >= 0x1_0000_0000L
                && !isPlaced(id)) unused.add(id);
        for (long id : unused) remove(id);
    }

    private void removePlacements(Predicate<Placement> matches, boolean freeData) {
        if (placements.isEmpty()) return;
        HashSet<Long> removed = freeData ? new HashSet<>() : null;
        placements.removeIf(p -> {
            if (!matches.test(p)) return false;
            if (removed != null) removed.add(p.imageId);
            return true;
        });
        // A delete or buffer clear must not discard unrelated, preloaded Kitty images.
        if (removed != null) for (long id : removed) if (!isPlaced(id)) remove(id);
    }
}
