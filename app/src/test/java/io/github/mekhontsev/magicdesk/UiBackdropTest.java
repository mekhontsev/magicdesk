package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Execute the real Window binding without requiring a device compositor. */
public final class UiBackdropTest {
    @Test public void overflowKeepsPaintInsideItsPanelAndDoesNotReallocateOnRefresh() throws Exception {
        verify("""
                var content = new View(); content.setBackground(paint);
                var host = new UiPanelWindow(content); var frame = Dialog.last.window;
                frame.decor.attach();
                host.backdropInsets(16, 24, 16, 8);
                check(frame.background instanceof InsetDrawable, "overflow painted as whole window");
                var inset = (InsetDrawable) frame.background;
                check(inset.paint()==paint && inset.left()==16 && inset.top()==24 && inset.bottom()==8, "paint insets");
                check(!frame.decor.clipped && !frame.decor.clipChildren, "magnification clipped");
                check(frame.decor.padding==0, "paint insets applied twice to content");
                var padding = new Rect();
                check(!inset.getPadding(padding) && padding.left==0 && padding.top==0, "decor rebuild restores inset padding");
                check(frame.radius==0, "blur covered transparent overflow");
                host.backdropInsets(16,24,16,8);
                check(frame.background==inset, "unchanged presentation reallocated background");
                host.backdropInsets(0,0,0,0);
                check(frame.radius>0, "window-sized panel lost blur");
                check(frame.background==paint && frame.decor.clipped && frame.decor.clipChildren, "normal clipping not restored");
                host.close();
                """);
    }
    @Test public void attachmentRefreshDetachAndReattach() throws Exception {
        verify("""
                UiBackdrop.bind(window, paint);
                check(paint.backdrop && paint.refreshes == 1 && window.background == paint, "paint binding");
                check(view.listeners.size() == 1 && window.writes == 0, "detached effect");
                UiBackdrop.refreshAll();
                check(window.writes == 0, "detached refresh");
                view.attach();
                check(window.radius == 24, "attached density conversion");
                paint.radius = 30;
                UiBackdrop.refreshAll();
                check(window.radius == 60, "live radius");
                view.detach();
                check(window.radius == 0, "detached radius");
                int writes = window.writes;
                UiBackdrop.refreshAll();
                check(window.writes == writes, "detached registration made work");
                view.attach();
                check(window.radius == 60 && view.listeners.size() == 1, "reattachment");
                """);
    }

    @Test public void hiddenTaskbarDoesNotRetainOrRestoreBlurOnThemeChange() throws Exception {
        verify("""
                UiBackdrop.bind(window, paint);
                UiBackdrop.presented(window, false);
                view.attach();
                check(window.radius == 0, "hidden attach");
                UiBackdrop.presented(window, true);
                check(window.radius == 24, "reveal");
                UiBackdrop.presented(window, false);
                paint.radius = 32;
                UiBackdrop.refreshAll();
                check(window.radius == 0, "theme revived hidden backdrop");
                view.detach(); view.attach();
                check(window.radius == 0, "reattach revived hidden backdrop");
                UiBackdrop.presented(window, true);
                check(window.radius == 64, "reveal did not resolve latest theme");
                """);
    }

    @Test public void replacementAndCloseReleaseRegistrations() throws Exception {
        verify("""
                view.attach();
                UiBackdrop.bind(window, paint);
                var replacement = new UiAppearance.Paint();
                replacement.radius = 7;
                UiBackdrop.bind(window, replacement);
                check(window.radius == 14 && window.background == replacement, "replacement");
                check(view.listeners.size() == 1, "duplicate listener");
                paint.radius = 60;
                UiBackdrop.refreshAll();
                check(window.radius == 14, "old paint still owns window");
                UiBackdrop.unbind(window);
                check(window.radius == 0 && view.listeners.isEmpty(), "unbind");
                check(view.getTag(R.id.appearance_backdrop) == null, "retained tag");
                int writes = window.writes;
                UiBackdrop.unbind(window);
                UiBackdrop.refreshAll();
                view.detach(); view.attach();
                check(window.writes == writes, "released binding revived");
                """);
    }

    @Test public void optionalEffectFailureDoesNotPreventCleanupOrReplaceTheFill() throws Exception {
        verify("""
                window.reject = true;
                UiBackdrop.bind(window, paint);
                view.attach();
                UiBackdrop.refreshAll();
                UiBackdrop.presented(window, false);
                view.detach();
                check(window.background == paint, "failure changed opacity/fill");
                UiBackdrop.unbind(window);
                check(view.listeners.isEmpty(), "failure blocked cleanup");
                """);
    }

    @Test public void softwareRenderingAndDisabledStyleRequestNoBlur() throws Exception {
        verify("""
                UiBackdrop.bind(window, paint);
                view.hardware = false; view.attach();
                check(window.radius == 0, "software effect");
                view.hardware = true; UiBackdrop.refreshAll();
                check(window.radius == 24, "hardware recovery");
                paint.radius = 0; UiBackdrop.refreshAll();
                check(window.radius == 0 && window.background == paint, "zero radius");
                check(UiBackdrop.radiusPixels(7, 1.5f) == 11, "rounding");
                check(UiBackdrop.radiusPixels(64, 4f) == 150, "physical cost bound");
                check(UiBackdrop.radiusPixels(-1, 2f) == 0, "negative radius");
                """);
    }

    @Test public void panelDecorationBorrowsContentWithoutAcquiringAWindowOrInput() throws Exception {
        verify("""
                var content = new View(); content.setBackground(paint);
                var host = new UiPanelWindow(content);
                var frame = Dialog.last.window;
                check(frame.callback == null && frame.type == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        "dialog input policy installed");
                check(!frame.fits && frame.decor.clipped && frame.decor.padding == 0, "decor insets/clipping");
                check(frame.background == paint && content.getBackground() == null, "double opacity");
                check(frame.decor.children.contains(content) && host.view() == frame.decor, "content not borrowed");
                var params = new WindowManager.LayoutParams();
                host.attributes(params);
                check(frame.attributes == params, "placement copied or substituted");
                frame.decor.attach(); host.presented(false);
                check(paint.alpha == 0 && frame.radius == 0, "hidden decoration");
                host.presented(true);
                check(paint.alpha == 255 && frame.radius == 24, "shown decoration");
                check(content.alpha == 1f, "decoration faded content");
                host.close();
                check(content.getParent() == null && content.getBackground() == paint, "content not returned");
                check(frame.decor.listeners.isEmpty() && frame.radius == 0, "decoration retained effect");
                """);
    }

    @Test public void panelDecorationForwardsOutsideTouchesToItsContentOwner() throws Exception {
        verify("""
                var content = new View(); content.setBackground(paint);
                var host = new UiPanelWindow(content);
                var outside = new MotionEvent(MotionEvent.ACTION_OUTSIDE);
                content.handled = true;
                check(host.view().dispatchTouchEvent(outside), "outside was not handled by content");
                check(content.events.size() == 1 && content.events.get(0) == outside,
                        "outside notification was lost or duplicated");
                content.handled = false;
                check(!host.view().dispatchTouchEvent(outside), "unhandled outside was consumed");
                host.view().dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN));
                check(content.events.size() == 2, "ordinary input bypassed normal child dispatch");
                host.close();
                host.view().dispatchTouchEvent(outside);
                check(content.events.size() == 2, "released decor retained input owner");
                """);
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify(STUBS + "\nstatic "
                + RuntimeSourceFixture.nestedClass("UiBackdrop", "UiBackdrop")
                + "\nstatic " + RuntimeSourceFixture.nestedClass("UiPanelWindow", "UiPanelWindow")
                    .replace("android.graphics.Rect", "Rect").replace("android.graphics.drawable.InsetDrawable", "InsetDrawable")
                + "\npublic static void verify() throws Exception {\n"
                + "var window = new Window(); var view = window.decor; var paint = new UiAppearance.Paint();\n"
                + scenario + "\n}");
    }

    private static final String STUBS = """
            static class Rect {
                int left,top,right,bottom;
                void set(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
                void setEmpty(){set(0,0,0,0);}
            }
            static class InsetDrawable {
                final UiAppearance.Paint paint; final int left,top,right,bottom;
                InsetDrawable(UiAppearance.Paint p,int l,int t,int r,int b){paint=p;left=l;top=t;right=r;bottom=b;}
                UiAppearance.Paint paint(){return paint;} int left(){return left;} int top(){return top;} int bottom(){return bottom;}
                boolean getPadding(Rect r){r.set(left,top,right,bottom);return true;}
            }
            static class R {
                static class id { static int appearance_backdrop = 1; }
                static class style { static int DesktopChromeTheme = 2; }
            }
            static class UiAppearance {
                static class Paint {
                    boolean backdrop;
                    int radius = 12, refreshes, alpha = 255;
                    float density = 2;
                    void refresh() { refreshes++; }
                    Paint backdropStyle() { return this; }
                    int blurRadiusDp() { return radius; }
                    void setAlpha(int value) { alpha = value; }
                }
            }
            record MotionEvent(int action) {
                static final int ACTION_OUTSIDE=4, ACTION_DOWN=0;
                int getActionMasked() { return action; }
            }
            static class View {
                interface OnTouchListener { boolean onTouch(View view, MotionEvent event); }
                OnTouchListener touchListener;
                List<MotionEvent> events = new ArrayList<>();
                boolean handled;
                void setOnTouchListener(OnTouchListener value) { touchListener = value; }
                boolean dispatchTouchEvent(MotionEvent event) {
                    events.add(event);
                    return touchListener != null && touchListener.onTouch(this, event) || handled;
                }
                interface OnAttachStateChangeListener {
                    void onViewAttachedToWindow(View view);
                    void onViewDetachedFromWindow(View view);
                }
                final List<OnAttachStateChangeListener> listeners = new ArrayList<>();
                final Map<Integer, Object> tags = new HashMap<>();
                boolean attached, hardware = true, clipped;
                float alpha = 1f;
                int padding = -1;
                View parent;
                Object background;
                Object getContext() { return this; }
                Object getBackground() { return background; }
                void setBackground(Object value) { background = value; }
                View getParent() { return parent; }
                Object getTag(int key) { return tags.get(key); }
                void setTag(int key, Object value) { tags.put(key, value); }
                void setPadding(int a, int b, int c, int d) { padding = a + b + c + d; }
                void setClipToOutline(boolean value) { clipped = value; }
                boolean isAttachedToWindow() { return attached; }
                boolean isHardwareAccelerated() { return hardware; }
                void addOnAttachStateChangeListener(OnAttachStateChangeListener value) { listeners.add(value); }
                void removeOnAttachStateChangeListener(OnAttachStateChangeListener value) { listeners.remove(value); }
                void attach() { attached = true; for (var l : List.copyOf(listeners)) l.onViewAttachedToWindow(this); }
                void detach() { attached = false; for (var l : List.copyOf(listeners)) l.onViewDetachedFromWindow(this); }
            }
            static class ViewGroup extends View {
                final List<View> children = new ArrayList<>();
                boolean clipChildren=true,clipPadding=true;
                void setClipChildren(boolean value){clipChildren=value;} void setClipToPadding(boolean value){clipPadding=value;}
                void removeView(View child) { children.remove(child); child.parent = null; }
            }
            static class WindowManager {
                static class LayoutParams { static final int TYPE_APPLICATION_PANEL = 1000; }
            }
            static class Window {
                final ViewGroup decor = new ViewGroup();
                Object background;
                int radius, writes, type;
                Object callback = new Object();
                boolean reject, fits = true;
                WindowManager.LayoutParams attributes;
                View getDecorView() { return decor; }
                void setCallback(Object value) { callback = value; }
                void setType(int value) { type = value; }
                void setAttributes(WindowManager.LayoutParams value) { attributes = value; }
                void setDecorFitsSystemWindows(boolean value) { fits = value; }
                void setContentView(View value) { decor.children.add(value); value.parent = decor; }
                void setBackgroundDrawable(Object paint) {
                    background = paint;
                    Rect padding=new Rect();
                    if (paint instanceof InsetDrawable i) i.getPadding(padding);
                    decor.setPadding(padding.left,padding.top,padding.right,padding.bottom);
                }
                void setBackgroundBlurRadius(int value) {
                    writes++;
                    if (reject) throw new UnsupportedOperationException();
                    radius = value;
                }
            }
            static class Dialog {
                static Dialog last;
                final Window window = new Window();
                Dialog(Object context, int theme) { last = this; }
                Window getWindow() { return window; }
            }
            """;
}
