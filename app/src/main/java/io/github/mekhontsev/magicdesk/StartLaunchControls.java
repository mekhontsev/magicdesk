package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

/** Start-local launch choices; no session or input selection is changed here. */
final class StartLaunchControls {
    enum Mode {
        DEFAULT("auto", DesktopLaunchMode.AUTO, R.string.start_mode_default),
        WINDOWED("desktop", DesktopLaunchMode.WINDOWED, R.string.start_mode_windowed),
        FULLSCREEN("desktop", DesktopLaunchMode.FULLSCREEN, R.string.start_mode_fullscreen),
        INDEPENDENT("display", DesktopLaunchMode.FULLSCREEN, R.string.start_mode_independent);

        final String placement;
        final DesktopLaunchMode windowMode;
        final int label;
        Mode(String placement, DesktopLaunchMode windowMode, int label) {
            this.placement = placement;
            this.windowMode = windowMode;
            this.label = label;
        }
        boolean available(boolean desktop) { return !placement.equals("desktop") || desktop; }
    }

    private final LinearLayout mView;
    private final StartDisplaySelector mDisplay;
    private final CheckBox mNew;
    private final Button mModeButton;
    private Mode mMode = Mode.DEFAULT;
    private PopupMenu mMenu;

    StartLaunchControls(Activity activity, DesktopUiFactory ui, DesktopAutomationUiRegistry automation, Runnable changed) {
        mDisplay = new StartDisplaySelector(activity, ui, () -> { refresh(); changed.run(); });
        mView = new LinearLayout(activity);
        mView.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mView.addView(mDisplay.view(), new LinearLayout.LayoutParams(0, ui.dp(52), 1));
        final Button mode = ui.actionButton(mMode.label, DesktopUiFactory.COLOR_PANEL_ALT);
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
            mMenu.show();
        });
        final var modeParams = new LinearLayout.LayoutParams(0, ui.dp(52), 1);
        modeParams.setMarginStart(ui.dp(4));
        mView.addView(mode, modeParams);
        mNew = new CheckBox(activity);
        mNew.setText(R.string.start_new_window);
        mNew.setTextColor(DesktopUiFactory.COLOR_TEXT);
        mNew.setTextSize(12);
        mNew.setMaxLines(2);
        mView.addView(mNew, new LinearLayout.LayoutParams(ui.dp(90), ui.dp(52)));
        automation.register(mDisplay.view(), "start.display", "button", activity.getString(R.string.display_selector));
        automation.register(mode, "start.mode", "button", activity.getString(R.string.start_launch_mode));
        automation.register(mNew, "start.new_window", "checkbox", activity.getString(R.string.start_new_window));
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
        return DesktopLaunchPresentation.forMode(mMode.windowMode).withInstancePolicy(mNew.isChecked()
                ? DesktopTaskInstancePolicy.CREATE_NEW : DesktopTaskInstancePolicy.REUSE_EXISTING);
    }
    void dismiss() {
        mDisplay.dismiss();
        if (mMenu != null) { mMenu.dismiss(); mMenu = null; }
    }
}
