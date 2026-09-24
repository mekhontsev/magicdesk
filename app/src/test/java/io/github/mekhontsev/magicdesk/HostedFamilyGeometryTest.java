package io.github.mekhontsev.magicdesk;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HostedFamilyGeometryTest {
    private static HostedFamilyGeometry geometry(int width, int height, ShellBounds paint) {
        return new HostedFamilyGeometry(width, height, paint, true, List.of(paint));
    }

    @Test public void dependentMayExtendOutsideItsOwnerWithoutResizingIt() {
        var value = geometry(800, 460, new ShellBounds(780, 430, 1180, 780));
        assertEquals(new ShellBounds(780, 430, 1180, 780), value.place(800, 460));
        assertEquals(800, value.width());
        assertEquals(460, value.height());
    }

    @Test public void ownerLetterboxAndFractionalScaleApplyToChildren() {
        var value = geometry(800, 400, new ShellBounds(-10, -20, 810, 420));
        assertEquals(new ShellBounds(-5, 140, 405, 360), value.place(400, 500));
        assertEquals(new ShellBounds(-9, -19, 740, 384), value.place(731, 365));
    }

    @Test public void edgesAndOversizedDialogsStayWithinTheSharedPanelArea() {
        var area = new ShellBounds(0, 0, 1920, 1016);
        var menu = geometry(800, 460, new ShellBounds(780, 430, 1180, 780));
        assertEquals(new ShellBounds(780, 430, 1180, 780), menu.place(800, 460, 100, 140, area));
        assertEquals(new ShellBounds(20, -134, 420, 216), menu.place(800, 460, 1500, 800, area));
        var dialog = geometry(800, 460, new ShellBounds(0, 0, 4000, 2000));
        assertEquals(new ShellBounds(-100, -84, 1820, 876), dialog.place(800, 460, 100, 140, area));
    }

    @Test public void unmappingAndIncompleteInputRemainExplicit() {
        var empty = new ShellBounds(0, 0, 0, 0);
        assertTrue(geometry(0, 0, empty).place(800, 400).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new HostedFamilyGeometry(10, 10,
                empty, false, List.of(empty)));
    }
}
