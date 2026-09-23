package io.github.mekhontsev.magicdesk;

import java.util.Objects;

/** Shell placement policy; Views supply measured sizes, never final window coordinates. */
sealed interface ShellPanelPlacement {
    ShellSurface.Placement resolve(ShellLayout.Snapshot layout);

    default ShellPanelPlacement ownedBy(final ShellLayout.Surface owner) {
        return this instanceof Popup popup
                ? new OwnedPopup(owner.request().id(), owner.content(), popup) : this;
    }

    record OwnedPopup(String owner, ShellBounds initialOwner, Popup popup) implements ShellPanelPlacement {
        public OwnedPopup {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(initialOwner);
            Objects.requireNonNull(popup);
        }

        @Override public ShellSurface.Placement resolve(final ShellLayout.Snapshot layout) {
            final var surface = layout.surfaces().get(owner);
            if (surface == null || !surface.request().mapped()) {
                throw new IllegalStateException("Popup owner is not mapped in this layout scope");
            }
            return popup.resolveAt(layout, (long) surface.content().left() - initialOwner.left(),
                    (long) surface.content().top() - initialOwner.top());
        }
    }

    record Anchored(int width, int height, int anchors, ShellSurface.Margins margins)
            implements ShellPanelPlacement {
        public Anchored {
            if (width < 1 || height < 1) throw new IllegalArgumentException("Empty panel");
            Objects.requireNonNull(margins);
        }

        @Override public ShellSurface.Placement resolve(final ShellLayout.Snapshot layout) {
            return new ShellSurface.Placement(ShellSurface.Reference.PANEL,
                    anchors, width, height, margins);
        }
    }

    /** Anchor is in scope coordinates; direction selects which side each axis grows toward. */
    record Popup(ShellBounds anchor, int width, int height, Direction horizontal,
            Direction vertical, int gapX, int gapY, int margin, boolean flip)
            implements ShellPanelPlacement {
        enum Direction { BEFORE, AFTER }

        public Popup {
            Objects.requireNonNull(anchor);
            Objects.requireNonNull(horizontal);
            Objects.requireNonNull(vertical);
            if (width < 1 || height < 1 || gapX < 0 || gapY < 0 || margin < 0) {
                throw new IllegalArgumentException("Invalid popup placement");
            }
        }

        @Override public ShellSurface.Placement resolve(final ShellLayout.Snapshot layout) {
            return resolveAt(layout, 0, 0);
        }

        private ShellSurface.Placement resolveAt(final ShellLayout.Snapshot layout,
                final long offsetX, final long offsetY) {
            final ShellBounds area = layout.panelArea();
            final int insetX = Math.min(margin, (area.width() - 1) / 2);
            final int insetY = Math.min(margin, (area.height() - 1) / 2);
            final int w = Math.min(width, area.width() - 2 * insetX);
            final int h = Math.min(height, area.height() - 2 * insetY);
            final int x = position(area.left() + insetX, area.right() - insetX,
                    anchor.left() + offsetX, anchor.right() + offsetX, w, horizontal, gapX, flip);
            final int y = position(area.top() + insetY, area.bottom() - insetY,
                    anchor.top() + offsetY, anchor.bottom() + offsetY, h, vertical, gapY, flip);
            return new ShellSurface.Placement(ShellSurface.Reference.OUTPUT,
                    ShellSurface.LEFT | ShellSurface.TOP, w, h,
                    new ShellSurface.Margins(x - layout.output().left(),
                            y - layout.output().top(), 0, 0));
        }

        private static int position(final int start, final int end,
                final long anchorStart, final long anchorEnd, final int size,
                final Direction direction, final int gap, final boolean flip) {
            final long before = anchorStart - gap - size;
            final long after = anchorEnd + gap;
            long position = direction == Direction.BEFORE ? before : after;
            if (flip && (position < start || position + size > end)) {
                final long alternative = direction == Direction.BEFORE ? after : before;
                if (alternative >= start && alternative + size <= end) position = alternative;
            }
            return (int) Math.max(start, Math.min(position, (long) end - size));
        }
    }

    static ShellPanelPlacement anchored(final int width, final int height, final int anchors,
            final int left, final int top, final int right, final int bottom) {
        return new Anchored(width, height, anchors, new ShellSurface.Margins(left, top, right, bottom));
    }

    static ShellPanelPlacement centered(final int width, final int height) {
        return anchored(width, height, 0, 0, 0, 0, 0);
    }

    static ShellPanelPlacement atPointer(final int x, final int y, final int width,
            final int height, final int margin) {
        return new Popup(new ShellBounds(x, y, x, y), width, height,
                Popup.Direction.AFTER, Popup.Direction.AFTER, margin, margin, margin, true);
    }

    static ShellPanelPlacement aboveRight(final ShellBounds anchor, final int width, final int height) {
        final ShellBounds corner = new ShellBounds(anchor.right(), anchor.top(), anchor.right(), anchor.top());
        return new Popup(corner, width, height, Popup.Direction.BEFORE, Popup.Direction.BEFORE,
                0, 0, 0, false);
    }
}
