package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Android window composition and toolbar state, independent of PTY lifecycle. */
final class ConsoleTerminalWindow {

    private final Activity mActivity;
    private final ConsoleTerminalView mTerminalView;
    private final ConsoleTerminalActions mActions;
    private final Runnable mCreateApplication;
    private final java.util.function.Consumer<android.content.Intent> mSelectSession;
    private final View mContent;
    private FrameLayout mTerminalContainer;
    private TextView mShellStatus;
    private TextView mSessions;
    private String mSessionIdentity = "";
    private ImageButton mShowToolbar;
    private final java.util.List<ImageButton> primary = new java.util.ArrayList<>();
    private boolean attached, ready;
    private TextView mEmpty;
    private android.widget.ProgressBar mProgress;
    private LinearLayout mToolbar;
    private LinearLayout.LayoutParams mTerminalParams;
    private boolean mToolbarVisible = true;

    ConsoleTerminalWindow(Activity activity, ConsoleTerminalView view,
            ConsoleTerminalActions actions, Runnable createApplication,
            java.util.function.Consumer<android.content.Intent> selectSession) {
        mActivity = activity;
        mTerminalView = view;
        mActions = actions;
        mCreateApplication = createApplication;
        mSelectSession = selectSession;
        mContent = createContentView();
    }

    View content() { return mContent; }
    void toggleToolbar() { setToolbarVisible(!mToolbarVisible); }

    private View createContentView() {
        final LinearLayout page = UiToolLayout.page(mActivity, UiColor.BACKGROUND, true, 0, 0);

        mToolbar = new LinearLayout(mActivity);
        mToolbar.setOrientation(LinearLayout.HORIZONTAL);
        mToolbar.setGravity(Gravity.CENTER_VERTICAL);

        mSessions = new TextView(mActivity);
        mSessions.setText(R.string.terminal_sessions);
        mSessions.setTextSize(14);
        mSessions.setGravity(Gravity.CENTER_VERTICAL);
        mSessions.setSingleLine(true);
        mSessions.setFocusable(true);
        mSessions.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mSessions.setPadding(dp(8), 0, dp(8), 0);
        final var arrow = mActivity.getDrawable(R.drawable.ic_arrow_down);
        arrow.setBounds(0, 0, dp(18), dp(18));
        mSessions.setCompoundDrawablesRelative(null, null, arrow, null);
        UiAppearance.compound(mSessions, UiColor.TEXT);
        mSessions.setCompoundDrawablePadding(dp(4));
        mSessions.setOnClickListener(view -> TerminalSessionsDialog.showDropdown(mActivity, view, mSelectSession));
        mToolbar.addView(mSessions, new LinearLayout.LayoutParams(dp(160), dp(48)));
        for (Command command : Command.values()) {
            if (command.icon == 0) continue;
            ImageButton button = createIconButton(command.icon, command.label, view -> run(command, view));
            primary.add(button);
            button.setTag(command);
            mToolbar.addView(button, buttonParams());
        }
        mToolbar.addView(new android.widget.Space(mActivity),new LinearLayout.LayoutParams(0,1,1));
        mToolbar.addView(createIconButton(R.drawable.ic_more, R.string.console_more_actions, this::showMore), buttonParams());
        mToolbar.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            int count = Math.min(primary.size(),primarySlots(r-l, dp(120), dp(48)));
            int width = Math.min(dp(160),Math.max(0,r-l-(count+1)*dp(48)));
            var params=mSessions.getLayoutParams();
            if (params.width!=width) { params.width=width; mSessions.setLayoutParams(params); }
            for (int i=0; i<primary.size(); i++) primary.get(i).setVisibility(i<count ? View.VISIBLE : View.GONE);
        });
        mToolbar.setVisibility(mToolbarVisible ? View.VISIBLE : View.GONE);
        page.addView(mToolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mShellStatus = new TextView(mActivity);
        mShellStatus.setTextSize(12);
        mShellStatus.setPadding(dp(4), dp(4), dp(4), dp(4));
        mShellStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        mShellStatus.setVisibility(View.GONE);
        page.addView(mShellStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mProgress = new android.widget.ProgressBar(mActivity, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setMax(100);
        mProgress.setVisibility(View.INVISIBLE);
        page.addView(mProgress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));

        mTerminalView.setOnDragListener(mActions::handleFileDrop);
        mTerminalContainer = new FrameLayout(mActivity);
        mTerminalContainer.addView(mTerminalView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        mEmpty = new TextView(mActivity);
        mEmpty.setText(R.string.terminal_sessions);
        mEmpty.setTextSize(16);
        mEmpty.setGravity(Gravity.CENTER);
        UiAppearance.text(mEmpty, UiColor.ACCENT);
        mEmpty.setOnClickListener(view -> {
            setToolbarVisible(true);
            mSessions.post(mSessions::performClick);
        });
        final var emptyParams = new FrameLayout.LayoutParams(-2, dp(48), Gravity.CENTER);
        mTerminalContainer.addView(mEmpty, emptyParams);
        mShowToolbar = createIconButton(
                R.drawable.ic_arrow_down,
                R.string.console_show_toolbar,
                view -> setToolbarVisible(true));
        mShowToolbar.setPadding(dp(6), dp(6), dp(6), dp(6));
        mShowToolbar.setVisibility(View.GONE);
        final FrameLayout.LayoutParams showToolbarParams =
                new FrameLayout.LayoutParams(dp(32), dp(32));
        showToolbarParams.gravity = Gravity.TOP | Gravity.END;
        mTerminalContainer.addView(mShowToolbar, showToolbarParams);
        mTerminalParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        mTerminalParams.setMargins(
                0, mToolbarVisible ? dp(4) : 0, 0, 0);
        page.addView(mTerminalContainer, mTerminalParams);
        return page;
    }

    private void setToolbarVisible(final boolean visible) {
        if (mToolbarVisible == visible || mToolbar == null) {
            return;
        }
        mToolbarVisible = visible;
        mToolbar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (mShowToolbar != null) {
            mShowToolbar.setVisibility(visible ? View.GONE : View.VISIBLE);
        }
        if (mTerminalParams != null
                && mTerminalContainer != null
                && mTerminalView != null) {
            mTerminalParams.topMargin = visible ? dp(4) : 0;
            mTerminalContainer.setLayoutParams(mTerminalParams);
            mTerminalView.requestFocus();
        }
    }

    private ImageButton createIconButton(
            final int drawableResId,
            final int descriptionResId,
            final View.OnClickListener listener) {
        final ImageButton button = new ImageButton(mActivity);
        button.setImageResource(drawableResId);
        UiAppearance.imageStates(button, UiColor.TEXT);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(mActivity.getString(descriptionResId));
        button.setTooltipText(mActivity.getString(descriptionResId));
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        return new LinearLayout.LayoutParams(dp(48), dp(48));
    }

    private int dp(final int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    void updateMetadata(ConsoleTerminalSession.Metadata data) {
        mProgress.setVisibility(data.progressState() == 0 ? View.INVISIBLE : View.VISIBLE);
        mProgress.setIndeterminate(data.progressState() == 3 || data.progressPercent() < 0);
        if (data.progressPercent() >= 0) { mProgress.setProgress(data.progressPercent()); }
        final UiColor color = data.progressState() == 2 ? UiColor.DANGER
                : data.progressState() == 4 ? UiColor.ATTENTION : UiColor.ACCENT;
        UiAppearance.progress(mProgress, color);
        mProgress.setContentDescription(mActivity.getString(R.string.console_progress, data.progressState(), data.progressPercent()));
    }

    private enum Command {
        COPY(R.string.console_copy_output,R.drawable.ic_file_copy), PASTE(R.string.console_paste,R.drawable.ic_file_paste),
        ATTACH(R.string.console_session_files,R.drawable.ic_attachment), HISTORY(R.string.console_commands,R.drawable.ic_history),
        FILES(R.string.console_browse_files,R.drawable.ic_folder_open), REDRAW(R.string.console_redraw,0),
        CLEAR(R.string.console_clear_history,0), SHORTCUT(R.string.console_command_shortcut,0),
        FONT(R.string.console_font_size,0), NOTIFICATIONS(R.string.console_notification_settings,0), HIDE(R.string.console_hide_toolbar,0);
        final int label,icon;
        Command(int label,int icon) { this.label=label; this.icon=icon; }
    }
    static int primarySlots(int width,int titleWidth,int buttonWidth) {
        return Math.max(0,(width-titleWidth)/buttonWidth-1); // Reserve the overflow button.
    }
    private boolean enabled(Command command) {
        return switch (command) {
            case COPY, HISTORY, CLEAR -> attached;
            case PASTE, ATTACH, FILES, REDRAW, SHORTCUT -> ready;
            default -> true;
        };
    }
    private void run(Command command,View anchor) {
        if (!enabled(command)) return;
        switch (command) {
            case COPY -> mActions.showCopyActions(anchor);
            case PASTE -> mActions.pasteClipboard();
            case ATTACH -> mActions.showSessionFiles();
            case FILES -> mActions.openSelectedPathOrWorkingDirectory();
            case HISTORY -> mActions.showCommandHistory();
            case CLEAR -> mActions.confirmClearHistory();
            case REDRAW -> mActions.redraw();
            case SHORTCUT -> mCreateApplication.run();
            case FONT -> ConsoleFontSizeDialog.show(mActivity,R.string.console_font_size,mTerminalView.fontSizeSp(),
                    ConsolePreferences.fontSizeSp(mActivity),mTerminalView::setFontSizeSp);
            case NOTIFICATIONS -> mActions.showNotificationSettings();
            case HIDE -> setToolbarVisible(false);
        }
    }
    private void showMore(View anchor) {
        var menu = new android.widget.PopupMenu(mActivity,anchor);
        for (Command command : Command.values()) {
            if (primary.stream().anyMatch(b -> b.getTag()==command && b.getVisibility()==View.VISIBLE)) continue;
            menu.getMenu().add(command.label).setEnabled(enabled(command)).setOnMenuItemClickListener(item -> {
                run(command,anchor); return true;
            });
        }
        menu.show();
    }
    void updateActions(boolean attached, boolean ready) {
        this.attached=attached; this.ready=ready;
        for (var button:primary) button.setEnabled(enabled((Command)button.getTag()));
    }

    void updateSession(String label, boolean attached) {
        mSessions.setText(label);
        updateSessionDescription();
        mEmpty.setVisibility(attached ? View.GONE : View.VISIBLE);
        if (!attached) mProgress.setVisibility(View.INVISIBLE);
    }

    private void updateSessionDescription() {
        String action = mActivity.getString(R.string.terminal_sessions);
        String label = mSessions.getText().toString();
        String description = action + (label.isEmpty() || label.equals(action) ? "" : "\n" + label)
                + (mSessionIdentity.isEmpty() ? "" : "\n" + mSessionIdentity);
        mSessions.setTooltipText(description);
        mSessions.setContentDescription(description);
    }

    void updateStatus(DesktopExecBackend mBackend, ShellAccess.Snapshot mSnapshot,
            String mTerminalStatus, boolean mTerminalFailed) {
        if (mShellStatus == null || mSessions == null) return;
        if (mBackend == null) {
            mShellStatus.setVisibility(View.GONE);
            mSessionIdentity = "";
            updateSessionDescription();
            UiAppearance.text(mSessions, UiColor.TEXT);
            return;
        }
        final boolean termux = mBackend == DesktopExecBackend.TERMUX;
        final boolean shell = mBackend == DesktopExecBackend.SHELL;
        final boolean unavailable = shell && (mSnapshot == null || !mSnapshot.isReady());
        final boolean root = shell && !unavailable && mSnapshot.uid == ShellAccess.ROOT_UID;
        final String identity = mBackend == DesktopExecBackend.APP ? mActivity.getString(R.string.console_app_title)
                : termux ? mActivity.getString(R.string.console_shell_termux)
                : unavailable ? mActivity.getString(R.string.console_title)
                : mActivity.getString(root ? R.string.console_shell_root : R.string.console_shell_android, mSnapshot.uid);
        mSessionIdentity = identity;
        updateSessionDescription();
        UiAppearance.text(mSessions, root ? UiColor.ATTENTION : UiColor.TEXT);

        final String status = unavailable ? mActivity.getString(R.string.console_shell_unavailable,
                mSnapshot == null || mSnapshot.error.isEmpty()
                        ? mActivity.getString(R.string.state_unavailable) : mSnapshot.error) : mTerminalStatus;
        mShellStatus.setText(status);
        UiAppearance.text(mShellStatus, unavailable || mTerminalFailed ? UiColor.ATTENTION : UiColor.MUTED);
        mShellStatus.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
