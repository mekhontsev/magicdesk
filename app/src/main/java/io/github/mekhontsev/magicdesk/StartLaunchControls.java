package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

/** Start-local launch choices; no session or input selection is changed here. */
final class StartLaunchControls {
    static void addMenuItems(ApplicationContextMenu menu, StartDisplaySelector.Target target,
            DesktopLaunchPresentation selected,
            java.util.function.BiConsumer<StartDisplaySelector.Target, DesktopLaunchPresentation> launch) {
        menu.add(R.string.action_open, () -> launch.accept(target, menu.options.apply(selected)));
        menu.add(R.string.action_open_fullscreen, () -> launch.accept(target,
                menu.options.apply(DesktopLaunchPresentation.forMode(DesktopLaunchMode.FULLSCREEN))));
        menu.add(R.string.action_open_floating,
                Mode.WINDOWED.available(DesktopRuntimeBridge.hasWorkspace(target.displayId())),
                () -> launch.accept(new StartDisplaySelector.Target(target.displayId(), target.uniqueId(), "auto"),
                        menu.options.apply(DesktopLaunchPresentation.forMode(DesktopLaunchMode.WINDOWED))));
    }

    enum Mode {
        DEFAULT("auto", DesktopLaunchMode.AUTO, R.string.start_mode_default),
        WINDOWED("auto", DesktopLaunchMode.WINDOWED, R.string.start_mode_windowed),
        FULLSCREEN("auto", DesktopLaunchMode.FULLSCREEN, R.string.start_mode_fullscreen),
        INDEPENDENT("display", DesktopLaunchMode.FULLSCREEN, R.string.start_mode_independent);

        final String placement;
        final DesktopLaunchMode windowMode;
        final int label;
        Mode(String placement, DesktopLaunchMode windowMode, int label) {
            this.placement = placement;
            this.windowMode = windowMode;
            this.label = label;
        }
        boolean available(boolean desktop) {
            return this != WINDOWED || desktop || OrdinaryActivityLaunch.canLaunchWindowed();
        }
    }

    private final LinearLayout mView;
    private final StartDisplaySelector mDisplay;
    private final Button mModeButton;
    private Mode mMode = Mode.DEFAULT;
    private PopupMenu mMenu;

    StartLaunchControls(Activity activity, DesktopUiFactory ui, DesktopAutomationUiRegistry automation, Runnable changed) {
        mDisplay = new StartDisplaySelector(activity, ui, () -> { refresh(); changed.run(); });
        mView = new LinearLayout(activity);
        mView.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View view) { }
            @Override public void onViewDetachedFromWindow(android.view.View view) { dismiss(); }
        });
        mView.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mView.addView(mDisplay.view(), new LinearLayout.LayoutParams(0, ui.dp(52), 1));
        final Button mode = ui.actionButton(mMode.label, UiColor.SURFACE);
        mModeButton = mode;
        mode.setTextSize(12);
        mode.setMinWidth(0);
        mode.setMinimumWidth(0);
        mode.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        mode.setSingleLine(false);
        mode.setMaxLines(2);
        mode.setOnClickListener(v -> {
            if (mMenu != null) { mMenu.dismiss(); }
            mMenu = new PopupMenu(activity, mode);
            final boolean desktop = DesktopRuntimeBridge.hasWorkspace(mDisplay.target().displayId());
            for (final Mode candidate : Mode.values()) {
                mMenu.getMenu().add(candidate.label).setCheckable(true).setChecked(mMode == candidate)
                        .setEnabled(candidate.available(desktop)).setOnMenuItemClickListener(item -> {
                            mMode = candidate;
                            mode.setText(candidate.label);
                            changed.run();
                            return true;
                        });
            }
            mMenu.getMenu().setGroupCheckable(0, true, true);
            mMenu.show();
        });
        final var modeParams = new LinearLayout.LayoutParams(0, ui.dp(52), 1);
        modeParams.setMarginStart(ui.dp(4));
        mView.addView(mode, modeParams);
        automation.register(mDisplay.view(), "start.display", "button", activity.getString(R.string.display_selector));
        automation.register(mode, "start.mode", "button", activity.getString(R.string.start_launch_mode));
    }

    LinearLayout view() { return mView; }
    void refresh() {
        if (!mMode.available(DesktopRuntimeBridge.hasWorkspace(mDisplay.target().displayId()))) {
            mMode = Mode.DEFAULT;
            mModeButton.setText(mMode.label);
        }
    }
    RecentLaunchScope recentScope() {
        // A disappearing workspace must not make a read-only history query fail.
        return RecentLaunchScope.of(ToolLaunchTarget.resolve(mMode == Mode.INDEPENDENT ? "display" : "auto",
                mDisplay.target().displayId(), DesktopRuntimeBridge.workspaceDisplayIds()));
    }
    StartDisplaySelector.Target target() {
        final var target = mDisplay.target();
        return new StartDisplaySelector.Target(target.displayId(), target.uniqueId(), mMode.placement);
    }
    DesktopLaunchPresentation presentation() {
        return DesktopLaunchPresentation.forMode(mMode.windowMode);
    }
    void dismiss() {
        mDisplay.dismiss();
        if (mMenu != null) { mMenu.dismiss(); mMenu = null; }
    }
}
