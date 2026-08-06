package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DesktopPlacementEngine {
    private DesktopPlacementEngine() {
    }

    static Map<String, DesktopPlacement> arrange(
            final List<Request> requests,
            final int columns,
            final int rows) {
        final Map<String, DesktopPlacement> result = new LinkedHashMap<>();
        if (columns <= 0 || rows <= 0 || requests == null) {
            return result;
        }
        final List<DesktopPlacement> occupied = new ArrayList<>();
        int occupiedCells = 0;
        for (final Request request : requests) {
            if (request == null || request.itemId == null
                    || request.itemId.length() == 0
                    || result.containsKey(request.itemId)) {
                continue;
            }
            if (occupiedCells == columns * rows) {
                continue;
            }
            final int columnSpan = Math.min(
                    columns,
                    Math.max(1, request.columnSpan));
            final int rowSpan = Math.min(
                    rows,
                    Math.max(1, request.rowSpan));
            DesktopPlacement placement = request.preferred == null
                    ? null
                    : new DesktopPlacement(
                            request.preferred.column,
                            request.preferred.row,
                            columnSpan,
                            rowSpan);
            if (!isAvailable(placement, occupied, columns, rows)) {
                final int preferredColumn = placement == null
                        ? 0 : placement.column;
                final int preferredRow = placement == null
                        ? 0 : placement.row;
                placement = findNearestFree(
                        occupied,
                        columns,
                        rows,
                        columnSpan,
                        rowSpan,
                        preferredColumn,
                        preferredRow);
            }
            if (placement != null) {
                result.put(request.itemId, placement);
                occupied.add(placement);
                occupiedCells += placement.columnSpan * placement.rowSpan;
            }
        }
        return result;
    }

    static DesktopPlacement findNearestFree(
            final Iterable<DesktopPlacement> occupied,
            final int columns,
            final int rows,
            final int columnSpan,
            final int rowSpan,
            final int preferredColumn,
            final int preferredRow) {
        DesktopPlacement best = null;
        int bestDistance = Integer.MAX_VALUE;
        final int safeColumnSpan = Math.max(1, Math.min(columns, columnSpan));
        final int safeRowSpan = Math.max(1, Math.min(rows, rowSpan));
        for (int row = 0; row <= rows - safeRowSpan; row++) {
            for (int column = 0; column <= columns - safeColumnSpan; column++) {
                final DesktopPlacement candidate = new DesktopPlacement(
                        column, row, safeColumnSpan, safeRowSpan);
                if (!isAvailable(candidate, occupied, columns, rows)) {
                    continue;
                }
                final int distance = Math.abs(column - preferredColumn)
                        + Math.abs(row - preferredRow);
                if (best == null || distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private static boolean isAvailable(
            final DesktopPlacement candidate,
            final Iterable<DesktopPlacement> occupied,
            final int columns,
            final int rows) {
        if (candidate == null || !candidate.fits(columns, rows)) {
            return false;
        }
        for (final DesktopPlacement placement : occupied) {
            if (candidate.intersects(placement)) {
                return false;
            }
        }
        return true;
    }

    static final class Request {
        final String itemId;
        final int columnSpan;
        final int rowSpan;
        final DesktopPlacement preferred;

        Request(
                final String itemId,
                final int columnSpan,
                final int rowSpan,
                final DesktopPlacement preferred) {
            this.itemId = itemId;
            this.columnSpan = columnSpan;
            this.rowSpan = rowSpan;
            this.preferred = preferred;
        }
    }
}
