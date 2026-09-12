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

        void requestAccess();

        void openApplications();

        void controlSelectedDisplay();

        void releaseInput();

        void exitMagicDesk();
    }

    static final class State {
        final DesktopDisplayInfo[] displays;
        final String selectedDisplayUniqueId;
        final java.util.Set<Integer> desktopDisplays;
        final boolean displayOperation;
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
                final java.util.Set<Integer> desktopDisplays,
                final boolean displayOperation,
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
            this.desktopDisplays = java.util.Set.copyOf(desktopDisplays);
            this.displayOperation = displayOperation;
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
    private Button mRuntime;
    private Button mConnectWirelessDisplay;
    private DisplaySelectionView mDisplaySelection;
    private Button mCloseDesktop;
    private Button mTouchpad;
    private Button mPhoneScreen;
    private Button mControlDisplay;
    private Button mReleaseInput;
    private Button mApplications;

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
        if (!MagicDeskRuntime.inputError().isEmpty()) { mStatus.setText(MagicDeskRuntime.inputError()); }
        mRuntime.setText(mActivity.getString(
                R.string.control_runtime_status, state.runtime));
        mDisplaySelection.render(state.displays, state.selectedDisplayUniqueId,
                state.desktopDisplays, state.shellReady,
                state.sessionOperationInProgress || state.displayOperation,
                state.externalOutputControlAvailable, state.externalModeSelection);
        mConnectWirelessDisplay.setEnabled(state.wirelessConnectionUiAvailable
                && !state.sessionOperationInProgress && !state.wirelessDisplayConnected
                && !state.displayOperation);
        final boolean canOpenTouchpad = MagicDeskRuntime.inputDisplayId() > 0
                && MagicDeskRuntime.isPointerTransportReady() && state.shellReady;
        final boolean busy = state.sessionOperationInProgress || state.displayOperation;
        mRuntime.setEnabled(!busy && (!state.shellReady || ShellPrivilegePolicy.restartRequired(mActivity)));
        final int inputDisplay = MagicDeskRuntime.inputDisplayId();
        int selectedId = -1;
        for (var display : state.displays) {
            if (display.uniqueId.equals(state.selectedDisplayUniqueId)) selectedId = display.id;
        }
        mControlDisplay.setEnabled(state.shellReady && !busy && selectedId >= 0 && selectedId != inputDisplay);
        mReleaseInput.setEnabled(state.shellReady && !busy && inputDisplay >= 0);
        mApplications.setEnabled(!busy);
        mControlDisplay.setText(inputDisplay == selectedId && selectedId >= 0
                ? MagicDeskRuntime.readyInputDisplayId() == selectedId
                    ? R.string.display_input_active : R.string.display_input_starting
                : R.string.display_control);
        final boolean canControlPhoneScreen = state.externalDesktopActive
                && state.phoneScreenControlAvailable;
        mCloseDesktop.setEnabled(canCloseDesktop(state.desktopDisplays.contains(selectedId),
                state.shellReady, busy));
        mTouchpad.setEnabled(canOpenTouchpad);
        mPhoneScreen.setText(state.phoneScreenOff
                ? R.string.action_phone_screen_on
                : R.string.action_phone_screen_off);
        mUi.setControlIcon(mPhoneScreen, state.phoneScreenOff
                ? R.drawable.ic_phone_screen_on : R.drawable.ic_phone_screen_off);
        mPhoneScreen.setEnabled(canControlPhoneScreen);
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
        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout status = new LinearLayout(mActivity);
        status.setOrientation(LinearLayout.VERTICAL);
        mStatus = statusText(COLOR_TEXT, 14, true);
        status.addView(mStatus);

        mRuntime = mUi.controlAction(R.string.control_runtime_status, R.drawable.ic_lock, COLOR_MUTED);
        mRuntime.setOnClickListener(view -> mActions.requestAccess());
        final LinearLayout.LayoutParams runtimeParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(48));
        runtimeParams.setMargins(0, dp(7), 0, 0);
        status.addView(mRuntime, runtimeParams);
        row.addView(status, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        mApplications = mUi.controlAction(R.string.section_apps, R.drawable.ic_sections, COLOR_TEXT);
        mApplications.setOnClickListener(view -> mActions.openApplications());
        final LinearLayout.LayoutParams appsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(ACTION_HEIGHT_DP));
        appsParams.setMarginStart(dp(12));
        row.addView(mApplications, appsParams);
        parent.addView(row, fullWidthWrapParams(0));
    }

    private void addDesktopActions(final LinearLayout parent) {
        mDisplaySelection = new DisplaySelectionView(mActivity, mUi, mActions, parent);
        final GridLayout displayActions = actionGrid();
        mControlDisplay = mUi.controlAction(R.string.display_control, R.drawable.ic_touchpad, COLOR_TEXT);
        mControlDisplay.setOnClickListener(view -> mActions.controlSelectedDisplay());
        addGridAction(displayActions, mControlDisplay);
        mReleaseInput = mUi.controlAction(R.string.display_release_input, R.drawable.ic_close, COLOR_TEXT);
        mReleaseInput.setOnClickListener(view -> mActions.releaseInput());
        addGridAction(displayActions, mReleaseInput);
        parent.addView(displayActions, fullWidthWrapParams(dp(4)));
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
        addGridAction(sessionActions, mDisplaySelection.outputControl());
        parent.addView(sessionActions, fullWidthWrapParams(dp(4)));
    }

    private void addSystemActions(final LinearLayout parent) {
        final Button exit = mUi.controlAction(R.string.action_exit, R.drawable.ic_exit, COLOR_RED);
        exit.setOnClickListener(view -> mActions.exitMagicDesk());
        parent.addView(exit, fullWidthActionParams());
    }

    private View centered(final View view) {
        final FrameLayout host = new FrameLayout(mActivity) {
            @Override
            protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
                // The parent already excludes page padding and system insets.
                // Recompute after resize instead of retaining startup Configuration dimensions.
                final int maxWidth = dp(540);
                view.getLayoutParams().width = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                        ? maxWidth : Math.min(maxWidth, MeasureSpec.getSize(widthMeasureSpec));
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        host.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
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
