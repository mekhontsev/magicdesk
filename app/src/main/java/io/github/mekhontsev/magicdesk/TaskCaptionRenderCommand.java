package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks that a native caption contributes visible controls to the display. */
public final class TaskCaptionRenderCommand {
    private static final int SAMPLE_WIDTH = 96;
    private static final int SAMPLE_HEIGHT = 16;
    private static final Pattern RESULT = Pattern.compile(
            "(?m)^caption-render=(varied|uniform)"
                    + " sample=(\\d+)x(\\d+)"
                    + " dominant=([0-9a-fA-F]{8})"
                    + " contrast=(\\d+)"
                    + " contrasting=(\\d+)/(\\d+)"
                    + " signature=([0-9a-fA-F]+)"
                    + " crop=(\\[[^\\r\\n]+)$");

    private TaskCaptionRenderCommand() {
    }

    static String createCommand(
            final DisplayCaptureSource captureSource,
            final int taskId,
            final Rect windowBounds,
            final Rect sampleBounds) {
        if (!hasArea(windowBounds) || !hasArea(sampleBounds)) {
            throw new IllegalArgumentException("invalid caption window bounds");
        }
        return AppProcessCommand.run(
                TaskCaptionRenderCommand.class.getName(),
                "caption " + captureSource.commandArgument()
                        + " " + taskId + " "
                        + windowBounds.left + " " + windowBounds.top + " "
                        + windowBounds.right + " " + windowBounds.bottom + " "
                        + formatArguments(sampleBounds));
    }

    static String createReferenceCommand(
            final DisplayCaptureSource captureSource,
            final Rect sampleBounds) {
        if (!hasArea(sampleBounds)) {
            throw new IllegalArgumentException("invalid caption sample bounds");
        }
        return AppProcessCommand.run(
                TaskCaptionRenderCommand.class.getName(),
                "reference " + captureSource.commandArgument() + " "
                        + formatArguments(sampleBounds));
    }

    public static void main(final String[] args) {
        if (args.length != 6 && args.length != 11) {
            System.err.println("usage: TaskCaptionRenderCommand"
                    + " reference <capture-source> <left> <top> <right> <bottom>"
                    + " | caption <capture-source> <task-id>"
                    + " <window-left> <window-top> <window-right> <window-bottom>"
                    + " <sample-left> <sample-top> <sample-right> <sample-bottom>");
            System.exit(64);
            return;
        }
        try {
            if (args.length == 6 && "reference".equals(args[0])) {
                captureAndPrint(
                        DisplayCaptureSource.parse(args[1]),
                        parseRect(args, 2, "sample"));
                return;
            }
            if (args.length != 11 || !"caption".equals(args[0])) {
                throw new IllegalArgumentException("invalid caption render mode");
            }
            final DisplayCaptureSource captureSource =
                    DisplayCaptureSource.parse(args[1]);
            final int taskId = parse(args[2], "task id");
            final TaskLocalInsetsSourceParser.Frame window =
                    new TaskLocalInsetsSourceParser.Frame(
                            parse(args[3], "window left"),
                            parse(args[4], "window top"),
                            parse(args[5], "window right"),
                            parse(args[6], "window bottom"));
            final Rect crop = parseRect(args, 7, "sample");
            final TaskLocalInsetsSourceParser.CaptionSource source =
                    TaskCaptionInsetsRefresher.captureCaptionSource(taskId);
            validateSampleFrame(source, window, crop);
            captureAndPrint(captureSource, crop);
        } catch (DisplayPixelProbe.UnavailableException error) {
            System.err.println("caption render unsupported: "
                    + usefulMessage(error));
            System.exit(2);
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("caption render inspection failed: "
                    + usefulMessage(error));
            System.exit(1);
        }
    }

    static Observation parseObservation(final String output)
            throws IOException {
        final Matcher matcher = RESULT.matcher(output == null ? "" : output);
        if (!matcher.find()) {
            throw new IOException("caption render returned no observation");
        }
        try {
            return new Observation(
                    "varied".equals(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    (int) Long.parseLong(matcher.group(4), 16),
                    Integer.parseInt(matcher.group(5)),
                    Integer.parseInt(matcher.group(6)),
                    Integer.parseInt(matcher.group(7)),
                    DisplayPixelProbe.RegionSignature.decode(
                            Integer.parseInt(matcher.group(2)),
                            Integer.parseInt(matcher.group(3)),
                            matcher.group(8)),
                    matcher.group(9));
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid caption render observation", error);
        }
    }

    static TaskLocalInsetsSourceParser.Frame captureFrame(
            final TaskLocalInsetsSourceParser.CaptionSource source,
            final TaskLocalInsetsSourceParser.Frame window) {
        final TaskLocalInsetsSourceParser.Frame caption =
                resolveCaptionFrame(source, window);
        final int width = caption.width();
        final int height = caption.height();
        final int verticalInset = Math.max(1, height / 6);
        final TaskLocalInsetsSourceParser.Frame sample =
                new TaskLocalInsetsSourceParser.Frame(
                        caption.left + width / 2,
                        caption.top + verticalInset,
                        caption.right - 1,
                        caption.bottom - verticalInset);
        if (sample.width() <= 0 || sample.height() <= 0) {
            throw new IllegalArgumentException(
                    "caption render sample is empty");
        }
        return sample;
    }

    static void validateSampleFrame(
            final TaskLocalInsetsSourceParser.CaptionSource source,
            final TaskLocalInsetsSourceParser.Frame window,
            final Rect sample) {
        final TaskLocalInsetsSourceParser.Frame caption =
                resolveCaptionFrame(source, window);
        if (!hasArea(sample)
                || sample.left < caption.left || sample.top < caption.top
                || sample.right > caption.right
                || sample.bottom > caption.bottom) {
            throw new IllegalArgumentException(
                    "caption sample is outside " + caption.shortString());
        }
    }

    private static TaskLocalInsetsSourceParser.Frame resolveCaptionFrame(
            final TaskLocalInsetsSourceParser.CaptionSource source,
            final TaskLocalInsetsSourceParser.Frame window) {
        final String coordinates = TaskCaptionStructureCommand.validate(
                source, window);
        final TaskLocalInsetsSourceParser.Frame frame = source.frame;
        return new TaskLocalInsetsSourceParser.Frame(
                "task-local".equals(coordinates)
                        ? window.left + frame.left : frame.left,
                "task-local".equals(coordinates)
                        ? window.top + frame.top : frame.top,
                "task-local".equals(coordinates)
                        ? window.left + frame.right : frame.right,
                "task-local".equals(coordinates)
                        ? window.top + frame.bottom : frame.bottom);
    }

    private static void captureAndPrint(
            final DisplayCaptureSource captureSource,
            final Rect crop) throws IOException {
        final DisplayPixelProbe.RegionStats stats =
                DisplayPixelProbe.captureRegion(
                        captureSource, crop, SAMPLE_WIDTH, SAMPLE_HEIGHT);
        System.out.printf(Locale.US,
                "caption-render=%s sample=%dx%d dominant=%08x"
                        + " contrast=%d contrasting=%d/%d"
                        + " signature=%s crop=%s%n",
                stats.visuallyVaried ? "varied" : "uniform",
                Integer.valueOf(stats.width),
                Integer.valueOf(stats.height),
                Integer.valueOf(stats.dominantColor),
                Integer.valueOf(stats.maxContrast),
                Integer.valueOf(stats.contrastingPixels),
                Integer.valueOf(stats.width * stats.height),
                stats.signature.encode(),
                crop.toShortString());
    }

    private static Rect parseRect(
            final String[] args,
            final int offset,
            final String label) {
        return new Rect(
                parse(args[offset], label + " left"),
                parse(args[offset + 1], label + " top"),
                parse(args[offset + 2], label + " right"),
                parse(args[offset + 3], label + " bottom"));
    }

    private static String formatArguments(final Rect bounds) {
        return bounds.left + " " + bounds.top + " "
                + bounds.right + " " + bounds.bottom;
    }

    private static boolean hasArea(final Rect bounds) {
        return bounds != null
                && bounds.right > bounds.left
                && bounds.bottom > bounds.top;
    }

    private static int parse(final String value, final String label) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid " + label, error);
        }
    }

    private static String usefulMessage(final Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        return message == null || message.isEmpty()
                ? current.getClass().getSimpleName() : message;
    }

    static final class Observation {
        final boolean visuallyVaried;
        final int width;
        final int height;
        final int dominantColor;
        final int maxContrast;
        final int contrastingPixels;
        final int totalPixels;
        final DisplayPixelProbe.RegionSignature signature;
        final String crop;

        Observation(
                final boolean visuallyVaried,
                final int width,
                final int height,
                final int dominantColor,
                final int maxContrast,
                final int contrastingPixels,
                final int totalPixels,
                final DisplayPixelProbe.RegionSignature signature,
                final String crop) {
            this.visuallyVaried = visuallyVaried;
            this.width = width;
            this.height = height;
            this.dominantColor = dominantColor;
            this.maxContrast = maxContrast;
            this.contrastingPixels = contrastingPixels;
            this.totalPixels = totalPixels;
            this.signature = signature;
            this.crop = crop;
        }

        String detail() {
            return String.format(Locale.US,
                    "sample=%dx%d dominant=%08x contrast=%d"
                            + " contrasting=%d/%d crop=%s",
                    Integer.valueOf(width),
                    Integer.valueOf(height),
                    Integer.valueOf(dominantColor),
                    Integer.valueOf(maxContrast),
                    Integer.valueOf(contrastingPixels),
                    Integer.valueOf(totalPixels),
                    crop);
        }
    }
}
