package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Region;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Explicit, bounded-window fixture on a caller-selected active Desktop. */
public final class ShellPanelInstrumentation extends Instrumentation {
    private static SparsePanel sPanel;
    private int mDisplayId;
    private String mAction;

    @Override public void onCreate(final Bundle args) {
        super.onCreate(args);
        mDisplayId = Integer.parseInt(args.getString("display", "-1"));
        mAction = args.getString("action", "status");
        start();
    }

    @Override public void onStart() {
        final Bundle result = new Bundle();
        try {
            if (mDisplayId < 0) throw new IllegalArgumentException("Explicit Desktop display required");
            if (mAction.equals("trust")) {
                final CompletableFuture<TaskRepository.ActionResult> ready = new CompletableFuture<>();
                MagicDeskRuntime.prepareDesktopChromeHost(mDisplayId, true, ready::complete);
                final var prepared = ready.get(15, TimeUnit.SECONDS);
                if (!prepared.success) throw new IllegalStateException(prepared.message);
                result.putString("preparation", prepared.message);
            }
            final CompletableFuture<Void> applied = new CompletableFuture<>();
            runOnMainSync(() -> {
                try {
                    final var panels = controller();
                    switch (mAction) {
                        case "show" -> {
                            if (sPanel != null) throw new IllegalStateException("Fixture already open");
                            final var manager = getTargetContext().getSystemService(
                                    android.hardware.display.DisplayManager.class);
                            sPanel = new SparsePanel(getTargetContext().createDisplayContext(
                                    manager.getDisplay(mDisplayId)));
                            if (!panels.show(sPanel, ShellPanelPlacement.centered(900, 650),
                                    false, false, "MagicDesk shell input fixture")) {
                                sPanel = null;
                                throw new IllegalStateException("Panel rejected");
                            }
                            // Keep the sparse fixture on screen when input targets its holes.
                            sPanel.setOnTouchListener(null);
                        }
                        case "hide" -> {
                            if (sPanel != null) panels.hideAll();
                            sPanel = null;
                        }
                        case "status", "trust" -> { }
                        default -> throw new IllegalArgumentException("Unknown fixture action");
                    }
                    result.putString("bounds", panels.visibleBounds().toShortString());
                    result.putInt("downs", sPanel == null ? -1 : sPanel.downs);
                    result.putBoolean("attached", sPanel != null && sPanel.isAttachedToWindow());
                    result.putString("shell_panel", "ok");
                    applied.complete(null);
                } catch (Exception error) { applied.completeExceptionally(error); }
            });
            applied.get(15, TimeUnit.SECONDS);
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            result.putString("shell_panel", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    @SuppressWarnings("unchecked")
    private DesktopPanelWindowController controller() throws ReflectiveOperationException {
        // The fixture borrows the existing controller without exposing a production debug API.
        final var field = DesktopPanelWindowController.class.getDeclaredField("CONTROLLERS");
        field.setAccessible(true);
        final var panels = ((Map<Integer, DesktopPanelWindowController>) field.get(null)).get(mDisplayId);
        if (panels == null) throw new IllegalStateException("Desktop panel host is unavailable");
        return panels;
    }

    private static final class SparsePanel extends View {
        private final Paint paint = new Paint();
        private final Region region = new Region();
        int downs;

        SparsePanel(final Context context) { super(context); }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            region.set(0, 0, 48, height);
            region.op(width - 48, 0, width, height, Region.Op.UNION);
            final var root = getRootSurfaceControl();
            if (root == null) throw new IllegalStateException("Panel Surface is unavailable");
            root.setTouchableRegion(region);
        }

        @Override protected void onDraw(final Canvas canvas) {
            paint.setColor(Color.GREEN);
            canvas.drawRect(0, 0, 48, getHeight(), paint);
            canvas.drawRect(getWidth() - 48, 0, getWidth(), getHeight(), paint);
        }

        @Override public boolean onTouchEvent(final MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) downs++;
            if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
