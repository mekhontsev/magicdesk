package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_BACKGROUND;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_CYAN;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_MUTED;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_PANEL_ALT;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_RED;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_TEXT;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class PhoneControlPanelController {
    enum ExternalDisplayState {
        CHECKING,
        DISCONNECTED,
        CONNECTED
    }

    interface Actions {
        void openDesktopHere();

        void showExternalDesktop();

        void connectWirelessDisplay();

        void setFillExternalDisplay(boolean enabled);

        void setExternalOutputTiming(String outputTiming);

        void closeDesktop();

        void openTouchpad();

        void togglePhoneScreen();

        void openSettings();

        void exitMagicDesk();
    }

    static final class State {
        final boolean externalDesktopActive;
        final boolean desktopReady;
        final boolean consoleControlAvailable;
        final boolean phoneScreenOff;
        final boolean phoneScreenControlAvailable;
        final boolean phoneTouchpadAvailable;
        final boolean externalOutputControlAvailable;
        final boolean fillExternalDisplay;
        final PlatformProjectionDriver.ModeSelection externalModeSelection;
        final String externalDisplaySummary;
        final ExternalDisplayState externalDisplayState;
        final boolean wiredDisplayConnected;
        final boolean wirelessConnectionUiAvailable;
        final boolean wirelessDisplayConnected;
        final boolean simulatedDesktopAvailable;
        final String status;
        final String runtime;
        final int currentDisplayId;
        final int externalDesktopDisplayId;

        State(
                final boolean externalDesktopActive,
                final boolean desktopReady,
                final boolean consoleControlAvailable,
                final boolean phoneScreenOff,
                final boolean phoneScreenControlAvailable,
                final boolean phoneTouchpadAvailable,
                final boolean externalOutputControlAvailable,
                final boolean fillExternalDisplay,
                final PlatformProjectionDriver.ModeSelection externalModeSelection,
                final String externalDisplaySummary,
                final ExternalDisplayState externalDisplayState,
                final boolean wiredDisplayConnected,
                final boolean wirelessConnectionUiAvailable,
                final boolean wirelessDisplayConnected,
                final boolean simulatedDesktopAvailable,
                final String status,
                final String runtime,
                final int currentDisplayId,
                final int externalDesktopDisplayId) {
            this.externalDesktopActive = externalDesktopActive;
            this.desktopReady = desktopReady;
            this.consoleControlAvailable = consoleControlAvailable;
            this.phoneScreenOff = phoneScreenOff;
            this.phoneScreenControlAvailable = phoneScreenControlAvailable;
            this.phoneTouchpadAvailable = phoneTouchpadAvailable;
            this.externalOutputControlAvailable =
                    externalOutputControlAvailable;
            this.fillExternalDisplay = fillExternalDisplay;
            this.externalModeSelection = externalModeSelection;
            this.externalDisplaySummary = externalDisplaySummary;
            this.externalDisplayState = externalDisplayState;
            this.wiredDisplayConnected = wiredDisplayConnected;
            this.wirelessConnectionUiAvailable =
                    wirelessConnectionUiAvailable;
            this.wirelessDisplayConnected = wirelessDisplayConnected;
            this.simulatedDesktopAvailable = simulatedDesktopAvailable;
            this.status = status;
            this.runtime = runtime;
            this.currentDisplayId = currentDisplayId;
            this.externalDesktopDisplayId = externalDesktopDisplayId;
        }
    }

    private static final int ACTION_HEIGHT_DP = 52;

    private final Activity mActivity;
    private final DesktopUiFactory mUi;
    private final Actions mActions;

    private TextView mStatus;
    private TextView mRuntime;
    private TextView mDisplay;
    private TextView mExternalDisplay;
    private LinearLayout mExternalDisplayOptions;
    private Button mConnectWirelessDisplay;
    private Button mExternalDesktop;
    private Button mCloseDesktop;
    private Button mTouchpad;
    private Button mPhoneScreen;
    private GridLayout mSessionActions;
    private Switch mFillDisplay;
    private Spinner mOutputMode;
    private ArrayAdapter<String> mOutputModeAdapter;
    private List<PlatformProjectionDriver.Mode> mOutputModes =
            Collections.emptyList();
    private boolean mOutputModesConfigurable;
    private boolean mRendering = true;
    private int mRenderGeneration;

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
        final int renderGeneration = ++mRenderGeneration;
        mRendering = true;
        mStatus.setText(state.status);
        mRuntime.setText(mActivity.getString(
                R.string.control_runtime_status, state.runtime));
        final String externalDesktopDisplay = state.externalDesktopActive
                ? Integer.toString(state.externalDesktopDisplayId)
                : mActivity.getString(R.string.state_off);
        mDisplay.setText(mActivity.getString(
                R.string.control_display_status,
                Integer.valueOf(state.currentDisplayId),
                externalDesktopDisplay));
        if (state.externalDisplaySummary == null
                || state.externalDisplaySummary.isEmpty()) {
            mExternalDisplay.setVisibility(View.GONE);
        } else {
            mExternalDisplay.setText(mActivity.getString(
                    R.string.control_external_display_status,
                    state.externalDisplaySummary));
            mExternalDisplay.setVisibility(View.VISIBLE);
        }

        if (!state.externalDesktopActive) {
            mExternalDesktop.setText(
                    R.string.action_start_external_desktop);
        } else if (!state.desktopReady) {
            mExternalDesktop.setText(
                    R.string.action_start_external_desktop);
        } else {
            mExternalDesktop.setText(
                    R.string.action_show_external_desktop);
        }
        final boolean canStartOrShowExternalDesktop =
                state.externalDesktopActive
                        || state.externalDisplayState
                                == ExternalDisplayState.CONNECTED
                        || state.simulatedDesktopAvailable;
        mExternalDesktop.setEnabled(
                state.consoleControlAvailable
                        && canStartOrShowExternalDesktop);
        final boolean canConnectWireless =
                state.wirelessConnectionUiAvailable
                        && !state.externalDesktopActive
                        && !state.wirelessDisplayConnected;
        mConnectWirelessDisplay.setVisibility(
                canConnectWireless ? View.VISIBLE : View.GONE);
        mConnectWirelessDisplay.setEnabled(canConnectWireless);
        final boolean canConfigureOutput =
                !state.externalDesktopActive
                        && state.consoleControlAvailable
                        && state.externalOutputControlAvailable
                        && state.wiredDisplayConnected
                        && state.externalDisplayState
                                == ExternalDisplayState.CONNECTED;
        mExternalDisplayOptions.setVisibility(
                canConfigureOutput ? View.VISIBLE : View.GONE);
        mFillDisplay.setChecked(state.fillExternalDisplay);
        mFillDisplay.setEnabled(canConfigureOutput);
        renderOutputModes(state.externalModeSelection);
        mOutputMode.setEnabled(canConfigureOutput
                && mOutputModesConfigurable
                && !mOutputModes.isEmpty());
        final boolean canCloseDesktop = state.externalDesktopActive
                && state.consoleControlAvailable;
        final boolean canOpenTouchpad = state.externalDesktopActive
                && state.consoleControlAvailable
                && state.phoneTouchpadAvailable;
        final boolean canControlPhoneScreen = state.externalDesktopActive
                && state.phoneScreenControlAvailable;
        mCloseDesktop.setVisibility(
                canCloseDesktop ? View.VISIBLE : View.GONE);
        mCloseDesktop.setEnabled(canCloseDesktop);
        mTouchpad.setVisibility(
                canOpenTouchpad ? View.VISIBLE : View.GONE);
        mTouchpad.setEnabled(canOpenTouchpad);
        mPhoneScreen.setText(state.phoneScreenOff
                ? R.string.action_phone_screen_on
                : R.string.action_phone_screen_off);
        mPhoneScreen.setVisibility(
                canControlPhoneScreen ? View.VISIBLE : View.GONE);
        mPhoneScreen.setEnabled(canControlPhoneScreen);
        mSessionActions.setVisibility(
                canCloseDesktop
                        || canOpenTouchpad
                        || canControlPhoneScreen
                        ? View.VISIBLE : View.GONE);
        // Spinner selection callbacks can be posted after setSelection(). Keep
        // rendering guarded through the current UI turn so merely displaying a
        // mode never persists it as a user choice.
        mStatus.post(() -> {
            if (mRenderGeneration == renderGeneration) {
                mRendering = false;
            }
        });
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
        title.setText(mActivity.getString(
                R.string.control_panel_title,
                BuildConfig.VERSION_NAME));
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

        mExternalDisplay = statusText(COLOR_MUTED, 13, false);
        final LinearLayout.LayoutParams externalDisplayParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        externalDisplayParams.setMargins(0, dp(3), 0, 0);
        parent.addView(mExternalDisplay, externalDisplayParams);
    }

    private void addDesktopActions(final LinearLayout parent) {
        addSectionTitle(parent, R.string.control_section_desktop, dp(22));

        mExternalDesktop = actionButton(
                R.string.action_start_external_desktop, COLOR_CYAN);
        mExternalDesktop.setOnClickListener(
                view -> mActions.showExternalDesktop());
        parent.addView(mExternalDesktop, fullWidthActionParams());

        final LinearLayout secondaryActions = new LinearLayout(mActivity);
        secondaryActions.setOrientation(LinearLayout.HORIZONTAL);
        mConnectWirelessDisplay = actionButton(
                R.string.action_connect_wireless_display, COLOR_PANEL_ALT);
        mConnectWirelessDisplay.setOnClickListener(
                view -> mActions.connectWirelessDisplay());
        secondaryActions.addView(
                mConnectWirelessDisplay, rowActionParams(false));

        final Button desktopHere = actionButton(
                R.string.action_desktop_this_screen, COLOR_PANEL_ALT);
        desktopHere.setOnClickListener(view -> mActions.openDesktopHere());
        secondaryActions.addView(desktopHere, rowActionParams(true));
        parent.addView(secondaryActions, fullWidthWrapParams(0));

        addExternalDisplayOptions(parent);

        mSessionActions = actionGrid();
        mCloseDesktop = actionButton(
                R.string.action_close_desktop, COLOR_CYAN);
        mCloseDesktop.setOnClickListener(
                view -> mActions.closeDesktop());
        addGridAction(mSessionActions, mCloseDesktop);

        mTouchpad = actionButton(
                R.string.action_open_touchpad, COLOR_CYAN);
        mTouchpad.setOnClickListener(view -> mActions.openTouchpad());
        addGridAction(mSessionActions, mTouchpad);

        mPhoneScreen = actionButton(
                R.string.action_phone_screen_off, COLOR_CYAN);
        mPhoneScreen.setOnClickListener(view -> mActions.togglePhoneScreen());
        addGridAction(mSessionActions, mPhoneScreen);
        parent.addView(mSessionActions, fullWidthWrapParams(dp(6)));
    }

    private void addExternalDisplayOptions(final LinearLayout parent) {
        mExternalDisplayOptions = new LinearLayout(mActivity);
        mExternalDisplayOptions.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout fitRow = optionRow();
        fitRow.addView(optionLabel(R.string.external_display_fill),
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1));
        mFillDisplay = new Switch(mActivity);
        mFillDisplay.setOnCheckedChangeListener((button, checked) -> {
            if (!mRendering) {
                mActions.setFillExternalDisplay(checked);
            }
        });
        fitRow.addView(mFillDisplay);
        mExternalDisplayOptions.addView(fitRow);

        final LinearLayout resolutionRow = optionRow();
        resolutionRow.addView(optionLabel(R.string.external_display_resolution),
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1));
        mOutputMode = new Spinner(mActivity, Spinner.MODE_DROPDOWN);
        mOutputModeAdapter = new ArrayAdapter<>(
                mActivity,
                android.R.layout.simple_spinner_item,
                new ArrayList<>());
        mOutputModeAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        mOutputMode.setAdapter(mOutputModeAdapter);
        mOutputMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    final AdapterView<?> parentView,
                    final View selected,
                    final int position,
                    final long id) {
                if (mRendering) {
                    return;
                }
                if (position >= 0 && position < mOutputModes.size()) {
                    mActions.setExternalOutputTiming(
                            mOutputModes.get(position).timingKey);
                }
            }

            @Override
            public void onNothingSelected(final AdapterView<?> parentView) {
            }
        });
        resolutionRow.addView(mOutputMode, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48)));
        mExternalDisplayOptions.addView(resolutionRow);
        parent.addView(mExternalDisplayOptions);
    }

    private void renderOutputModes(
            final PlatformProjectionDriver.ModeSelection selection) {
        final List<PlatformProjectionDriver.Mode> modes = new ArrayList<>();
        if (selection != null && selection.systemDefaultAvailable) {
            modes.add(new PlatformProjectionDriver.Mode(
                    "", mActivity.getString(
                            R.string.external_display_system_native)));
        }
        if (selection != null) {
            modes.addAll(selection.availableModes);
        }
        final boolean configurable = selection != null
                && selection.configurable;
        if (mOutputModeAdapter.getCount() == 0
                || !sameModes(mOutputModes, modes)
                || mOutputModesConfigurable != configurable) {
            mOutputModes = modes;
            mOutputModesConfigurable = configurable;
            mOutputModeAdapter.clear();
            if (modes.isEmpty()) {
                mOutputModeAdapter.add(
                        mActivity.getString(R.string.external_display_no_modes));
            } else {
                for (final PlatformProjectionDriver.Mode mode : modes) {
                    mOutputModeAdapter.add(configurable
                            ? mode.displayLabel
                            : mActivity.getString(
                                    R.string.external_display_system_mode,
                                    mode.displayLabel));
                }
            }
            mOutputModeAdapter.notifyDataSetChanged();
        }
        if (selection == null || selection.target == null) {
            mOutputMode.setSelection(0, false);
            return;
        }
        final String selectedTiming = selection.systemDefaultSelected
                ? "" : selection.target.timingKey;
        for (int index = 0; index < mOutputModes.size(); index++) {
            if (selectedTiming.equals(mOutputModes.get(index).timingKey)) {
                mOutputMode.setSelection(index, false);
                return;
            }
        }
    }

    private static boolean sameModes(
            final List<PlatformProjectionDriver.Mode> left,
            final List<PlatformProjectionDriver.Mode> right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!left.get(index).timingKey.equals(
                    right.get(index).timingKey)) {
                return false;
            }
        }
        return true;
    }

    private LinearLayout optionRow() {
        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(6), 0, dp(3), 0);
        return row;
    }

    private TextView optionLabel(final int textResId) {
        final TextView label = new TextView(mActivity);
        label.setText(textResId);
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(14);
        return label;
    }

    private void addSystemActions(final LinearLayout parent) {
        final GridLayout actions = actionGrid();

        final Button settings = actionButton(
                R.string.action_settings, COLOR_PANEL_ALT);
        settings.setOnClickListener(view -> mActions.openSettings());
        addGridAction(actions, settings);

        final Button exit = actionButton(R.string.action_exit, COLOR_RED);
        exit.setOnClickListener(view -> mActions.exitMagicDesk());
        addGridAction(actions, exit);
        parent.addView(actions, fullWidthWrapParams(dp(16)));
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

    private LinearLayout.LayoutParams rowActionParams(
            final boolean addStartMargin) {
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, dp(ACTION_HEIGHT_DP), 1);
        params.setMargins(
                addStartMargin ? dp(3) : 0,
                dp(3),
                addStartMargin ? 0 : dp(3),
                dp(3));
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
