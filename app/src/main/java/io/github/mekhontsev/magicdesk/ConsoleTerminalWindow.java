package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.res.ColorStateList;
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
    private static final int COLOR_BACKGROUND = 0xFF090D14;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF94A3B8;
    private static final int COLOR_CYAN = 0xFF22D3EE;
    private static final int COLOR_AMBER = 0xFFF59E0B;

    private final Activity mActivity;
    private final ConsoleTerminalView mTerminalView;
    private final ConsoleTerminalActions mActions;
    private final Runnable mCreateApplication;
    private final View mContent;
    private FrameLayout mTerminalContainer;
    private TextView mShellStatus;
    private ImageButton mSessions, mShowToolbar, mClear, mCopy, mPaste;
    private android.widget.ProgressBar mProgress;
    private LinearLayout mToolbar;
    private LinearLayout.LayoutParams mTerminalParams;
    private boolean mToolbarVisible = true;

    ConsoleTerminalWindow(Activity activity, ConsoleTerminalView view,
            ConsoleTerminalActions actions, Runnable createApplication) {
        mActivity = activity;
        mTerminalView = view;
        mActions = actions;
        mCreateApplication = createApplication;
        mContent = createContentView();
    }

    View content() { return mContent; }
    void toggleToolbar() { setToolbarVisible(!mToolbarVisible); }

    private View createContentView() {
        final LinearLayout page = new LinearLayout(mActivity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(8), dp(6), dp(8), dp(6));
        // Edge-to-edge windows receive IME insets instead of a resized content frame.
        SystemBarInsets.addToPadding(page, true);
        page.setBackgroundColor(COLOR_BACKGROUND);

        mToolbar = new LinearLayout(mActivity);
        mToolbar.setOrientation(LinearLayout.HORIZONTAL);
        mToolbar.setGravity(Gravity.CENTER_VERTICAL);

        mSessions = createIconButton(
                R.drawable.ic_file_new_window, R.string.terminal_sessions,
                view -> TerminalSessionsDialog.show(mActivity));
        mToolbar.addView(mSessions, buttonParams());
        mClear = createIconButton(
                R.drawable.ic_clear_output,
                R.string.console_clear,
                view -> {
                    mActions.clear();
                });
        mToolbar.addView(mClear, buttonParams());
        mCopy = createIconButton(
                R.drawable.ic_file_copy,
                R.string.console_copy_output,
                mActions::showCopyActions);
        mToolbar.addView(mCopy, buttonParams());
        mPaste = createIconButton(
                R.drawable.ic_file_paste,
                R.string.console_paste,
                view -> mActions.pasteClipboard());
        mToolbar.addView(mPaste, buttonParams());
        mToolbar.addView(createIconButton(R.drawable.ic_history,
                R.string.console_commands, view -> mActions.showCommandHistory()), buttonParams());
        mToolbar.addView(createIconButton(R.drawable.ic_notifications,
                R.string.console_notifications, view -> mActions.showNotificationSettings()), buttonParams());
        mToolbar.addView(createIconButton(R.drawable.ic_font_size,
                R.string.console_font_size, view -> ConsoleFontSizeDialog.show(mActivity,
                        R.string.console_font_size, mTerminalView.fontSizeSp(),
                        ConsolePreferences.fontSizeSp(mActivity), mTerminalView::setFontSizeSp)), buttonParams());
        final ImageButton createApplication = createIconButton(
                R.drawable.ic_add,
                R.string.action_new_terminal_application,
                view -> mCreateApplication.run());
        mToolbar.addView(createApplication, buttonParams());
        final ImageButton openFiles = createIconButton(
                R.drawable.ic_folder_open,
                R.string.console_open_working_directory,
                view -> mActions.openSelectedPathOrWorkingDirectory());
        mToolbar.addView(openFiles, buttonParams());
        final ImageButton hideToolbar = createIconButton(
                R.drawable.ic_arrow_up,
                R.string.console_hide_toolbar,
                view -> setToolbarVisible(false));
        mToolbar.addView(hideToolbar, buttonParams());
        mToolbar.setVisibility(mToolbarVisible ? View.VISIBLE : View.GONE);
        final android.widget.HorizontalScrollView toolbarScroll = new android.widget.HorizontalScrollView(mActivity);
        toolbarScroll.setHorizontalScrollBarEnabled(false);
        toolbarScroll.addView(mToolbar);
        page.addView(toolbarScroll, new LinearLayout.LayoutParams(
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
        button.setImageTintList(new ColorStateList(
                new int[][]{
                    new int[]{-android.R.attr.state_enabled},
                    new int[0]
                },
                new int[]{COLOR_MUTED, COLOR_TEXT}));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(mActivity.getString(descriptionResId));
        button.setTooltipText(mActivity.getString(descriptionResId));
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        return new LinearLayout.LayoutParams(dp(44), dp(44));
    }

    private int dp(final int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    void updateMetadata(ConsoleTerminalSession.Metadata data) {
        mProgress.setVisibility(data.progressState() == 0 ? View.INVISIBLE : View.VISIBLE);
        mProgress.setIndeterminate(data.progressState() == 3 || data.progressPercent() < 0);
        if (data.progressPercent() >= 0) { mProgress.setProgress(data.progressPercent()); }
        final int color = data.progressState() == 2 ? 0xFFEF4444
                : data.progressState() == 4 ? COLOR_AMBER : COLOR_CYAN;
        mProgress.setProgressTintList(ColorStateList.valueOf(color));
        mProgress.setIndeterminateTintList(ColorStateList.valueOf(color));
        mProgress.setContentDescription(mActivity.getString(R.string.console_progress, data.progressState(), data.progressPercent()));
    }

    void updateActions(boolean attached, boolean ready) {
        if (mClear == null || mCopy == null || mPaste == null) {
            return;
        }
        mClear.setEnabled(ready);
        mCopy.setEnabled(attached);
        mPaste.setEnabled(ready);
    }

    void updateStatus(DesktopExecBackend mBackend, ShellAccess.Snapshot mSnapshot,
            String mTerminalStatus, boolean mTerminalFailed) {
        if (mShellStatus == null || mSessions == null) return;
        final boolean termux = mBackend == DesktopExecBackend.TERMUX;
        final boolean unavailable = !termux && (mSnapshot == null || !mSnapshot.isReady());
        final boolean root = !termux && !unavailable && mSnapshot.uid == ShellAccess.ROOT_UID;
        final String identity = termux ? mActivity.getString(R.string.console_shell_termux)
                : unavailable ? mActivity.getString(R.string.console_title)
                : mActivity.getString(root ? R.string.console_shell_root : R.string.console_shell_android, mSnapshot.uid);
        final String description = mActivity.getString(R.string.terminal_sessions) + "\n" + identity;
        mSessions.setTooltipText(description);
        mSessions.setContentDescription(description);
        mSessions.setImageTintList(ColorStateList.valueOf(root ? COLOR_AMBER : COLOR_TEXT));

        final String status = unavailable ? mActivity.getString(R.string.console_shell_unavailable,
                mSnapshot == null || mSnapshot.error.isEmpty()
                        ? mActivity.getString(R.string.state_unavailable) : mSnapshot.error) : mTerminalStatus;
        mShellStatus.setText(status);
        mShellStatus.setTextColor(unavailable || mTerminalFailed ? COLOR_AMBER : COLOR_MUTED);
        mShellStatus.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
