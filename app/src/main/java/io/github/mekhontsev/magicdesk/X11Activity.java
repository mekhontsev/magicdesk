package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.mekhontsev.magicdesk.x11.X11Session;

/** An ordinary Android window onto a retained X server or one selected X client window. */
public final class X11Activity extends Activity implements
        BuiltInWindowRegistry.PresentationSource, BuiltInWindowRegistry.ImmersiveSource,
        BuiltInWindowRegistry.CloseHandler, BuiltInWindowRegistry.ApplicationSource,
        BuiltInWindowRegistry.DesktopPresentationListener, HostedWindowPresentation.ContentHost {
    @Override public long hostedWindowId() { return window; }
    @Override public HostedSurfaceView hostedSurface() { return surface; }
    @Override public boolean wholeDesktopViewer() { return !application && window == 0; }
    static final String SESSION = "x11_session";
    static final String WINDOW = "x11_window";
    static final String APPLICATION = "x11_application";
    private X11Sessions.Session session;
    private volatile X11HostBinding binding;
    private HostedSurfaceView surface;
    private HostedContentLayout content;
    private TextView status;
    private long window;
    private boolean seenWindow;
    private boolean application;
    private boolean detached;
    private volatile BuiltInWindowRegistry.Presentation presentation;
    private RecentApplicationStore.Entry identityRecipe;
    private volatile AppReference windowApplication;

    static Intent createIntent(Context context) { return new Intent(context, X11Activity.class); }

    static Intent windowIntent(Context context, X11Sessions.Session session, long window) {
        return BuiltInWindowIdentity.bind(createIntent(context).putExtra(SESSION, session.id()).putExtra(WINDOW, window)
                .putExtra(APPLICATION, session.application && window != 0),
                GraphicalApplicationLaunch.reference(context, session.windowRecipe(window)));
    }

    @Override public void onCreate(Bundle state) {
        HostedStartupWindow.prepare(this);
        super.onCreate(state);
        BuiltInWindowRegistry.register(this);
        DesktopTaskDescription.apply(this, R.string.x11_title, R.drawable.ic_show_desktop);
        DesktopUiFactory ui = new DesktopUiFactory(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        UiAppearance.background(root, UiColor.BACKGROUND);
        SystemBarInsets.addToPadding(root, true, this);
        status = new TextView(this);
        UiAppearance.text(status, UiColor.MUTED);
        status.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        root.addView(status);
        surface = new HostedSurfaceView(this);
        content = new HostedContentLayout(this, surface);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        HostedStartupWindow.layout(this);
        window = state == null ? getIntent().getLongExtra(WINDOW, 0) : state.getLong(WINDOW);
        String id = state == null ? getIntent().getStringExtra(SESSION) : state.getString(SESSION);
        application = state == null ? getIntent().getBooleanExtra(APPLICATION, false) : state.getBoolean(APPLICATION);
        select(X11Sessions.find(id));
    }

    private void select(X11Sessions.Session next) {
        if (binding != null) binding.close(false);
        binding = null;
        session = next;
        seenWindow = false;
        if (session != null) {
            session.presentation.host(this);
            binding = new X11HostBinding(this, surface, session, window, application, this::onChanged);
        }
        onChanged();
    }

    private void onChanged() {
        if (isDestroyed() || isFinishing()) return;
        if (session != null && session.presentation.isClosed()) { finish(); return; }
        boolean ready = session != null && session.state() == X11Sessions.State.READY;
        if (ready && window != 0) {
            session.claimWindow(window);
        }
        content.constraints(session == null ? io.github.mekhontsev.magicdesk.hosted.HostedWindowConstraints.NONE
                : session.layout(window).constraints(), 1);
        var currentRecipe = session == null || HostedStartupWindow.temporary(this) ? null : session.windowRecipe(window);
        if (currentRecipe != identityRecipe) {
            identityRecipe = currentRecipe;
            windowApplication = GraphicalApplicationLaunch.reference(this, currentRecipe);
            DesktopRuntimeBridge.refreshTaskPresentations();
        }
        if (application && session != null && session.state() == X11Sessions.State.CLOSED) { finish(); return; }
        if (window == 0 && session != null) {
            present(session.name, null);
        }
        if (window != 0) {
            if (session == null || session.state() == X11Sessions.State.CLOSED
                    || session.state() == X11Sessions.State.FAILED) { finish(); return; }
            X11Session.Window info = session.windows().stream().filter(item -> item.id() == window).findFirst().orElse(null);
            if (HostedStartupWindow.temporary(this) && (info == null || !info.mapped()
                    || info.applicationWindow())) {
                detachHostedWindow(); return;
            }
            if (info != null) {
                seenWindow = true;
                String title = info.title().isBlank() ? session.name : info.title();
                present(title, DesktopApplicationIconResolver.hostedIcon(info.icon(), currentRecipe));
            } else if (seenWindow || ready) { finish(); return; }
        }
        status.setText(session == null ? getString(R.string.x11_no_session)
                : !session.error().isEmpty() ? session.error()
                : ready && application && window == 0 ? getString(R.string.x11_waiting_application) : session.state().name());
        status.setVisibility(ready && session.error().isEmpty() && (!application || window != 0) ? View.GONE : View.VISIBLE);
        if (binding != null) {
            try {
                binding.refresh(window, ready && (!application || window != 0));
            } catch (RuntimeException error) { status.setText(ShellAccess.usefulMessage(error)); status.setVisibility(View.VISIBLE); }
        }
    }

    @Override public BuiltInWindowRegistry.ImmersiveRequest immersiveRequest() {
        X11HostBinding current = binding;
        return current == null ? null : current.immersiveRequest();
    }

    @Override public void onImmersiveRejected() {
        if (binding != null) binding.rejectImmersive();
    }

    @Override public BuiltInWindowRegistry.Presentation taskPresentation() {
        return presentation == null ? null : new BuiltInWindowRegistry.Presentation(presentation.title(), presentation.icon(),
                binding != null && binding.attention());
    }
    @Override protected void onStart() { super.onStart(); if (binding != null) binding.visible(true); }
    @Override protected void onStop() { if (binding != null) binding.visible(false); super.onStop(); }
    @Override public AppReference windowApplication() { return windowApplication; }
    @Override public void desktopPresentationChanged() { if (binding != null) binding.presentationChanged(); }

    private void present(String title, Bitmap icon) {
        if (presentation != null && presentation.title().equals(title) && presentation.icon() == icon) return;
        presentation = new BuiltInWindowRegistry.Presentation(title, icon);
        setTitle(title);
        if (icon == null) DesktopTaskDescription.apply(this, title, R.drawable.ic_show_desktop);
        else DesktopTaskDescription.apply(this, title, icon);
        DesktopRuntimeBridge.refreshTaskPresentations();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (binding != null) binding.focusChanged(focused);
        if (focused && session != null) session.presentation.host(this);
        if (focused && surface != null) onChanged();
        else if (binding != null) binding.updateExchangeFocus();
        if (binding != null) binding.presentationChanged();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        HostedStartupWindow.layout(this);
        if (binding != null) { binding.updateDensity(); binding.presentationChanged(); }
        if (session != null) session.presentation.host(this);
    }

    @Override public void onMultiWindowModeChanged(boolean multiWindow, android.content.res.Configuration configuration) {
        super.onMultiWindowModeChanged(multiWindow, configuration);
        if (binding != null) binding.presentationChanged();
    }

    static void openWindow(Activity source, X11Sessions.Session selected, long id) {
        selected.claimWindow(id);
        ToolApplications.openSibling(source, windowIntent(source, selected, id),
                error -> {
                    if (error != null) {
                        UiDialogs.builder(source).setMessage(ShellAccess.usefulMessage(error))
                                .setPositiveButton(android.R.string.ok, null).show();
                    }
                });
    }

    @Override public void detachHostedWindow() { detached = true; finishAndRemoveTask(); }
    @Override public void requestClose(boolean force) {
        if (binding != null && binding.requestClose(force)) return;
        finishAndRemoveTask();
    }

    @Override public BuiltInWindowRegistry.ForceCloseAction forceCloseAction() {
        return new BuiltInWindowRegistry.ForceCloseAction(
                window == 0 ? R.string.x11_stop_session_action : R.string.action_force_stop,
                getString(window == 0 ? R.string.x11_force_stop_session : R.string.x11_force_stop_client));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (session != null) state.putString(SESSION, session.id());
        state.putLong(WINDOW, window);
        state.putBoolean(APPLICATION, application);
    }

    @Override public void onDestroy() {
        if (binding != null) binding.close(isFinishing() && !detached);
        binding = null;
        BuiltInWindowRegistry.unregister(this);
        super.onDestroy();
    }
}
