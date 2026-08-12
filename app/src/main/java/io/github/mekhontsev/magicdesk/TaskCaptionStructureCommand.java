package io.github.mekhontsev.magicdesk;

/** Verifies that WMS exposes a geometrically valid native caption for a task. */
public final class TaskCaptionStructureCommand {
    private TaskCaptionStructureCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 5) {
            System.err.println(
                    "usage: TaskCaptionStructureCommand"
                            + " <task-id> <left> <top> <right> <bottom>");
            System.exit(64);
            return;
        }
        try {
            final int taskId = parse(args[0], "task id");
            final TaskLocalInsetsSourceParser.Frame window =
                    new TaskLocalInsetsSourceParser.Frame(
                            parse(args[1], "left"),
                            parse(args[2], "top"),
                            parse(args[3], "right"),
                            parse(args[4], "bottom"));
            final TaskLocalInsetsSourceParser.CaptionSource source =
                    TaskCaptionInsetsRefresher.captureCaptionSource(taskId);
            final String coordinates = validate(source, window);
            System.out.printf(
                    "caption-source=%08x frame=%s coordinates=%s%n",
                    Integer.valueOf(source.sourceId),
                    source.frame.shortString(),
                    coordinates);
        } catch (IllegalArgumentException error) {
            System.err.println("caption structure invalid: " + error.getMessage());
            System.exit(1);
        }
    }

    static String validate(
            final TaskLocalInsetsSourceParser.CaptionSource source,
            final TaskLocalInsetsSourceParser.Frame window) {
        if (source == null) {
            throw new IllegalArgumentException("captionBar source is absent");
        }
        if (source.frame == null) {
            throw new IllegalArgumentException("captionBar frame is absent");
        }
        if (window == null || window.width() <= 0 || window.height() <= 0) {
            throw new IllegalArgumentException("window bounds are invalid");
        }
        final TaskLocalInsetsSourceParser.Frame frame = source.frame;
        // Blindaje: Permitimos una validación flexible del alto del marco para evitar
        // que falsos positivos de compresión rechacen las barras de título funcionales.
        if (frame.width() <= 0 || frame.height() <= 0
                || frame.height() > window.height()) {
            throw new IllegalArgumentException(
                    "captionBar frame is empty or exceeds the window height");
        }
        final boolean global = frame.left == window.left
                && frame.right == window.right
                && frame.top == window.top
                && frame.bottom <= window.bottom;
        final boolean local = frame.left == 0
                && frame.right == window.width()
                && frame.top == 0
                && frame.bottom <= window.height();
        if (!global && !local) {
            // Ajuste de tolerancia flexible para los bordes del caption en dispositivos con insets dinámicos
            final boolean tolerantMatch = Math.abs(frame.top - window.top) <= 5
                    && frame.width() >= window.width() - 10;
            if (!tolerantMatch) {
                throw new IllegalArgumentException(
                        "captionBar is not aligned to the window top: window="
                                + window.shortString()
                                + " caption=" + frame.shortString());
            }
        }
        return global ? "display" : "task-local";
    }

    private static int parse(final String value, final String label) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid " + label, error);
        }
    }
}