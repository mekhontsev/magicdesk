package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_BACKGROUND;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_MUTED;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_RED;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_TEXT;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class PhoneControlPanelController {
    interface Actions extends DisplaySelectionView.Actions {

        void connectWirelessDisplay();

        void closeDesktop();

        void openTouchpad();

        void togglePhoneScreen();

        void openSettings();

        void openTool(String name, boolean selectedScreen);

        void exitMagicDesk();
    }

    static final class State {
        final DesktopDisplayInfo[] displays;
        final String selectedDisplayUniqueId;
        final int activeDisplayId;
        final boolean displayOperation;
        final boolean desktopSessionActive;
        final boolean sessionOperationInProgress;
        final boolean externalDesktopActive;
        final boolean shellReady;
        final boolean phoneScreenOff;
        final boolean phoneScreenControlAvailable;
        final boolean externalOutputControlAvailable;
        final PlatformProjectionDriver.ModeSelection externalModeSelection;
        final boolean wirelessConnectionUiAvailable;
        final boolean wirelessDisplayConnected;
        final String status;
        final String runtime;

        State(
                final DesktopDisplayInfo[] displays,
                final String selectedDisplayUniqueId,
                final int activeDisplayId,
                final boolean displayOperation,
                final boolean desktopSessionActive,
                final boolean sessionOperationInProgress,
                final boolean externalDesktopActive,
                final boolean shellReady,
                final boolean phoneScreenOff,
                final boolean phoneScreenControlAvailable,
                final boolean externalOutputControlAvailable,
                final PlatformProjectionDriver.ModeSelection externalModeSelection,
                final boolean wirelessConnectionUiAvailable,
                final boolean wirelessDisplayConnected,
                final String status,
                final String runtime) {
            this.displays = displays;
            this.selectedDisplayUniqueId = selectedDisplayUniqueId;
            this.activeDisplayId = activeDisplayId;
            this.displayOperation = displayOperation;
            this.desktopSessionActive = desktopSessionActive;
            this.sessionOperationInProgress = sessionOperationInProgress;
            this.externalDesktopActive = externalDesktopActive;
            this.shellReady = shellReady;
            this.phoneScreenOff = phoneScreenOff;
            this.phoneScreenControlAvailable = phoneScreenControlAvailable;
            this.externalOutputControlAvailable =
                    externalOutputControlAvailable;
            this.externalModeSelection = externalModeSelection;
            this.wirelessConnectionUiAvailable =
                    wirelessConnectionUiAvailable;
            this.wirelessDisplayConnected = wirelessDisplayConnected;
            this.status = status;
            this.runtime = runtime;
        }
    }

    private static final int ACTION_HEIGHT_DP = 52;

    private final Activity mActivity;
    private final DesktopUiFactory mUi;
    private final Actions mActions;

    private TextView mStatus;
    private TextView mRuntime;
    private TextView mDisplay;
    private Button mConnectWirelessDisplay;
    private DisplaySelectionView mDisplaySelection;
    private Button mCloseDesktop;
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
        page.setPadding(
                dp(18),
                dp(16),
                dp(18),
                dp(16));
        SystemBarInsets.addToPadding(page);

        page.addView(centered(createHeader()));

        final ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(8), 0, 0);

        final LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        addStatus(content);
        addDesktopActions(content);
        addToolActions(content);
        addSystemActions(content);
        scroll.addView(centered(content), new ScrollView.LayoutParams(
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
        final String desktopDisplay = state.activeDisplayId >= 0
                ? desktopDisplayLabel(state.displays, state.activeDisplayId)
                : mActivity.getString(R.string.state_off);
        mDisplay.setText(mActivity.getString(
                R.string.control_display_status, desktopDisplay));
        mDisplaySelection.render(state.displays, state.selectedDisplayUniqueId,
                state.activeDisplayId, state.shellReady,
                state.sessionOperationInProgress || state.displayOperation,
                state.externalOutputControlAvailable, state.externalModeSelection);
        mConnectWirelessDisplay.setEnabled(state.wirelessConnectionUiAvailable
                && !state.desktopSessionActive && !state.wirelessDisplayConnected
                && !state.displayOperation);
        final boolean canCloseDesktop = canCloseDesktop(
                state.desktopSessionActive,
                state.shellReady,
                state.sessionOperationInProgress);
        final boolean canOpenTouchpad = state.externalDesktopActive
                && state.shellReady;
        final boolean canControlPhoneScreen = state.externalDesktopActive
                && state.phoneScreenControlAvailable;
        mCloseDesktop.setEnabled(canCloseDesktop);
        mTouchpad.setEnabled(canOpenTouchpad);
        mPhoneScreen.setText(state.phoneScreenOff
                ? R.string.action_phone_screen_on
                : R.string.action_phone_screen_off);
        mUi.setControlIcon(mPhoneScreen, state.phoneScreenOff
                ? R.drawable.ic_phone_screen_on : R.drawable.ic_phone_screen_off);
        mPhoneScreen.setEnabled(canControlPhoneScreen);
    }

    static String desktopDisplayLabel(final DesktopDisplayInfo[] displays, final int activeId) {
        for (final DesktopDisplayInfo display : displays) {
            if (display.id == activeId) { return display.name + " [" + activeId + "]"; }
        }
        return Integer.toString(activeId);
    }

    static boolean canCloseDesktop(
            final boolean desktopSessionActive,
            final boolean shellReady,
            final boolean operationInProgress) {
        return desktopSessionActive && shellReady && !operationInProgress;
    }

    private View createHeader() {
        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final ImageView icon = new ImageView(mActivity);
        icon.setImageResource(R.drawable.ic_magicdesk);
        header.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

        final LinearLayout titleBlock = new LinearLayout(mActivity);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(dp(12), 0, 0, 0);

        final TextView title = new TextView(mActivity);
        title.setText(R.string.app_name);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleBlock.addView(title);

        final TextView subtitle = new TextView(mActivity);
        subtitle.setText(BuildConfig.VERSION_NAME);
        subtitle.setTextColor(COLOR_MUTED);
        subtitle.setTextSize(13);
        titleBlock.addView(subtitle);

        header.addView(titleBlock, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1));
        final ImageButton settings = mUi.menuIconButton(
                R.drawable.ic_settings, R.string.action_settings);
        settings.setOnClickListener(view -> mActions.openSettings());
        header.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return header;
    }

    private void addStatus(final LinearLayout parent) {
        mStatus = statusText(COLOR_TEXT, 14, true);
        parent.addView(mStatus);

        mRuntime = statusText(COLOR_MUTED, 12, false);
        final LinearLayout.LayoutParams runtimeParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        runtimeParams.setMargins(0, dp(7), 0, 0);
        parent.addView(mRuntime, runtimeParams);

    }

    private void addDesktopActions(final LinearLayout parent) {
        mUi.addControlSection(parent, R.string.control_section_desktop, dp(16));
        mDisplay = statusText(COLOR_MUTED, 13, false);
        final LinearLayout.LayoutParams displayParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        displayParams.setMargins(0, 0, 0, dp(8));
        parent.addView(mDisplay, displayParams);
        mDisplaySelection = new DisplaySelectionView(mActivity, mUi, mActions, parent);
        final GridLayout sessionActions = actionGrid();
        mTouchpad = mUi.controlAction(
                R.string.action_open_touchpad, R.drawable.ic_touchpad, COLOR_TEXT);
        mTouchpad.setOnClickListener(view -> mActions.openTouchpad());
        addGridAction(sessionActions, mTouchpad);

        mPhoneScreen = mUi.controlAction(
                R.string.action_phone_screen_off, R.drawable.ic_phone_screen_off, COLOR_TEXT);
        mPhoneScreen.setOnClickListener(view -> mActions.togglePhoneScreen());
        addGridAction(sessionActions, mPhoneScreen);
        mCloseDesktop = mUi.controlAction(
                R.string.action_close_desktop, R.drawable.ic_close, COLOR_TEXT);
        mCloseDesktop.setOnClickListener(view -> mActions.closeDesktop());
        addGridAction(sessionActions, mCloseDesktop);

        mConnectWirelessDisplay = mUi.controlAction(
                R.string.action_connect_wireless_display, R.drawable.ic_cast, COLOR_TEXT);
        mConnectWirelessDisplay.setOnClickListener(view -> mActions.connectWirelessDisplay());
        addGridAction(sessionActions, mConnectWirelessDisplay);
        parent.addView(sessionActions, fullWidthWrapParams(dp(4)));
    }

    private void addToolActions(final LinearLayout parent) {
        mUi.addControlSection(parent, R.string.control_section_tools, dp(16));
        final android.widget.CheckBox selected = new android.widget.CheckBox(mActivity);
        selected.setText(R.string.tools_selected_display);
        selected.setTextColor(COLOR_TEXT);
        selected.setTextSize(13);
        selected.setMinHeight(dp(44));
        parent.addView(selected);
        final GridLayout grid = actionGrid();
        final String[] names = {"files", "sessions"};
        final int[] labels = {R.string.file_manager_title, R.string.terminal_sessions};
        final int[] icons = {R.drawable.ic_desktop_folder, R.drawable.ic_file_new_window};
        for (int i = 0; i < names.length; i++) {
            final String name = names[i];
            final Button button = mUi.controlAction(labels[i], icons[i], COLOR_TEXT);
            button.setOnClickListener(view -> mActions.openTool(name, selected.isChecked()));
            addGridAction(grid, button);
        }
        parent.addView(grid, fullWidthWrapParams(dp(6)));
    }


    private void addSystemActions(final LinearLayout parent) {
        final Button exit = mUi.controlAction(R.string.action_exit, R.drawable.ic_exit, COLOR_RED);
        exit.setOnClickListener(view -> mActions.exitMagicDesk());
        parent.addView(exit, fullWidthActionParams());
    }

    private View centered(final View view) {
        final FrameLayout host = new FrameLayout(mActivity);
        final int availableWidth = Math.max(1,
                mActivity.getResources().getConfiguration().screenWidthDp - 36);
        host.addView(view, new FrameLayout.LayoutParams(dp(Math.min(540, availableWidth)),
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        return host;
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
