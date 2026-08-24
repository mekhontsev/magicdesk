package io.github.mekhontsev.magicdesk.displayfixes;

import io.github.mekhontsev.magicdesk.display.DisplayTiming;
import io.github.mekhontsev.magicdesk.display.DisplayTimingPolicy;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;

import java.io.IOException;
import java.util.List;

/** One-shot root operation that applies Nubia's advertised native DP timing. */
final class NativeDisplayModeFix {
    private static final String PACKAGE_NAME =
            "io.github.mekhontsev.magicdesk.displayfixes";
    private static final String EDID_MODES =
            "/sys/kernel/lcd_enhance/edid_modes";
    private static final String HPD = "/sys/kernel/lcd_enhance/hpd";
    private static final String REFRESH_COMMAND =
            "io.github.mekhontsev.magicdesk.displayfixes."
                    + "NubiaDisplayRefreshCommand";
    private static final String ROOT_OK = "MAGICDESK_ROOT=ok";
    private static final String STATUS_ROOT_REQUIRED =
            "MAGICDESK_STATUS=root_required";
    private static final String STATUS_NO_DISPLAY =
            "MAGICDESK_STATUS=no_display";
    private static final String STATUS_UNSUPPORTED =
            "MAGICDESK_STATUS=unsupported";
    private static final String MODES_BEGIN = "MAGICDESK_MODES_BEGIN";
    private static final String MODES_END = "MAGICDESK_MODES_END";
    private static final long MODE_TIMEOUT_MILLIS = 10_000L;
    private static final long MODE_POLL_MILLIS = 100L;

    enum Code {
        APPLIED,
        ALREADY_ACTIVE,
        ROOT_DENIED,
        NO_DISPLAY,
        UNSUPPORTED,
        FAILED
    }

    static final class Result {
        final Code code;
        final String timing;
        final String detail;

        Result(final Code code, final String timing, final String detail) {
            this.code = code;
            this.timing = timing == null ? "" : timing;
            this.detail = detail == null ? "" : detail;
        }
    }

    interface Callback {
        void onComplete(Result result);
    }

    private NativeDisplayModeFix() {
    }

    static void apply(final Context context, final Callback callback) {
        final Context appContext = context.getApplicationContext();
        new Thread(() -> callback.onComplete(applyBlocking(appContext)),
                "MagicDeskNativeDisplayFix").start();
    }

    private static Result applyBlocking(final Context context) {
        final RootCommandRunner.Result probe;
        try {
            probe = RootCommandRunner.run(createProbeCommand());
        } catch (IOException | RuntimeException error) {
            return new Result(Code.ROOT_DENIED, "", usefulMessage(error));
        }
        if (!probe.output.contains(ROOT_OK)) {
            return new Result(
                    Code.ROOT_DENIED, "", compact(probe.output));
        }
        if (probe.output.contains(STATUS_NO_DISPLAY)) {
            return new Result(Code.NO_DISPLAY, "", "wired display missing");
        }
        if (probe.output.contains(STATUS_UNSUPPORTED)) {
            return new Result(
                    Code.UNSUPPORTED, "", compact(probe.output));
        }
        if (!probe.succeeded()) {
            return new Result(Code.FAILED, "", compact(probe.output));
        }

        final String rawModes = section(
                probe.output, MODES_BEGIN, MODES_END);
        final List<DisplayTimingPolicy.ParsedTiming> parsed =
                DisplayTimingPolicy.parseNubiaModes(rawModes);
        final List<DisplayTimingPolicy.ParsedTiming> modes =
                DisplayTimingPolicy.normalize(parsed);
        final DisplayTiming target = DisplayTimingPolicy.bestNative(modes);
        if (target == null) {
            return new Result(
                    Code.UNSUPPORTED,
                    "",
                    "vendor EDID mode list is empty");
        }
        final String timing = target.timingKey();
        if (findActiveDisplay(context, target) > Display.DEFAULT_DISPLAY) {
            return new Result(Code.ALREADY_ACTIVE, timing, "");
        }

        final RootCommandRunner.Result applied;
        try {
            applied = RootCommandRunner.run(createApplyCommand(target));
        } catch (IOException | RuntimeException error) {
            return new Result(Code.FAILED, timing, usefulMessage(error));
        }
        if (!applied.output.contains(ROOT_OK)) {
            return new Result(
                    Code.ROOT_DENIED, timing, compact(applied.output));
        }
        if (!applied.succeeded()
                || !applied.output.contains("MAGICDESK_STATUS=applied")) {
            return new Result(Code.FAILED, timing, compact(applied.output));
        }

        final int displayId = waitForMode(context, target);
        if (displayId <= Display.DEFAULT_DISPLAY) {
            return new Result(
                    Code.FAILED,
                    timing,
                    "Android did not report the requested mode after HPD");
        }
        return new Result(
                Code.APPLIED, timing, "display=" + displayId);
    }

    static String createProbeCommand() {
        return "uid=$(/system/bin/id -u 2>/dev/null); "
                + "if [ \"$uid\" != 0 ]; then "
                + "echo " + STATUS_ROOT_REQUIRED + "; exit 70; fi; "
                + "echo " + ROOT_OK + "; "
                + "display_id=$(/system/bin/cmd display get-displays "
                + "--ids-only --type external 2>/dev/null "
                + "| /system/bin/sed -n "
                + "'/^[1-9][0-9]*$/ {p;q;}'); "
                + "if [ -z \"$display_id\" ]; then "
                + "echo " + STATUS_NO_DISPLAY + "; exit 71; fi; "
                + "if [ ! -r " + EDID_MODES + " ]; then "
                + "echo " + STATUS_UNSUPPORTED + "; exit 72; fi; "
                + "modes=$(/system/bin/cat " + EDID_MODES
                + " 2>/dev/null) || { echo " + STATUS_UNSUPPORTED
                + "; exit 72; }; "
                + "if [ -z \"$modes\" ]; then echo " + STATUS_UNSUPPORTED
                + "; exit 72; fi; "
                + "echo " + MODES_BEGIN + "; "
                + "/system/bin/printf '%s\\n' \"$modes\"; "
                + "echo " + MODES_END;
    }

    static String createApplyCommand(final DisplayTiming target) {
        final String vendorValue = target.vendorValue();
        return "uid=$(/system/bin/id -u 2>/dev/null); "
                + "if [ \"$uid\" != 0 ]; then "
                + "echo " + STATUS_ROOT_REQUIRED + "; exit 70; fi; "
                + "echo " + ROOT_OK + "; "
                + "display_id=$(/system/bin/cmd display get-displays "
                + "--ids-only --type external 2>/dev/null "
                + "| /system/bin/sed -n "
                + "'/^[1-9][0-9]*$/ {p;q;}'); "
                + "if [ -z \"$display_id\" ]; then "
                + "echo " + STATUS_NO_DISPLAY + "; exit 71; fi; "
                + "/system/bin/cmd display clear-user-preferred-display-mode "
                + "\"$display_id\" >/dev/null 2>&1 || true; "
                + "/system/bin/printf '%s' '" + vendorValue + "' > "
                + EDID_MODES + " || exit 73; "
                + "/system/bin/sleep 0.2; "
                + "APK=$(/system/bin/pm path " + PACKAGE_NAME
                + " | /system/bin/cut -d: -f2- "
                + "| /system/bin/head -n 1); "
                + "if [ -z \"$APK\" ]; then exit 74; fi; "
                + "CLASSPATH=\"$APK\" /system/bin/app_process / "
                + REFRESH_COMMAND + " || exit 75; "
                + "hpd_low=0; "
                + "restore_hpd() { if [ \"$hpd_low\" = 1 ]; then "
                + "/system/bin/printf 1 > " + HPD + "; fi; }; "
                + "trap restore_hpd EXIT HUP INT TERM; "
                + "/system/bin/printf 0 > " + HPD
                + " || exit 76; hpd_low=1; "
                + "/system/bin/sleep 0.8; "
                + "/system/bin/printf 1 > " + HPD
                + " || exit 77; hpd_low=0; "
                + "trap - EXIT HUP INT TERM; "
                + "echo MAGICDESK_STATUS=applied";
    }

    private static int waitForMode(
            final Context context,
            final DisplayTiming target) {
        final long deadline = SystemClock.uptimeMillis()
                + MODE_TIMEOUT_MILLIS;
        int stableDisplayId = Display.INVALID_DISPLAY;
        int stableSamples = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            final int displayId = findActiveDisplay(context, target);
            if (displayId > Display.DEFAULT_DISPLAY) {
                if (displayId == stableDisplayId) {
                    stableSamples++;
                } else {
                    stableDisplayId = displayId;
                    stableSamples = 1;
                }
                if (stableSamples >= 3) {
                    return displayId;
                }
            } else {
                stableDisplayId = Display.INVALID_DISPLAY;
                stableSamples = 0;
            }
            SystemClock.sleep(MODE_POLL_MILLIS);
        }
        return Display.INVALID_DISPLAY;
    }

    private static int findActiveDisplay(
            final Context context,
            final DisplayTiming target) {
        final DisplayManager manager =
                context.getSystemService(DisplayManager.class);
        if (manager == null) {
            return Display.INVALID_DISPLAY;
        }
        for (final Display display : manager.getDisplays()) {
            if (display.getDisplayId() <= Display.DEFAULT_DISPLAY
                    || display.getState() == Display.STATE_OFF) {
                continue;
            }
            final Display.Mode mode = display.getMode();
            if (mode.getPhysicalWidth() == target.width()
                    && mode.getPhysicalHeight() == target.height()
                    && Math.abs(mode.getRefreshRate()
                            - target.refreshRate()) < 0.6f) {
                return display.getDisplayId();
            }
        }
        return Display.INVALID_DISPLAY;
    }

    static String section(
            final String output,
            final String begin,
            final String end) {
        final int beginAt = output.indexOf(begin);
        if (beginAt < 0) {
            return "";
        }
        final int contentAt = output.indexOf('\n', beginAt + begin.length());
        final int endAt = output.indexOf(end,
                contentAt < 0 ? beginAt + begin.length() : contentAt + 1);
        if (contentAt < 0 || endAt < 0) {
            return "";
        }
        return output.substring(contentAt + 1, endAt).trim();
    }

    private static String compact(final String value) {
        final String compact = value == null
                ? "" : value.trim().replace('\n', ' ');
        if (compact.length() <= 500) {
            return compact;
        }
        return compact.substring(0, 500) + "...";
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message.trim();
    }
}
