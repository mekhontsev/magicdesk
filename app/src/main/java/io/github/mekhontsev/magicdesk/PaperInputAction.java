package io.github.mekhontsev.magicdesk;

/** Actions assignable to external keyboard shortcuts and keyboard-programmable mouse buttons. */
enum PaperInputAction {
    SYSTEM(null), PREVIOUS(DesktopWindowAction.PAPER_PREVIOUS), NEXT(DesktopWindowAction.PAPER_NEXT),
    MOVE_LEFT(DesktopWindowAction.PAPER_MOVE_LEFT), MOVE_RIGHT(DesktopWindowAction.PAPER_MOVE_RIGHT),
    WIDTH(DesktopWindowAction.PAPER_WIDTH), FLOAT(DesktopWindowAction.PAPER_FLOAT);

    final DesktopWindowAction command;
    PaperInputAction(DesktopWindowAction command) { this.command = command; }

    static PaperInputAction parse(String value) {
        try { return valueOf(value); }
        catch (IllegalArgumentException | NullPointerException ignored) { return SYSTEM; }
    }
}
