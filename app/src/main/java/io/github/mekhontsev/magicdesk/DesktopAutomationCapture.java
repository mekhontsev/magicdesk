package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.AutomationJsonArguments.requiredInt;

import android.content.Context;
import android.util.Base64;
import android.view.Display;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.function.IntSupplier;

/** MCP adaptation of shared capture; source selection never changes focus or placement. */
final class DesktopAutomationCapture {
    private final CaptureService mCapture;

    DesktopAutomationCapture(final Context context) {
        mCapture = new CaptureService(context);
    }

    DesktopAutomationResult screenshot(final JSONObject args) {
        try {
            final CaptureService.Image image = mCapture.capture(
                    request(args, DesktopAutomationCapture::defaultDisplayId));
            return DesktopAutomationResult.success(
                    "screenshot captured", imageMetadata(image),
                    new DesktopAutomationImage("image/png",
                            Base64.encodeToString(image.png(), Base64.NO_WRAP)));
        } catch (IllegalArgumentException | JSONException error) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.INVALID_ARGUMENT,
                    ShellAccess.usefulMessage(error), false);
        } catch (IOException | RuntimeException error) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.CAPTURE_UNAVAILABLE,
                    ShellAccess.usefulMessage(error), true);
        }
    }

    DesktopAutomationResult samplePixels(final JSONObject args) {
        try {
            final JSONArray points = args.getJSONArray("points");
            if (points.length() == 0 || points.length() > 64) {
                throw new IllegalArgumentException("points must contain 1 to 64 coordinates");
            }
            final int[] x = new int[points.length()];
            final int[] y = new int[points.length()];
            for (int i = 0; i < points.length(); i++) {
                final JSONObject point = points.getJSONObject(i);
                x[i] = requiredInt(point, "x");
                y[i] = requiredInt(point, "y");
            }
            final CaptureService.Samples result = mCapture.samplePixels(
                    displayId(args, DesktopAutomationCapture::defaultDisplayId), x, y);
            final JSONArray samples = new JSONArray();
            for (int i = 0; i < result.colors().length; i++) {
                final int color = result.colors()[i];
                samples.put(new JSONObject()
                        .put("x", x[i]).put("y", y[i])
                        .put("argb", String.format(Locale.ROOT, "#%08X", color))
                        .put("alpha", (color >>> 24) & 0xFF)
                        .put("red", (color >>> 16) & 0xFF)
                        .put("green", (color >>> 8) & 0xFF)
                        .put("blue", color & 0xFF));
            }
            return DesktopAutomationResult.success(
                    "display pixels sampled",
                    metadata(result.display())
                            .put("width", result.display().width())
                            .put("height", result.display().height())
                            .put("samples", samples));
        } catch (IllegalArgumentException | JSONException error) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.INVALID_ARGUMENT,
                    ShellAccess.usefulMessage(error), false);
        } catch (IOException | RuntimeException error) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.CAPTURE_UNAVAILABLE,
                    ShellAccess.usefulMessage(error), true);
        }
    }

    static CaptureRequest request(final JSONObject args, final IntSupplier defaultDisplayId)
            throws JSONException {
        if (args.has("taskId") && args.has("displayId")) {
            throw new IllegalArgumentException("taskId and displayId are mutually exclusive");
        }
        final CaptureRequest.Region region;
        if (args.has("region")) {
            final JSONObject value = args.getJSONObject("region");
            region = new CaptureRequest.Region(
                    requiredInt(value, "left"), requiredInt(value, "top"),
                    requiredInt(value, "right"), requiredInt(value, "bottom"));
        } else {
            region = null;
        }
        return args.has("taskId")
                ? new CaptureRequest(CaptureRequest.Target.TASK, requiredInt(args, "taskId"), region)
                : new CaptureRequest(CaptureRequest.Target.DISPLAY, displayId(args, defaultDisplayId), region);
    }

    static JSONObject imageMetadata(final CaptureService.Image image) throws JSONException {
        final CaptureRequest.Region region = image.region();
        final boolean task = image.request().target() == CaptureRequest.Target.TASK;
        final JSONObject data = new JSONObject()
                .put("sourceType", task ? "task" : "display")
                .put(task ? "taskId" : "displayId", image.request().id())
                .put("captureSource", (task ? "t:" : "l:") + image.request().id())
                .put("width", region.width()).put("height", region.height())
                .put("sourceWidth", image.sourceWidth()).put("sourceHeight", image.sourceHeight())
                .put("rotation", image.rotation())
                .put("sourceBounds", new JSONObject().put("left", region.left()).put("top", region.top())
                        .put("right", region.right()).put("bottom", region.bottom()))
                .put("mimeType", "image/png");
        if (task) {
            final TaskCapture.Info info = image.task();
            data.put("taskWidth", info.taskWidth()).put("taskHeight", info.taskHeight())
                    .put("topActivity", info.topActivity());
        } else {
            data.put("displayWidth", image.sourceWidth()).put("displayHeight", image.sourceHeight());
        }
        return data;
    }

    private static int displayId(final JSONObject args, final IntSupplier defaultDisplayId)
            throws JSONException {
        return args.has("displayId") ? requiredInt(args, "displayId") : defaultDisplayId.getAsInt();
    }

    private static int defaultDisplayId() {
        return DesktopRuntimeBridge.hasWorkspaces()
                ? DesktopRuntimeBridge.requireSingleDesktopDisplay() : Display.DEFAULT_DISPLAY;
    }

    private static JSONObject metadata(final CaptureService.Frame display)
            throws JSONException {
        return new JSONObject()
                .put("displayId", display.displayId())
                .put("rotation", display.rotation())
                .put("captureSource", display.source().commandArgument());
    }
}
