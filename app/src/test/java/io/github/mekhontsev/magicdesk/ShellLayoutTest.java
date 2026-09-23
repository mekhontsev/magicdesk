package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class ShellLayoutTest {
    private static final ShellBounds OUTPUT = new ShellBounds(0, 0, 1920, 1080);

    @Test public void overlappingAbsoluteStrutsAreNotAddedAndRetainPartialRanges() {
        final ShellSurface a = surface("a", ShellSurface.Reference.CONTENT, ShellSurface.BOTTOM,
                200, 40, ShellSurface.Margins.NONE,
                ShellReservation.absolute(ShellReservation.Edge.BOTTOM, 40, 100, 400));
        final ShellSurface b = surface("b", ShellSurface.Reference.CONTENT, ShellSurface.BOTTOM,
                200, 40, ShellSurface.Margins.NONE,
                ShellReservation.absolute(ShellReservation.Edge.BOTTOM, 60, 200, 600));
        final ShellLayout layout = layout(OUTPUT, OUTPUT, a, b);
        assertEquals(new ShellBounds(0, 0, 1920, 1020), layout.snapshot().workArea());
        assertEquals(new ShellBounds(100, 1040, 400, 1080), layout.snapshot().exclusions().get(0).bounds());
        assertEquals(new ShellBounds(200, 1020, 600, 1080), layout.snapshot().exclusions().get(1).bounds());
    }

    @Test public void absoluteDistancesUseOutputNotInsetContent() {
        final ShellBounds content = new ShellBounds(10, 25, 1900, 1050);
        final ShellSurface strut = surface("dock", ShellSurface.Reference.CONTENT, ShellSurface.TOP,
                200, 40, ShellSurface.Margins.NONE,
                ShellReservation.absolute(ShellReservation.Edge.TOP, 50, 0, 1920));
        assertEquals(new ShellBounds(10, 50, 1900, 1050), layout(OUTPUT, content, strut).snapshot().workArea());
    }

    @Test public void stackedExclusivePanelsUseAlreadyAvailableAreaIncludingMargin() {
        final ShellSurface a = panel("a", ShellReservation.Edge.BOTTOM, 40, 10, true);
        final ShellSurface b = panel("b", ShellReservation.Edge.BOTTOM, 30, 0, true);
        final ShellLayout.Snapshot snapshot = layout(OUTPUT, OUTPUT, a, b).snapshot();
        assertEquals(new ShellBounds(0, 1030, 1920, 1070), snapshot.surfaces().get("a").content());
        assertEquals(new ShellBounds(0, 1000, 1920, 1030), snapshot.surfaces().get("b").content());
        assertEquals(new ShellBounds(0, 0, 1920, 1000), snapshot.workArea());
    }

    @Test public void layoutClipsExtremeDimensionsWithoutIntegerOverflow() {
        final ShellSurface large = panel("large", ShellReservation.Edge.BOTTOM,
                Integer.MAX_VALUE, Integer.MAX_VALUE, true);
        final ShellLayout.Snapshot snapshot = layout(OUTPUT, OUTPUT, large).snapshot();
        assertFalse(snapshot.workArea().isEmpty());
        assertEquals(OUTPUT.intersect(snapshot.workArea()), snapshot.workArea());
        assertEquals(OUTPUT.intersect(snapshot.surfaces().get("large").paint()),
                snapshot.surfaces().get("large").paint());
    }

    @Test public void allFourEdgesReserveIndependently() {
        final ShellLayout.Snapshot snapshot = layout(OUTPUT, OUTPUT,
                panel("left", ShellReservation.Edge.LEFT, 30, 0, true),
                panel("top", ShellReservation.Edge.TOP, 40, 0, true),
                panel("right", ShellReservation.Edge.RIGHT, 50, 0, true),
                panel("bottom", ShellReservation.Edge.BOTTOM, 60, 0, true)).snapshot();
        assertEquals(new ShellBounds(30, 40, 1870, 1020), snapshot.workArea());
    }

    @Test public void nonExclusiveSurfaceAvoidsAllReservationsRegardlessOfInsertionOrder() {
        final ShellSurface overlay = surface("overlay", ShellSurface.Reference.AVAILABLE,
                ShellSurface.BOTTOM, 100, 20, ShellSurface.Margins.NONE);
        final ShellLayout.Snapshot snapshot = layout(OUTPUT, OUTPUT, overlay,
                panel("dock", ShellReservation.Edge.BOTTOM, 64, 0, true)).snapshot();
        assertEquals(new ShellBounds(910, 996, 1010, 1016), snapshot.surfaces().get("overlay").content());
    }

    @Test public void outputAnchoredBackgroundIgnoresReservations() {
        final ShellSurface background = surface("background", ShellSurface.Reference.OUTPUT,
                15, 0, 0, ShellSurface.Margins.NONE);
        assertEquals(OUTPUT, layout(OUTPUT, OUTPUT, background,
                panel("dock", ShellReservation.Edge.BOTTOM, 64, 0, true))
                .snapshot().surfaces().get("background").content());
    }

    @Test public void centeredFloatingSurfaceSeparatesPaintInputAndExclusiveZone() {
        final ShellSurface original = surface("floating", ShellSurface.Reference.CONTENT,
                ShellSurface.BOTTOM, 600, 60, new ShellSurface.Margins(0, 0, 0, 12));
        final ShellSurface floating = new ShellSurface(original.id(), true, ShellSurface.Layer.TOP,
                ShellSurface.Keyboard.ON_DEMAND, original.placement(),
                new ShellSurface.Margins(4, 4, 4, 4), ShellSurface.Input.CONTENT, List.of());
        final ShellLayout.Snapshot snapshot = layout(OUTPUT, OUTPUT, floating).snapshot();
        final ShellLayout.Surface resolved = snapshot.surfaces().get("floating");
        assertEquals(new ShellBounds(660, 1008, 1260, 1068), resolved.content());
        assertEquals(new ShellBounds(656, 1004, 1264, 1072), resolved.paint());
        assertEquals(resolved.content(), resolved.input());
        assertEquals(OUTPUT, snapshot.workArea());
        assertEquals(ShellSurface.Keyboard.ON_DEMAND, resolved.request().keyboard());
    }

    @Test public void unrelatedOrEmptyPartialRangeDoesNotReserveSpace() {
        final ShellSurface struts = surface("dock", ShellSurface.Reference.CONTENT,
                ShellSurface.BOTTOM, 100, 30, ShellSurface.Margins.NONE,
                ShellReservation.absolute(ShellReservation.Edge.BOTTOM, 50, 2000, 2200),
                ShellReservation.absolute(ShellReservation.Edge.BOTTOM, 50, 100, 100),
                ShellReservation.absolute(ShellReservation.Edge.BOTTOM, 0, 0, 1920));
        assertEquals(OUTPUT, layout(OUTPUT, OUTPUT, struts).snapshot().workArea());
    }

    @Test public void twoScopesMayUseSameSurfaceIdentityWithoutSharingReservations() {
        final ShellLayout a = layout(OUTPUT, OUTPUT, panel("dock", ShellReservation.Edge.BOTTOM, 40, 0, true));
        final ShellLayout b = layout(OUTPUT, OUTPUT, panel("dock", ShellReservation.Edge.LEFT, 60, 0, true));
        a.clear();
        assertEquals(OUTPUT, a.snapshot().workArea());
        assertEquals(new ShellBounds(60, 0, 1920, 1080), b.snapshot().workArea());
    }

    @Test public void commitRemovalResizeAndDuplicateStateHaveAtomicImmutableSnapshots() {
        final ShellSurface a = panel("a", ShellReservation.Edge.BOTTOM, 40, 0, true);
        final ShellSurface b = panel("b", ShellReservation.Edge.BOTTOM, 60, 0, true);
        final ShellLayout layout = layout(OUTPUT, OUTPUT, a, b);
        final ShellLayout.Snapshot previous = layout.snapshot();
        layout.commit(OUTPUT, OUTPUT, List.of(a, b));
        assertSame(previous, layout.snapshot());
        final ShellBounds resized = new ShellBounds(100, 200, 1100, 1000);
        final List<ShellSurface> live = new ArrayList<>(List.of(a));
        layout.commit(resized, resized, live);
        live.clear();
        assertEquals(new ShellBounds(100, 200, 1100, 960), layout.snapshot().workArea());
        assertEquals(1, layout.snapshot().surfaces().size());
        assertEquals(2, previous.surfaces().size());
        assertThrows(UnsupportedOperationException.class, () -> previous.surfaces().clear());
        assertThrows(UnsupportedOperationException.class, () -> previous.exclusions().clear());
        assertThrows(IllegalArgumentException.class, () -> layout.commit(resized, resized, List.of(a, a)));
        assertEquals(1, layout.snapshot().surfaces().size());
    }

    @Test public void invalidStretchAndUnanchoredExclusiveEdgeAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> surface("bad", ShellSurface.Reference.CONTENT,
                ShellSurface.BOTTOM, 0, 20, ShellSurface.Margins.NONE));
        assertThrows(IllegalArgumentException.class, () -> surface("bad", ShellSurface.Reference.CONTENT,
                ShellSurface.BOTTOM, 100, 20, ShellSurface.Margins.NONE,
                ShellReservation.exclusive(ShellReservation.Edge.TOP, 20, true)));
    }

    @Test public void unmappedClientReceivesGeometryButNoReservationOrInput() {
        final ShellSurface mapped = panel("client", ShellReservation.Edge.BOTTOM, 40, 0, true);
        final ShellSurface unmapped = new ShellSurface(mapped.id(), false, mapped.layer(), mapped.keyboard(),
                mapped.placement(), mapped.paintExtension(), mapped.input(), mapped.reservations());
        final ShellSurface other = panel("other", ShellReservation.Edge.BOTTOM, 60, 0, true);
        final ShellLayout layout = layout(OUTPUT, OUTPUT, unmapped, other);
        assertEquals(new ShellBounds(0, 0, 1920, 1020), layout.snapshot().workArea());
        assertEquals(new ShellBounds(0, 980, 1920, 1020), layout.snapshot().surfaces().get("client").content());
        assertTrue(layout.snapshot().surfaces().get("client").input().isEmpty());
        layout.commit(OUTPUT, OUTPUT, List.of(other, mapped));
        assertEquals(new ShellBounds(0, 0, 1920, 980), layout.snapshot().workArea());
        layout.commit(OUTPUT, OUTPUT, List.of(other, unmapped));
        assertEquals(new ShellBounds(0, 0, 1920, 1020), layout.snapshot().workArea());
    }

    @Test public void fixedSizeWithOppositeAnchorsCentersWithoutStretchMargins() {
        final ShellSurface centered = surface("center", ShellSurface.Reference.CONTENT,
                ShellSurface.LEFT | ShellSurface.RIGHT | ShellSurface.TOP, 200, 40,
                new ShellSurface.Margins(400, 10, 20, 0));
        assertEquals(new ShellBounds(860, 10, 1060, 50),
                layout(OUTPUT, OUTPUT, centered).snapshot().surfaces().get("center").content());
    }

    @Test public void higherLayerTakesPlacementPriorityWithoutChangingFocusMetadata() {
        final ShellSurface lower = panel("lower", ShellReservation.Edge.BOTTOM, 40, 0, true);
        final ShellSurface higher = new ShellSurface("higher", true, ShellSurface.Layer.OVERLAY,
                ShellSurface.Keyboard.EXCLUSIVE, lower.placement(), lower.paintExtension(),
                ShellSurface.Input.NONE, lower.reservations());
        final ShellLayout.Snapshot snapshot = layout(OUTPUT, OUTPUT, lower, higher).snapshot();
        assertEquals(1040, snapshot.surfaces().get("higher").content().top());
        assertEquals(1000, snapshot.surfaces().get("lower").content().top());
        assertTrue(snapshot.surfaces().get("higher").input().isEmpty());
        assertEquals(ShellSurface.Keyboard.NONE, snapshot.surfaces().get("lower").request().keyboard());
    }

    @Test public void negativeOriginAndPartialVerticalStrutKeepScopeCoordinates() {
        final ShellBounds output = new ShellBounds(-1920, -100, 0, 980);
        final ShellSurface dock = surface("dock", ShellSurface.Reference.CONTENT,
                ShellSurface.LEFT, 30, 100, ShellSurface.Margins.NONE,
                ShellReservation.absolute(ShellReservation.Edge.LEFT, 30, -50, 300));
        final ShellLayout.Snapshot snapshot = layout(output, output, dock).snapshot();
        assertEquals(new ShellBounds(-1890, -100, 0, 980), snapshot.workArea());
        assertEquals(new ShellBounds(-1920, -50, -1890, 300), snapshot.exclusions().get(0).bounds());
    }

    private static ShellLayout layout(final ShellBounds output, final ShellBounds content,
            final ShellSurface... surfaces) {
        final ShellLayout layout = new ShellLayout();
        layout.commit(output, content, List.of(surfaces));
        return layout;
    }

    private static ShellSurface panel(final String id, final ShellReservation.Edge edge,
            final int size, final int margin, final boolean windows) {
        final int anchors = switch (edge) {
            case LEFT -> ShellSurface.LEFT | ShellSurface.TOP | ShellSurface.BOTTOM;
            case TOP -> ShellSurface.TOP | ShellSurface.LEFT | ShellSurface.RIGHT;
            case RIGHT -> ShellSurface.RIGHT | ShellSurface.TOP | ShellSurface.BOTTOM;
            case BOTTOM -> ShellSurface.BOTTOM | ShellSurface.LEFT | ShellSurface.RIGHT;
        };
        final boolean horizontal = edge == ShellReservation.Edge.TOP || edge == ShellReservation.Edge.BOTTOM;
        final ShellSurface.Margins margins = switch (edge) {
            case LEFT -> new ShellSurface.Margins(margin, 0, 0, 0);
            case TOP -> new ShellSurface.Margins(0, margin, 0, 0);
            case RIGHT -> new ShellSurface.Margins(0, 0, margin, 0);
            case BOTTOM -> new ShellSurface.Margins(0, 0, 0, margin);
        };
        return surface(id, ShellSurface.Reference.AVAILABLE, anchors,
                horizontal ? 0 : size, horizontal ? size : 0, margins,
                ShellReservation.exclusive(edge, size, windows));
    }

    private static ShellSurface surface(final String id, final ShellSurface.Reference reference,
            final int anchors, final int width, final int height, final ShellSurface.Margins margins,
            final ShellReservation... reservations) {
        return new ShellSurface(id, true, ShellSurface.Layer.TOP, ShellSurface.Keyboard.NONE,
                new ShellSurface.Placement(reference, anchors, width, height, margins),
                ShellSurface.Margins.NONE, ShellSurface.Input.CONTENT, List.of(reservations));
    }
}
