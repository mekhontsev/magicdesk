package io.github.mekhontsev.magicdesk;

import android.graphics.Bitmap;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** The ordinary capture/input commands addressed to a retained output, not an Android display. */
final class AutomationGraphicsOutput {
    static DesktopAutomationResult execute(String operation, JSONObject args) {
        try {
            validateTarget(args);
            String id = args.getString("graphicsSessionId");
            Long window = args.has("windowId") ? AutomationJsonArguments.requiredLong(args, "windowId") : null;
            var output = AutomationMainThread.read(() -> {
                var session = GraphicalSessions.find(id);
                if (session == null) throw new IllegalArgumentException("Graphical session is unavailable");
                return session.offscreen().require(window);
            });
            var result = new JSONObject().put("graphicsSessionId", id).put("windowId", output.window)
                    .put("coordinateSpace", "output_pixels");
            if (operation.equals("capture_screenshot") || operation.equals("sample_pixels"))
                return capture(output, operation, args, result);
            AutomationInputSequence.Sink sink = new AutomationInputSequence.Sink() {
                public void validatePoint(float x, float y) { output.validatePoint(x, y); }
                public void pointer(int action, float x, float y) {
                    dispatch(() -> {
                        output.pointer(x, y);
                        if (action == MotionEvent.ACTION_DOWN) output.button(HostedSurfaceOutput.Button.PRIMARY, true);
                        else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                            output.button(HostedSurfaceOutput.Button.PRIMARY, false);
                    });
                }
                public void key(int action, int key, int meta) {
                    dispatch(() -> output.key(key, action == KeyEvent.ACTION_DOWN));
                }
            };
            switch (operation) {
                case "input.gesture" -> AutomationInputSequence.gesture(sink, args);
                case "input.key_chord" -> AutomationInputSequence.keyChord(sink, args);
                case "send_key" -> AutomationInputSequence.keyChord(sink,
                        new JSONObject().put("keys", new JSONArray().put(args.getString("keyCode"))));
                case "move_pointer" -> {
                    int x = AutomationJsonArguments.requiredInt(args, "x"), y = AutomationJsonArguments.requiredInt(args, "y");
                    dispatch(() -> output.pointer(x, y));
                }
                case "click_pointer" -> {
                    if (args.has("x") != args.has("y")) throw new IllegalArgumentException("x and y must be supplied together");
                    Integer x = args.has("x") ? AutomationJsonArguments.requiredInt(args, "x") : null;
                    Integer y = args.has("y") ? AutomationJsonArguments.requiredInt(args, "y") : null;
                    var button = switch (args.optString("button", "primary")) {
                        case "primary" -> HostedSurfaceOutput.Button.PRIMARY;
                        case "secondary" -> HostedSurfaceOutput.Button.SECONDARY;
                        default -> throw new IllegalArgumentException("Unknown pointer button");
                    };
                    dispatch(() -> {
                        if (x != null) output.pointer(x, y);
                        else output.requirePointer();
                        try { output.button(button, true); }
                        finally { output.button(button, false); }
                    });
                }
                default -> throw new IllegalArgumentException("This operation does not accept a graphical output target");
            }
            return DesktopAutomationResult.success("input dispatched", result.put("injected", true));
        } catch (DispatchFailure error) {
            return error.result;
        } catch (Exception error) {
            Throwable cause = error instanceof java.util.concurrent.CompletionException ? error.getCause() : error;
            return DesktopAutomationResult.failure(cause instanceof IllegalArgumentException || cause instanceof org.json.JSONException
                    ? DesktopAutomationErrorCode.INVALID_ARGUMENT : DesktopAutomationErrorCode.ACTION_FAILED,
                    ShellAccess.usefulMessage(cause), false);
        }
    }

    static void validateTarget(JSONObject args) {
        if (args.has("displayId") || args.has("taskId") || args.has("sessionId"))
            throw new IllegalArgumentException("graphicsSessionId excludes Android display/task and Linux accessibility session selectors");
    }

    private static void dispatch(Runnable action) {
        var result = AutomationMainThread.await(AutomationMainThread.submit(() -> {
            action.run(); return new JSONObject();
        }), "offscreen input", false);
        if (!result.success) throw new DispatchFailure(result);
    }

    private static final class DispatchFailure extends RuntimeException {
        final DesktopAutomationResult result;
        DispatchFailure(DesktopAutomationResult result) { super(result.message); this.result = result; }
    }

    private static DesktopAutomationResult capture(HostedOffscreen.Output output, String operation,
            JSONObject args, JSONObject result) throws Exception {
        int left = 0, top = 0, right = output.image.width(), bottom = output.image.height();
        var points = operation.equals("sample_pixels") ? args.getJSONArray("points") : null;
        if (points != null) {
            if (points.length() < 1 || points.length() > 64) throw new IllegalArgumentException("points must contain 1 to 64 coordinates");
            left = right; top = bottom; right = 0; bottom = 0;
            for (int i = 0; i < points.length(); i++) {
                int x = AutomationJsonArguments.requiredInt(points.getJSONObject(i), "x");
                int y = AutomationJsonArguments.requiredInt(points.getJSONObject(i), "y");
                output.validatePoint(x, y);
                left = Math.min(left, x); top = Math.min(top, y);
                right = Math.max(right, x + 1); bottom = Math.max(bottom, y + 1);
            }
        } else if (args.has("region")) {
            var region = args.getJSONObject("region");
            left = AutomationJsonArguments.requiredInt(region, "left"); top = AutomationJsonArguments.requiredInt(region, "top");
            right = AutomationJsonArguments.requiredInt(region, "right"); bottom = AutomationJsonArguments.requiredInt(region, "bottom");
        }
        var snapshot = output.image.capture(left, top, right, bottom);
        Bitmap bitmap = snapshot.bitmap();
        try {
            result.put("sourceType", "graphics").put("sourceWidth", output.image.width()).put("sourceHeight", output.image.height())
                    .put("width", bitmap.getWidth()).put("height", bitmap.getHeight())
                    .put("frameSequence", snapshot.sequence()).put("frameUptimeMillis", snapshot.receivedUptimeMillis())
                    .put("sourceBounds", new JSONObject().put("left", left).put("top", top).put("right", right).put("bottom", bottom));
            if (points != null) {
                var samples = new JSONArray();
                for (int i = 0; i < points.length(); i++) {
                    int x = AutomationJsonArguments.requiredInt(points.getJSONObject(i), "x");
                    int y = AutomationJsonArguments.requiredInt(points.getJSONObject(i), "y");
                    int color = bitmap.getPixel(x - left, y - top);
                    samples.put(new JSONObject().put("x", x).put("y", y).put("argb", String.format(Locale.ROOT, "#%08X", color))
                            .put("alpha", color >>> 24).put("red", color >>> 16 & 255).put("green", color >>> 8 & 255).put("blue", color & 255));
                }
                return DesktopAutomationResult.success("output pixels sampled", result.put("samples", samples));
            }
            var png = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) throw new java.io.IOException("PNG encoding failed");
            return DesktopAutomationResult.success("output captured", result.put("mimeType", "image/png"),
                    new DesktopAutomationImage("image/png", Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP)));
        } finally { bitmap.recycle(); }
    }
    private AutomationGraphicsOutput() { }
}
