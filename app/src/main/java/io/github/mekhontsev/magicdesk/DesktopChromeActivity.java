package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Supplies one standard application token for all persistent desktop chrome. */
public final class DesktopChromeActivity extends Activity {
    private static final String EXTRA_DISPLAY_ID =
            "magicdesk_chrome_display_id";
    private static final String CLASS_NAME =
            BuildConfig.APPLICATION_ID + ".DesktopChromeActivity";
    static final ComponentName COMPONENT = new ComponentName(
            BuildConfig.APPLICATION_ID, CLASS_NAME);

    private FrameLayout mRoot;
    private WindowManager mWindowManager;
    private final java.util.Map<String, NativePanel> mPanels = new java.util.LinkedHashMap<>();
    private int mDisplayId = Display.INVALID_DISPLAY;
    private boolean mPresented = true;
    private boolean mEdgeHidden;
    private int mEdgeHeight = 1;

    static Intent createIntent(final int displayId) {
        return new Intent()
                .setComponent(COMPONENT)
                .setData(Uri.parse("magicdesk-desktop-chrome:" + displayId))
                .putExtra(EXTRA_DISPLAY_ID, displayId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    static boolean isChromeComponent(final ComponentName component) {
        return COMPONENT.equals(component);
    }

    IBinder activityToken() {
        return FrameworkActivityInputApi.requireActivityToken(this);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int requestedDisplayId = getIntent().getIntExtra(
                EXTRA_DISPLAY_ID, Display.INVALID_DISPLAY);
        final Display display = getDisplay();
        mDisplayId = display == null
                ? Display.INVALID_DISPLAY : display.getDisplayId();
        if (requestedDisplayId == Display.INVALID_DISPLAY
                || requestedDisplayId != mDisplayId) {
            finishAndRemoveTask();
            overridePendingTransition(0, 0);
            return;
        }
        // Child application windows own all chrome input and geometry. The
        // transparent fullscreen base must not become an input sink.
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setNavigationBarContrastEnforced(false);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        // Transparent pixels do not exempt a window from touch occlusion.
        // Only this empty base is transparent to InputDispatcher; the child
        // panel windows retain their own alpha and receive normal input.
        final WindowManager.LayoutParams baseParams = getWindow().getAttributes();
        baseParams.alpha = 0f;
        getWindow().setAttributes(baseParams);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(Color.TRANSPARENT);
        mRoot.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setContentView(mRoot);
        mWindowManager = getWindowManager();
        mRoot.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(final View view) {
                        registerChromeHost(view.getWindowToken());
                        applyPresentation();
                    }

                    @Override
                    public void onViewDetachedFromWindow(final View view) {
                        DesktopPanelWindowController.unregisterActivity(
                                mDisplayId, DesktopChromeActivity.this);
                        removePanelWindows();
                    }
                });
        DesktopTaskbarHost.registerActivity(mDisplayId, this);
    }

    @Override
    protected void onDestroy() {
        DesktopPanelWindowController.unregisterActivity(mDisplayId, this);
        DesktopTaskbarHost.unregisterActivity(mDisplayId, this);
        detachPanels();
        removePanelWindows();
        mWindowManager = null;
        mRoot = null;
        super.onDestroy();
    }

    private void registerChromeHost(final IBinder windowToken) {
        if (mDisplayId == Display.INVALID_DISPLAY || windowToken == null) {
            return;
        }
        DesktopPanelWindowController.registerActivity(
                mDisplayId, this, mWindowManager, windowToken);
        MagicDeskRuntime.configureDesktopActivityInput(
                mDisplayId, activityToken());
    }

    void attachPanels(java.util.List<DesktopTaskbarHost.Panel> definitions) {
        var ids = definitions.stream().map(DesktopTaskbarHost.Panel::id).toList();
        for (var it = mPanels.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            if (!ids.contains(entry.getKey())) { entry.getValue().release(); it.remove(); }
        }
        for (var definition : definitions) {
            var panel = mPanels.get(definition.id());
            if (panel != null && panel.definition.view() != definition.view()) {
                panel.release(); mPanels.remove(definition.id()); panel = null;
            }
            if (panel == null) {
                panel = new NativePanel(definition);
                mPanels.put(definition.id(), panel);
            } else panel.definition = definition;
        }
        applyPresentation();
    }

    void detachPanels() {
        for (var panel : mPanels.values()) panel.release();
        mPanels.clear();
    }

    void setPresentation(boolean presented, boolean edgeHidden, int edgeHeight) {
        mPresented = presented; mEdgeHidden = edgeHidden; mEdgeHeight = Math.max(1, edgeHeight);
        applyPresentation();
    }

    private void applyPresentation() { for (var panel : mPanels.values()) panel.apply(); }
    private void removePanelWindows() { for (var panel : mPanels.values()) panel.removeWindow(); }
    private static ShellBounds bounds(Rect r) { return new ShellBounds(r.left, r.top, r.right, r.bottom); }

    private final class NativePanel extends FrameLayout {
        DesktopTaskbarHost.Panel definition;
        final UiPanelWindow decoration;
        final Rect applied = new Rect();
        final Rect panelInput = new Rect();
        final android.graphics.Region inputRegion = new android.graphics.Region();
        final android.graphics.Region lastInputRegion = new android.graphics.Region();
        final UiDockEffects dock = new UiDockEffects(this, this::updateInputRegion);
        boolean inputRegionApplied;
        boolean added, hiddenTouch, contentPresented, presentationApplied;

        NativePanel(DesktopTaskbarHost.Panel value) {
            super(value.view().getContext());
            definition = value;
            setBackground(UiAppearance.panelPaint(value.view().getContext(), value.id()));
            decoration = new UiPanelWindow(this);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (value.view().getParent() instanceof ViewGroup parent) parent.removeView(value.view());
            addView(value.view(), new FrameLayout.LayoutParams(value.content().width(), value.content().height()));
        }

        void apply() {
            var view = definition.view();
            var content = definition.content();
            var surface = mEdgeHidden ? definition.paint() : definition.frame();
            var paint = definition.paint();
            panelInput.set(paint.left - surface.left, paint.top - surface.top, paint.right - surface.left, paint.bottom - surface.top);
            decoration.backdropInsets(panelInput.left, panelInput.top, surface.right - paint.right, surface.bottom - paint.bottom);
            var theme = AppearanceStore.current(view.getContext());
            var style = theme.composition().panel(definition.id());
            dock.configure(definition.overflow() == 0 || style == null || mEdgeHidden || !mPresented ? ShellDockEffect.NONE
                    : style.style().hover(), definition.edge(), theme.motion());
            var params = (FrameLayout.LayoutParams) view.getLayoutParams();
            int left = content.left - surface.left, top = content.top - surface.top;
            if (params.width != content.width() || params.height != content.height()
                    || params.leftMargin != left || params.topMargin != top) {
                params.width = content.width(); params.height = content.height();
                params.leftMargin = left; params.topMargin = top; view.setLayoutParams(params);
            }
            boolean visible = mPresented && !mEdgeHidden;
            decoration.presented(visible);
            if (!presentationApplied || visible != contentPresented) {
                presentationApplied = true;
                UiMotion.cancel(view);
                view.setAlpha(visible ? 1 : 0);
                contentPresented = visible;
                if (visible) UiMotion.reveal(view, false, definition.edge());
            }
            view.setVisibility(mPresented ? View.VISIBLE : View.INVISIBLE);
            var target = PanelGeometry.presented(bounds(definition.output()), bounds(surface),
                    definition.edge(), mPresented, mEdgeHidden, mEdgeHeight);
            Rect rect = new Rect(target.left(), target.top(), target.right(), target.bottom());
            if (rect.isEmpty()) { removeWindow(); return; }
            updateInputRegion();
            if (mWindowManager == null || mRoot == null || mRoot.getWindowToken() == null
                    || (added && applied.equals(rect))) return;
            WindowManager.LayoutParams window = new WindowManager.LayoutParams(rect.width(), rect.height(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT);
            window.gravity = Gravity.LEFT | Gravity.TOP; window.x = rect.left; window.y = rect.top;
            window.token = mRoot.getWindowToken();
            // Shared shell layout already resolved stable system insets and panel reservations.
            window.setFitInsetsTypes(0); window.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            window.setTitle("MagicDesk panel " + definition.id());
            decoration.attributes(window);
            if (added) mWindowManager.updateViewLayout(decoration.view(), window);
            else { mWindowManager.addView(decoration.view(), window); added = true; }
            applied.set(rect);
        }
        void removeWindow() {
            if (added && mWindowManager != null) mWindowManager.removeViewImmediate(decoration.view());
            added = false; applied.setEmpty();
        }
        void updateInputRegion() {
            var surface = getRootSurfaceControl();
            if (surface == null) return;
            if (mEdgeHidden) inputRegion.set(0, 0, getWidth(), getHeight());
            else { inputRegion.set(panelInput); dock.input(inputRegion); }
            if (inputRegionApplied && lastInputRegion.equals(inputRegion)) return;
            lastInputRegion.set(inputRegion);
            inputRegionApplied = true;
            surface.setTouchableRegion(inputRegion);
        }
        void release() { dock.clear(); removeWindow(); decoration.close(); removeAllViews(); UiMotion.cancel(definition.view()); }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            dock.layout(definition.view(), changed);
            updateInputRegion();
        }
        @Override protected void onDetachedFromWindow() {
            dock.reset(); inputRegionApplied = false; super.onDetachedFromWindow();
        }
        @Override public boolean dispatchHoverEvent(MotionEvent event) {
            return dock.hover(event) || super.dispatchHoverEvent(event);
        }
        @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
            DesktopTaskbarHost.dispatchEdgeInput(mDisplayId, event);
            return dock.generic(event) || super.dispatchGenericMotionEvent(event);
        }
        @Override public boolean dispatchDragEvent(android.view.DragEvent event) {
            if (event.getAction() == android.view.DragEvent.ACTION_DRAG_STARTED) dock.reset();
            return super.dispatchDragEvent(event);
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            boolean consume = hiddenTouch;
            if (action == MotionEvent.ACTION_DOWN && mEdgeHidden) { hiddenTouch = true; consume = true; }
            // Deliver UP before reveal dismissal can detach the clicked control.
            boolean handled = consume || dock.touch(event) || super.dispatchTouchEvent(event);
            DesktopTaskbarHost.dispatchEdgeInput(mDisplayId, event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) hiddenTouch = false;
            return handled;
        }
    }
}
