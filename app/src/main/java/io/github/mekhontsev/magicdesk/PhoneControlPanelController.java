package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_BACKGROUND;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_CYAN;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_MUTED;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_PANEL_ALT;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_RED;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_TEXT;

import android.app.Activity;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class PhoneControlPanelController {
    interface Actions {
        void openDesktopHere();

        void toggleConsoleMode();

        void openTouchpad();

        void togglePhoneScreen();

        void openDeviceSetup();

        void openDiagnostics();

        void exitMagicDesk();
    }

    static final class State {
        final boolean consoleActive;
        final boolean consoleControlAvailable;
        final boolean phoneScreenOff;
        final boolean phoneScreenControlAvailable;
        final String status;
        final String runtime;
        final int currentDisplayId;
        final int consoleDisplayId;

        State(
                final boolean consoleActive,
                final boolean consoleControlAvailable,
                final boolean phoneScreenOff,
                final boolean phoneScreenControlAvailable,
                final String status,
                final String runtime,
                final int currentDisplayId,
                final int consoleDisplayId) {
            this.consoleActive = consoleActive;
            this.consoleControlAvailable = consoleControlAvailable;
            this.phoneScreenOff = phoneScreenOff;
            this.phoneScreenControlAvailable = phoneScreenControlAvailable;
            this.status = status;
            this.runtime = runtime;
            this.currentDisplayId = currentDisplayId;
            this.consoleDisplayId = consoleDisplayId;
        }
    }

    private static final int ACTION_HEIGHT_DP = 52;

    private final Activity mActivity;
    private final DesktopUiFactory mUi;
    private final Actions mActions;

    private TextView mStatus;
    private TextView mRuntime;
    private TextView mDisplay;
    private Button mConsoleMode;
    private Button mTouchpad;
    private Button mPhoneScreen;

    PhoneControlPanelController(
            final Activity activity,
            final DesktopUiFactory ui,
            final Actions actions) {
        mActivity = activity;
        mUi = ui;
        mActions = actions;
    }

    View createView() {
        final LinearLayout page = new LinearLayout(mActivity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(COLOR_BACKGROUND);
        final int horizontalPadding = dp(18);
        final int topPadding = dp(16);
        final int bottomPadding = dp(16);
        page.setPadding(
                horizontalPadding,
                topPadding,
                horizontalPadding,
                bottomPadding);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            final Insets systemBars = insets.getInsets(
                    WindowInsets.Type.systemBars());
            view.setPadding(
                    horizontalPadding,
                    topPadding + systemBars.top,
                    horizontalPadding,
                    bottomPadding + systemBars.bottom);
            return insets;
        });

        page.addView(createHeader(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(18), 0, 0);

        final LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        addStatus(content);
        addDesktopActions(content);
        addSystemActions(content);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1));
        return page;
    }

    void render(final State state) {
        mStatus.setText(state.status);
        mRuntime.setText(mActivity.getString(
                R.string.control_runtime_status, state.runtime));
        final String consoleDisplay = state.consoleActive
                ? Integer.toString(state.consoleDisplayId)
                : mActivity.getString(R.string.state_off);
        mDisplay.setText(mActivity.getString(
                R.string.control_display_status,
                Integer.valueOf(state.currentDisplayId),
                consoleDisplay));

        mConsoleMode.setText(state.consoleActive
                ? R.string.action_switch_to_mirror
                : R.string.action_start_console_mode);
        mConsoleMode.setEnabled(state.consoleControlAvailable);
        mTouchpad.setEnabled(
                state.consoleActive && state.consoleControlAvailable);
        mPhoneScreen.setText(state.phoneScreenOff
                ? R.string.action_phone_screen_on
                : R.string.action_phone_screen_off);
        mPhoneScreen.setEnabled(
                state.consoleActive
                        && state.phoneScreenControlAvailable);
    }

    private View createHeader() {
        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final ImageView icon = new ImageView(mActivity);
        icon.setImageResource(R.drawable.ic_magicdesk);
        header.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        final LinearLayout titleBlock = new LinearLayout(mActivity);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(dp(12), 0, 0, 0);

        final TextView title = new TextView(mActivity);
        title.setText(R.string.app_name);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleBlock.addView(title);

        final TextView subtitle = new TextView(mActivity);
        subtitle.setText(R.string.control_panel_subtitle);
        subtitle.setTextColor(COLOR_MUTED);
        subtitle.setTextSize(13);
        titleBlock.addView(subtitle);

        header.addView(titleBlock, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1));
        return header;
    }

    private void addStatus(final LinearLayout parent) {
        mStatus = statusText(COLOR_CYAN, 15, true);
        parent.addView(mStatus);

        mRuntime = statusText(COLOR_TEXT, 13, false);
        final LinearLayout.LayoutParams runtimeParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        runtimeParams.setMargins(0, dp(7), 0, 0);
        parent.addView(mRuntime, runtimeParams);

        mDisplay = statusText(COLOR_MUTED, 13, false);
        final LinearLayout.LayoutParams displayParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        displayParams.setMargins(0, dp(3), 0, 0);
        parent.addView(mDisplay, displayParams);
    }

    private void addDesktopActions(final LinearLayout parent) {
        addSectionTitle(parent, R.string.control_section_desktop, dp(22));

        final Button desktopHere = actionButton(
                R.string.action_desktop_this_screen, COLOR_CYAN);
        desktopHere.setOnClickListener(view -> mActions.openDesktopHere());
        parent.addView(desktopHere, fullWidthActionParams());

        final GridLayout actions = actionGrid();
        mConsoleMode = actionButton(
                R.string.action_start_console_mode, COLOR_CYAN);
        mConsoleMode.setOnClickListener(view -> mActions.toggleConsoleMode());
        addGridAction(actions, mConsoleMode);

        mTouchpad = actionButton(
                R.string.action_open_touchpad, COLOR_CYAN);
        mTouchpad.setOnClickListener(view -> mActions.openTouchpad());
        addGridAction(actions, mTouchpad);

        mPhoneScreen = actionButton(
                R.string.action_phone_screen_off, COLOR_CYAN);
        mPhoneScreen.setOnClickListener(view -> mActions.togglePhoneScreen());
        addGridAction(actions, mPhoneScreen);
        parent.addView(actions, fullWidthWrapParams(dp(6)));
    }

    private void addSystemActions(final LinearLayout parent) {
        addSectionTitle(parent, R.string.control_section_system, dp(20));
        final GridLayout actions = actionGrid();

        final Button setup = actionButton(
                R.string.action_device_setup, COLOR_PANEL_ALT);
        setup.setOnClickListener(view -> mActions.openDeviceSetup());
        addGridAction(actions, setup);

        final Button diagnostics = actionButton(
                R.string.action_diagnostics, COLOR_PANEL_ALT);
        diagnostics.setOnClickListener(view -> mActions.openDiagnostics());
        addGridAction(actions, diagnostics);

        final Button exit = actionButton(R.string.action_exit, COLOR_RED);
        exit.setOnClickListener(view -> mActions.exitMagicDesk());
        addGridAction(actions, exit);
        parent.addView(actions, fullWidthWrapParams(0));
    }

    private void addSectionTitle(
            final LinearLayout parent,
            final int titleResId,
            final int topMargin) {
        final TextView title = mUi.sectionTitle(titleResId);
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, topMargin, 0, dp(8));
        parent.addView(title, params);
    }

    private TextView statusText(
            final int color, final int size, final boolean bold) {
        final TextView text = new TextView(mActivity);
        text.setTextColor(color);
        text.setTextSize(size);
        if (bold) {
            text.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return text;
    }

    private GridLayout actionGrid() {
        final GridLayout grid = new GridLayout(mActivity);
        grid.setColumnCount(2);
        return grid;
    }

    private Button actionButton(final int textResId, final int color) {
        final Button button = mUi.actionButton(textResId, color);
        button.setSingleLine(false);
        button.setMaxLines(2);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        return button;
    }

    private void addGridAction(final GridLayout grid, final Button button) {
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(ACTION_HEIGHT_DP);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(button, params);
    }

    private LinearLayout.LayoutParams fullWidthActionParams() {
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(ACTION_HEIGHT_DP));
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
    }

    private LinearLayout.LayoutParams fullWidthWrapParams(
            final int topMargin) {
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, topMargin, 0, 0);
        return params;
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
