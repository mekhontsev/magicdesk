package io.github.mekhontsev.magicdesk;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class StartMenuController {
    static final int MENU_RECENT = 0;
    static final int MENU_APPS = 1;
    static final int MENU_HARDWARE = 2;
    static final int MENU_TOOLS = 3;
    static final int MENU_CAPTURE = 4;

    private static final int LAUNCH_AUTO = 0;
    private static final int LAUNCH_WINDOWED = 1;
    private static final int LAUNCH_FULLSCREEN = 2;

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;

    private LinearLayout mPanel;
    private LinearLayout mContent;
    private LinearLayout mBody;
    private EditText mSearch;
    private Button mAutoLaunch;
    private Button mWindowedLaunch;
    private Button mFullscreenLaunch;
    private boolean mFocusable = true;
    private int mLaunchMode = LAUNCH_AUTO;
    private int mMode = MENU_RECENT;
    private int mPage;
    private int mSearchSelection;
    private String mSearchQuery = "";

    StartMenuController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
    }

    LinearLayout create() {
        final LinearLayout menu = new LinearLayout(mActivity) {
            @Override
            public void onWindowFocusChanged(final boolean hasWindowFocus) {
                super.onWindowFocusChanged(hasWindowFocus);
                if (hasWindowFocus) {
                    StartMenuController.this.focusSearch();
                }
            }
        };
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(14), dp(14), dp(14), dp(12));
        menu.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL,
                dp(18),
                DesktopUiFactory.COLOR_CYAN));
        menu.setVisibility(View.GONE);
        menu.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(final View view) {
                        syncHardwareMonitoring();
                    }

                    @Override
                    public void onViewDetachedFromWindow(final View view) {
                        mActivity.setHardwarePanelVisible(false);
                    }
                });

        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final TextView title = new TextView(mActivity);
        title.setText(R.string.action_start);
        title.setTextColor(DesktopUiFactory.COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final Button close = mUi.smallButton(
                R.string.action_close,
                DesktopUiFactory.COLOR_PANEL_ALT);
        close.setOnClickListener(view -> setVisible(false));
        header.addView(close, new LinearLayout.LayoutParams(
                dp(86), LinearLayout.LayoutParams.WRAP_CONTENT));

        menu.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mSearch = new EditText(mActivity);
        mSearch.setHint(R.string.search_apps_hint);
        mSearch.setHintTextColor(DesktopUiFactory.COLOR_MUTED);
        mSearch.setTextColor(DesktopUiFactory.COLOR_TEXT);
        mSearch.setTextSize(14);
        mSearch.setSingleLine(true);
        mSearch.setShowSoftInputOnFocus(false);
        mSearch.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && mActivity.getCurrentDisplayId()
                            == Display.DEFAULT_DISPLAY) {
                mSearch.setShowSoftInputOnFocus(true);
            }
            return false;
        });
        mSearch.setPadding(dp(12), dp(8), dp(12), dp(8));
        mSearch.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                dp(8),
                DesktopUiFactory.COLOR_PANEL_ALT));
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(
                    final CharSequence text,
                    final int start,
                    final int count,
                    final int after) {
            }

            @Override
            public void onTextChanged(
                    final CharSequence text,
                    final int start,
                    final int before,
                    final int count) {
                mSearchQuery = text == null ? "" : text.toString();
                mSearchSelection = 0;
                renderBody();
            }

            @Override
            public void afterTextChanged(final Editable editable) {
            }
        });
        mSearch.setOnKeyListener((view, keyCode, event) ->
                handleSearchKey(keyCode, event));

        mContent = new LinearLayout(mActivity);
        mContent.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        contentParams.setMargins(0, dp(10), 0, 0);
        menu.addView(mContent, contentParams);
        mPanel = menu;
        return menu;
    }

    void render() {
        if (mContent == null) {
            return;
        }
        syncHardwareMonitoring();
        mContent.removeAllViews();
        mBody = null;

        final LinearLayout tabs = new LinearLayout(mActivity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.addView(createTab(R.string.section_recent, MENU_RECENT),
                new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final LinearLayout.LayoutParams appsTabParams =
                new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        appsTabParams.setMargins(dp(5), 0, dp(5), 0);
        tabs.addView(createTab(R.string.section_apps, MENU_APPS),
                appsTabParams);
        final LinearLayout.LayoutParams toolsTabParams =
                new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        toolsTabParams.setMargins(0, 0, dp(5), 0);
        tabs.addView(createTab(R.string.section_tools, MENU_TOOLS), toolsTabParams);
        tabs.addView(createTab(
                        R.string.section_hardware,
                        MENU_HARDWARE),
                new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        mContent.addView(tabs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (mMode == MENU_TOOLS) {
            addTools();
            return;
        }
        if (mMode == MENU_HARDWARE) {
            addHardware();
            return;
        }
        if (mMode == MENU_CAPTURE) {
            addCapture();
            return;
        }

        addLaunchModeControl();

        if (mSearch != null) {
            final LinearLayout.LayoutParams searchParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            searchParams.setMargins(0, dp(10), 0, 0);
            mContent.addView(mSearch, searchParams);
        }

        mBody = new LinearLayout(mActivity);
        mBody.setOrientation(LinearLayout.VERTICAL);
        mContent.addView(mBody, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        renderBody();
    }

    void showSection(final int mode) {
        showSection(mode, true);
    }

    void showSection(final int mode, final boolean focusable) {
        mMode = mode;
        mPage = 0;
        mSearchQuery = "";
        if (mSearch != null && mSearch.length() > 0) {
            mSearch.setText("");
        }
        setVisible(true, focusable);
    }

    void toggle() {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (overlays != null && overlays.isVisible(mPanel)) {
            setVisible(false);
            return;
        }
        setVisible(true, true);
    }

    void toggleTools() {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (overlays != null
                && overlays.isVisible(mPanel)
                && (mMode == MENU_TOOLS || mMode == MENU_CAPTURE)) {
            setVisible(false);
            return;
        }
        showSection(MENU_TOOLS, false);
    }

    boolean isToolsVisible() {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        return overlays != null
                && overlays.isVisible(mPanel)
                && (mMode == MENU_TOOLS || mMode == MENU_CAPTURE);
    }

    void toggleHardware() {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (overlays != null
                && overlays.isVisible(mPanel)
                && mMode == MENU_HARDWARE) {
            setVisible(false);
            return;
        }
        showSection(MENU_HARDWARE, false);
    }

    void showCapture() {
        mMode = MENU_CAPTURE;
        mPage = 0;
        mSearchQuery = "";
        if (mSearch != null && mSearch.length() > 0) {
            mSearch.setText("");
        }
        render();
    }

    void setVisible(final boolean visible) {
        setVisible(visible, true);
    }

    void setVisible(final boolean visible, final boolean focusable) {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (overlays == null || mPanel == null) {
            return;
        }
        if (!visible) {
            if (mSearch != null) {
                mSearch.setShowSoftInputOnFocus(false);
            }
            overlays.hide(mPanel);
            return;
        }
        mSearch.setShowSoftInputOnFocus(false);
        mFocusable = focusable;
        render();
        final boolean pointerCaptured = MagicDeskRuntimeService
                .capturePointerPositionIfRunning();
        final int width = getWidth();
        final int height = getHeight();
        final int left = mActivity.getDesktopAreaLeft() + mUi.desktopDp(
                16, 6, mActivity.isCompactDesktopPreview());
        final int top = mActivity.getDesktopAreaTop() + Math.max(
                0,
                mActivity.getDesktopAreaHeight()
                        - mActivity.getTaskbarHeight() - height);
        if (!overlays.show(mPanel, left, top, width, height,
                focusable, "MagicDesk Start")) {
            mActivity.setErrorStatus(
                    "OVERLAY-001",
                    mActivity.getString(R.string.status_overlay_panel_unavailable));
            return;
        }
        if (mPanel.hasWindowFocus()) {
            focusSearch();
        }
        if (pointerCaptured) {
            MagicDeskRuntimeService
                    .restorePointerPositionOnNextMotionIfRunning();
        }
    }

    private void focusSearch() {
        if (!mFocusable || isUtilityMode(mMode) || mSearch == null) {
            return;
        }
        mSearch.requestFocus();
        mSearch.setSelection(mSearch.length());
    }

    private void renderBody() {
        if (mBody == null) {
            return;
        }
        mBody.removeAllViews();

        if (!mSearchQuery.trim().isEmpty()) {
            renderSearchResults();
            return;
        }

        final List<AppItem> menuApps = getMenuApps();
        if (menuApps.isEmpty()) {
            final TextView empty = new TextView(mActivity);
            empty.setText(mMode == MENU_RECENT
                    ? R.string.recent_apps_empty
                    : R.string.status_no_apps);
            empty.setTextColor(DesktopUiFactory.COLOR_MUTED);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            mBody.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            return;
        }
        final int pageSize = getPageSize();
        final int pageCount = Math.max(
                1, (menuApps.size() + pageSize - 1) / pageSize);
        if (mPage >= pageCount) {
            mPage = pageCount - 1;
        }
        if (mPage < 0) {
            mPage = 0;
        }

        final GridLayout grid = new GridLayout(mActivity);
        grid.setColumnCount(getColumnCount());
        final int start = mPage * pageSize;
        final int end = Math.min(menuApps.size(), start + pageSize);
        for (int index = start; index < end; index++) {
            grid.addView(createAppTile(menuApps.get(index), false),
                    createTileParams());
        }
        final LinearLayout.LayoutParams gridParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        gridParams.setMargins(0, dp(12), 0, dp(8));
        mBody.addView(grid, gridParams);
        addPager(pageCount);
    }

    private Button createTab(final int textResId, final int mode) {
        final Button button = mUi.actionButton(
                textResId,
                tabSelected(mode)
                        ? DesktopUiFactory.COLOR_CYAN
                        : DesktopUiFactory.COLOR_PANEL_ALT);
        button.setTextSize(11);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(
                dp(4),
                button.getPaddingTop(),
                dp(4),
                button.getPaddingBottom());
        button.setOnClickListener(view -> {
            mMode = mode;
            mPage = 0;
            if (isUtilityMode(mode)) {
                mSearchQuery = "";
                if (mSearch != null && mSearch.length() > 0) {
                    mSearch.setText("");
                }
            }
            if (!mFocusable && !isUtilityMode(mode)) {
                mPanel.post(() -> setVisible(true, true));
                return;
            }
            render();
        });
        return button;
    }

    private void addTools() {
        final LinearLayout tools = new LinearLayout(mActivity);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setPadding(0, dp(14), 0, 0);
        mActivity.populateToolsControls(tools, dp(10));

        final ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        scroll.addView(tools, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        mContent.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private void addHardware() {
        final LinearLayout hardware = new LinearLayout(mActivity);
        hardware.setOrientation(LinearLayout.VERTICAL);
        hardware.setPadding(0, dp(14), 0, 0);
        mActivity.populateHardwareControls(hardware, dp(10));

        final ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        scroll.addView(hardware, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        mContent.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private void addCapture() {
        final LinearLayout capture = new LinearLayout(mActivity);
        capture.setOrientation(LinearLayout.VERTICAL);
        capture.setPadding(0, dp(14), 0, 0);
        mActivity.populateCaptureControls(capture, dp(10));

        final ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        scroll.addView(capture, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        mContent.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private boolean tabSelected(final int mode) {
        return mMode == mode
                || (mode == MENU_TOOLS && mMode == MENU_CAPTURE);
    }

    private static boolean isUtilityMode(final int mode) {
        return mode == MENU_TOOLS
                || mode == MENU_HARDWARE
                || mode == MENU_CAPTURE;
    }

    private void syncHardwareMonitoring() {
        mActivity.setHardwarePanelVisible(
                mMode == MENU_HARDWARE
                        && mPanel != null
                        && mPanel.isAttachedToWindow());
    }

    private View createAppTile(final AppItem app, final boolean selected) {
        final LinearLayout tile = new LinearLayout(mActivity);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(6), dp(5), dp(6), dp(5));
        tile.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                dp(12),
                selected
                        ? DesktopUiFactory.COLOR_AMBER
                        : (app.canFloat
                                ? DesktopUiFactory.COLOR_CYAN
                                : DesktopUiFactory.COLOR_PANEL_ALT)));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setOnClickListener(view -> {
            mActivity.hideAllPanels();
            launchForCurrentMode(app);
        });
        mActivity.registerContextTarget(tile, app, null);

        final ImageView icon = new ImageView(mActivity);
        icon.setImageDrawable(app.icon);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        final TextView label = new TextView(mActivity);
        label.setText(app.label);
        label.setTextColor(DesktopUiFactory.COLOR_TEXT);
        label.setTextSize(11);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(4), 0, 0);
        tile.addView(label, labelParams);
        return tile;
    }

    private GridLayout.LayoutParams createTileParams() {
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(104);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        return params;
    }

    private void addPager(final int pageCount) {
        final LinearLayout pager = new LinearLayout(mActivity);
        pager.setOrientation(LinearLayout.HORIZONTAL);
        pager.setGravity(Gravity.CENTER_VERTICAL);

        final Button previous = mUi.actionButton(
                R.string.action_previous,
                DesktopUiFactory.COLOR_PANEL_ALT);
        previous.setEnabled(mPage > 0);
        previous.setOnClickListener(view -> {
            if (mPage > 0) {
                mPage--;
                renderBody();
            }
        });
        pager.addView(previous, new LinearLayout.LayoutParams(
                dp(108), LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView page = new TextView(mActivity);
        page.setText(mActivity.getString(
                R.string.page_status,
                Integer.valueOf(mPage + 1),
                Integer.valueOf(pageCount)));
        page.setTextColor(DesktopUiFactory.COLOR_MUTED);
        page.setTextSize(13);
        page.setGravity(Gravity.CENTER);
        pager.addView(page, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final Button next = mUi.actionButton(
                R.string.action_next,
                DesktopUiFactory.COLOR_PANEL_ALT);
        next.setEnabled(mPage + 1 < pageCount);
        next.setOnClickListener(view -> {
            if (mPage + 1 < pageCount) {
                mPage++;
                renderBody();
            }
        });
        pager.addView(next, new LinearLayout.LayoutParams(
                dp(108), LinearLayout.LayoutParams.WRAP_CONTENT));
        mBody.addView(pager, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private List<AppItem> getMenuApps() {
        final List<AppItem> result = new ArrayList<>();
        final List<AppItem> launcherApps = mActivity.getLauncherApps();
        if (mMode == MENU_APPS) {
            result.addAll(launcherApps);
            return result;
        }
        if (mMode == MENU_RECENT) {
            for (final String packageName :
                    DesktopPreferences.recentPackages(mActivity)) {
                final AppItem app = LauncherAppRepository.find(
                        launcherApps, packageName);
                if (app != null) {
                    result.add(app);
                }
            }
        }
        return result;
    }

    private List<AppItem> getSearchApps() {
        final String query = mSearchQuery.trim().toLowerCase(Locale.ROOT);
        if (query.length() == 0) {
            return Collections.emptyList();
        }
        final List<AppItem> result = new ArrayList<>();
        for (final AppItem app : mActivity.getLauncherApps()) {
            if (app.label.toLowerCase(Locale.ROOT).contains(query)
                    || app.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                result.add(app);
            }
        }
        return result;
    }

    private void renderSearchResults() {
        final List<AppItem> matches = getSearchApps();
        if (matches.isEmpty()) {
            final TextView empty = new TextView(mActivity);
            empty.setText(R.string.search_no_results);
            empty.setTextColor(DesktopUiFactory.COLOR_MUTED);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            mBody.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            return;
        }
        final int visibleCount = Math.min(matches.size(), getPageSize());
        if (mSearchSelection >= visibleCount) {
            mSearchSelection = visibleCount - 1;
        }
        final GridLayout grid = new GridLayout(mActivity);
        grid.setColumnCount(getColumnCount());
        for (int index = 0; index < visibleCount; index++) {
            grid.addView(
                    createAppTile(matches.get(index), index == mSearchSelection),
                    createTileParams());
        }
        mBody.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private boolean handleSearchKey(
            final int keyCode,
            final KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        final List<AppItem> matches = getSearchApps();
        final int visibleCount = Math.min(matches.size(), getPageSize());
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !matches.isEmpty()) {
            mSearchSelection = Math.min(
                    visibleCount - 1, mSearchSelection + 1);
            renderBody();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && !matches.isEmpty()) {
            mSearchSelection = Math.max(0, mSearchSelection - 1);
            renderBody();
            return true;
        }
        if ((keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                && !matches.isEmpty()) {
            final AppItem app = matches.get(
                    Math.min(mSearchSelection, matches.size() - 1));
            mActivity.hideAllPanels();
            launchForCurrentMode(app);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            mActivity.hideAllPanels();
            return true;
        }
        return false;
    }

    private void launchForCurrentMode(final AppItem app) {
        if (mLaunchMode == LAUNCH_WINDOWED) {
            mActivity.launchWindowed(app);
        } else if (mLaunchMode == LAUNCH_FULLSCREEN) {
            mActivity.launchFullscreen(app);
        } else {
            mActivity.launchDefault(app);
        }
    }

    private void addLaunchModeControl() {
        final LinearLayout modes = new LinearLayout(mActivity);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setPadding(dp(2), dp(2), dp(2), dp(2));
        modes.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                dp(8),
                DesktopUiFactory.COLOR_PANEL_ALT));

        mAutoLaunch = createLaunchModeButton(
                R.string.launch_mode_auto, LAUNCH_AUTO);
        mWindowedLaunch = createLaunchModeButton(
                R.string.launch_mode_windowed, LAUNCH_WINDOWED);
        mFullscreenLaunch = createLaunchModeButton(
                R.string.launch_mode_fullscreen, LAUNCH_FULLSCREEN);
        modes.addView(mAutoLaunch, launchModeButtonParams(0));
        modes.addView(mWindowedLaunch, launchModeButtonParams(dp(3)));
        modes.addView(mFullscreenLaunch, launchModeButtonParams(dp(3)));

        final LinearLayout.LayoutParams modesParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(38));
        modesParams.setMargins(0, dp(8), 0, 0);
        mContent.addView(modes, modesParams);
        updateLaunchModeButtons();
    }

    private Button createLaunchModeButton(
            final int textResId,
            final int launchMode) {
        final Button button = mUi.smallButton(
                textResId, DesktopUiFactory.COLOR_PANEL_ALT);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setOnClickListener(view -> {
            mLaunchMode = launchMode;
            updateLaunchModeButtons();
        });
        return button;
    }

    private LinearLayout.LayoutParams launchModeButtonParams(
            final int leftMargin) {
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1);
        params.setMargins(leftMargin, 0, 0, 0);
        return params;
    }

    private void updateLaunchModeButtons() {
        updateLaunchModeButton(mAutoLaunch, LAUNCH_AUTO, true);
        updateLaunchModeButton(
                mWindowedLaunch,
                LAUNCH_WINDOWED,
                ShellAccess.isReady());
        updateLaunchModeButton(mFullscreenLaunch, LAUNCH_FULLSCREEN, true);
    }

    private void updateLaunchModeButton(
            final Button button,
            final int launchMode,
            final boolean enabled) {
        if (button == null) {
            return;
        }
        final boolean selected = mLaunchMode == launchMode;
        button.setEnabled(enabled);
        button.setAlpha(selected ? 1f : 0.72f);
        button.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                dp(6),
                selected
                        ? DesktopUiFactory.COLOR_CYAN
                        : DesktopUiFactory.COLOR_PANEL_ALT));
    }

    private int getColumnCount() {
        final int widthDp =
                mActivity.getResources().getConfiguration().screenWidthDp;
        return widthDp >= 1100 ? 4 : 3;
    }

    private int getPageSize() {
        return getColumnCount() * getRowCount();
    }

    private int getRowCount() {
        final int heightDp =
                mActivity.getResources().getConfiguration().screenHeightDp;
        if (heightDp < 480) {
            return 1;
        }
        if (heightDp < 650) {
            return 2;
        }
        return 3;
    }

    private int getWidth() {
        final int margin = mUi.desktopDp(
                16, 6, mActivity.isCompactDesktopPreview());
        final int available = Math.max(
                1, mActivity.getDesktopAreaWidth() - margin * 2);
        return Math.min(dp(560), available);
    }

    private int getHeight() {
        final int margin = mUi.desktopDp(
                12, 4, mActivity.isCompactDesktopPreview());
        final int available = Math.max(
                1,
                mActivity.getDesktopAreaHeight()
                        - mActivity.getTaskbarHeight() - margin);
        return Math.min(dp(620), available);
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
