package io.github.mekhontsev.magicdesk;

/** Task preservation and UI destination for an explicit desktop close. */
enum DesktopCloseMode {
    CONTROL_PANEL(true, true),
    HOME(true, false),
    // Full Exit has already returned application tasks and cleared parking.
    EXIT(false, false);

    final boolean parkTasks;
    final boolean showControlPanel;

    DesktopCloseMode(final boolean parkTasks, final boolean showControlPanel) {
        this.parkTasks = parkTasks;
        this.showControlPanel = showControlPanel;
    }
}
