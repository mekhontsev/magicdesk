package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
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
import java.util.Comparator;
import java.util.List;

final class StartMenuController {
    static final int MENU_RECENT = 0;
    static final int MENU_APPS = 1;
    static final int MENU_TOOLS = 2;
    static final int MENU_CAPTURE = 3;

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final StartSearchController mSearchController;

    private LinearLayout mPanel;
    private LinearLayout mContent;
    private LinearLayout mBody;
    private EditText mSearch;
    private boolean mFocusable = true;
    private int mMode = MENU_RECENT;
    private int mPage;
    private int mSearchSelection;
    private String mSearchQuery = "";

    StartMenuController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
        mSearchController = new StartSearchController(
                activity,
                this::onSearchResultsChanged);
    }

    // The touch observer only enables IME display; EditText retains click handling.
    @SuppressLint("ClickableViewAccessibility")
    LinearLayout create() {
        final LinearLayout menu = new LinearLayout(mActivity) {
            // Overlay windows bypass the activity's event dispatch path.
            @Override
            public boolean dispatchTouchEvent(final MotionEvent event) {
                if (mActivity.handleDesktopMouseTouchEvent(event, true)) {
                    return true;
                }
                return super.dispatchTouchEvent(event);
            }

            @Override
            public boolean dispatchGenericMotionEvent(
                    final MotionEvent event) {
                if (mActivity.handleDesktopMouseGenericEvent(event, true)) {
                    return true;
                }
                return super.dispatchGenericMotionEvent(event);
            }

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
                mSearchController.update(
                        mSearchQuery,
                        mActivity.getLauncherApps(),
                        mActivity.getDesktopApplications());
                renderBody();
            }

            @Override
            public void afterTextChanged(final Editable editable) {
            }
        });
        mSearch.setOnKeyListener((view, keyCode, event) ->
                handleSearchKey(keyCode, event));
        mActivity.registerAutomationUiElement(
                mSearch,
                "start.search",
                "text_field",
                mActivity.getString(R.string.search_apps_hint));

        mContent = new LinearLayout(mActivity);
        mContent.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        contentParams.setMargins(0, dp(10), 0, 0);
        menu.addView(mContent, contentParams);
        mPanel = menu;
        mActivity.registerAutomationUiElement(
                menu, "panel.start", "panel", "Start");
        return menu;
    }

    void render() {
        if (mContent == null) {
            return;
        }
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
        tabs.addView(createTab(R.string.section_tools, MENU_TOOLS),
                new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        mContent.addView(tabs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (mMode == MENU_TOOLS) {
            addTools();
            return;
        }
        if (mMode == MENU_CAPTURE) {
            addCapture();
            return;
        }

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
        } else {
            mSearchController.update(
                    "",
                    mActivity.getLauncherApps(),
                    mActivity.getDesktopApplications());
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

    boolean isVisible() {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        return overlays != null && overlays.isVisible(mPanel);
    }

    void showCapture() {
        mMode = MENU_CAPTURE;
        mPage = 0;
        mSearchQuery = "";
        if (mSearch != null && mSearch.length() > 0) {
            mSearch.setText("");
        } else {
            mSearchController.update(
                    "",
                    mActivity.getLauncherApps(),
                    mActivity.getDesktopApplications());
        }
        render();
    }

    void release() {
        mSearchController.close();
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
            mSearchController.pause();
            overlays.hide(mPanel);
            return;
        }
        mSearch.setShowSoftInputOnFocus(false);
        mFocusable = focusable;
        mSearchController.update(
                mSearchQuery,
                mActivity.getLauncherApps(),
                mActivity.getDesktopApplications());
        render();
        final int width = getWidth();
        final int height = getHeight();
        final int left = mActivity.getDesktopAreaLeft() + mUi.desktopDp(
                16, 6, mActivity.isCompactDesktopPreview());
        final int top = mActivity.getDesktopAreaTop() + Math.max(
                0,
                mActivity.getDesktopAreaHeight()
                        - mActivity.getTaskbarHeight() - height);
        final boolean inputMethodTarget = mActivity.getCurrentDisplayId()
                == Display.DEFAULT_DISPLAY;
        if (!overlays.show(mPanel, left, top, width, height,
                focusable, inputMethodTarget, "MagicDesk Start")) {
            mActivity.setErrorStatus(
                    "OVERLAY-001",
                    mActivity.getString(R.string.status_overlay_panel_unavailable));
            return;
        }
        if (mPanel.hasWindowFocus()) {
            focusSearch();
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

        final List<MenuApplication> menuApps = getMenuApps();
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
        mActivity.registerAutomationUiElement(
                button,
                "start.tab." + modeName(mode),
                "tab",
                button.getText());
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
                || mode == MENU_CAPTURE;
    }

    private View createAppTile(
            final MenuApplication application,
            final boolean selected) {
        final AppItem app = application.app;
        final DesktopApplicationRepository.Entry desktopApplication =
                application.desktopApplication;
        final LinearLayout tile = new LinearLayout(mActivity);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(6), dp(5), dp(6), dp(5));
        tile.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                dp(12),
                selected
                        ? DesktopUiFactory.COLOR_AMBER
                        : (app == null || app.canFloat
                                ? DesktopUiFactory.COLOR_CYAN
                                : DesktopUiFactory.COLOR_PANEL_ALT)));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setOnClickListener(view -> {
            mActivity.hideAllPanels();
            if (app != null) {
                mActivity.launchDefault(app);
            } else {
                mActivity.launchDesktopShortcut(
                        desktopApplication.shortcut,
                        DesktopLaunchArguments.empty(),
                        desktopApplication.desktopFilePath);
            }
        });
        if (app != null) {
            mActivity.registerContextTarget(tile, app, null);
        } else if (desktopApplication.desktopFile != null) {
            mActivity.registerFileContextTarget(
                    tile, desktopApplication.desktopFile);
        }
        mActivity.registerAutomationUiElement(
                tile,
                "start.app."
                        + DesktopAutomationUiRegistry.segment(
                                application.identity()),
                "application",
                application.label(),
                application.identity(),
                -1);

        final ImageView icon = new ImageView(mActivity);
        icon.setImageDrawable(app != null
                ? app.icon
                : DesktopApplicationIconResolver.resolve(
                        mActivity, desktopApplication.shortcut));
        tile.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        final TextView label = new TextView(mActivity);
        label.setText(application.label());
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
        mActivity.registerAutomationUiElement(
                previous, "start.page.previous", "button",
                previous.getText());
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
        mActivity.registerAutomationUiElement(
                next, "start.page.next", "button", next.getText());
        pager.addView(next, new LinearLayout.LayoutParams(
                dp(108), LinearLayout.LayoutParams.WRAP_CONTENT));
        mBody.addView(pager, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private List<MenuApplication> getMenuApps() {
        final List<MenuApplication> result = new ArrayList<>();
        final List<AppItem> launcherApps = mActivity.getLauncherApps();
        if (mMode == MENU_APPS) {
            for (final AppItem app : launcherApps) {
                result.add(MenuApplication.android(app));
            }
            for (final DesktopApplicationRepository.Entry application
                    : mActivity.getDesktopApplications()) {
                if (application.shortcut.hasExecLaunch()) {
                    result.add(MenuApplication.desktop(application));
                }
            }
            result.sort(Comparator
                    .comparing(
                            MenuApplication::label,
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(MenuApplication::identity));
            return result;
        }
        if (mMode == MENU_RECENT) {
            for (final String appKey :
                    DesktopPreferences.recentAppKeys(mActivity)) {
                final AppItem app = LauncherAppRepository.findByIdentityKey(
                        launcherApps, appKey);
                if (app != null) {
                    result.add(MenuApplication.android(app));
                }
            }
        }
        return result;
    }

    private void renderSearchResults() {
        final List<StartSearchController.Result> matches =
                mSearchController.results(getSearchResultLimit());
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
        final int visibleCount = matches.size();
        if (mSearchSelection >= visibleCount) {
            mSearchSelection = visibleCount - 1;
        }
        final LinearLayout list = new LinearLayout(mActivity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, 0);
        for (int index = 0; index < visibleCount; index++) {
            list.addView(
                    createSearchRow(
                            matches.get(index),
                            index == mSearchSelection),
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(58)));
        }
        final ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        mBody.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private boolean handleSearchKey(
            final int keyCode,
            final KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        final List<StartSearchController.Result> matches =
                mSearchController.results(getSearchResultLimit());
        final int visibleCount = matches.size();
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
            final StartSearchController.Result result = matches.get(
                    Math.min(mSearchSelection, matches.size() - 1));
            openSearchResult(result);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            mActivity.hideTopPanel();
            return true;
        }
        return false;
    }

    private View createSearchRow(
            final StartSearchController.Result result,
            final boolean selected) {
        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(5), dp(8), dp(5));
        row.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                dp(7),
                selected
                        ? DesktopUiFactory.COLOR_AMBER
                        : DesktopUiFactory.COLOR_PANEL_ALT));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(view -> openSearchResult(result));
        if (result.app != null) {
            mActivity.registerContextTarget(row, result.app, null);
            mActivity.registerAutomationUiElement(
                    row,
                    "start.search.app."
                            + DesktopAutomationUiRegistry.segment(
                                    result.app.packageName),
                    "application",
                    result.label,
                    result.app.packageName,
                    -1);
        } else if (result.desktopApplication != null
                && result.desktopApplication.desktopFile != null) {
            mActivity.registerFileContextTarget(
                    row, result.desktopApplication.desktopFile);
            mActivity.registerAutomationUiElement(
                    row,
                    "start.search.command."
                            + DesktopAutomationUiRegistry.segment(
                                    result.desktopApplication.desktopFilePath),
                    "application",
                    result.label);
        } else {
            mActivity.registerAutomationUiElement(
                    row,
                    "start.search.result."
                            + DesktopAutomationUiRegistry.segment(
                                    result.detail),
                    "search_result",
                    result.label);
        }

        final ImageView icon = new ImageView(mActivity);
        if (result.app != null) {
            icon.setImageDrawable(result.app.icon);
        } else if (result.desktopApplication != null) {
            icon.setImageDrawable(DesktopApplicationIconResolver.resolve(
                    mActivity, result.desktopApplication.shortcut));
        } else {
            icon.setImageResource(searchIcon(result));
        }
        row.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        final LinearLayout labels = new LinearLayout(mActivity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, 0, 0);
        final TextView name = new TextView(mActivity);
        name.setText(result.label);
        name.setTextColor(DesktopUiFactory.COLOR_TEXT);
        name.setTextSize(14);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        final TextView detail = new TextView(mActivity);
        detail.setText(result.detail);
        detail.setTextColor(DesktopUiFactory.COLOR_MUTED);
        detail.setTextSize(10);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        labels.addView(name);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private int searchIcon(final StartSearchController.Result result) {
        if (result.file != null) {
            return FileIconResolver.forFile(
                    result.file.directory,
                    result.file.mimeType);
        }
        if (result.builtIn != null) {
            final AppLaunchTarget target = result.builtIn.launchTarget;
            if (BuiltInDesktopAppCatalog.filesTarget().equals(target)) {
                return R.drawable.ic_desktop_folder;
            }
            if (BuiltInDesktopAppCatalog.consoleTarget().equals(target)) {
                return R.drawable.ic_file_console;
            }
            if (BuiltInDesktopAppCatalog.taskManagerTarget().equals(target)) {
                return android.R.drawable.ic_menu_manage;
            }
            return android.R.drawable.ic_menu_preferences;
        }
        if (result.action == StartSearchController.Action.SCREENSHOT) {
            return android.R.drawable.ic_menu_camera;
        }
        if (result.action == StartSearchController.Action.SCREEN_RECORDING) {
            return android.R.drawable.presence_video_online;
        }
        return R.drawable.ic_show_desktop;
    }

    private static String modeName(final int mode) {
        switch (mode) {
            case MENU_RECENT:
                return "recent";
            case MENU_APPS:
                return "apps";
            case MENU_TOOLS:
                return "tools";
            case MENU_CAPTURE:
                return "capture";
            default:
                return Integer.toString(mode);
        }
    }

    private void openSearchResult(final StartSearchController.Result result) {
        mActivity.hideAllPanels();
        if (result.app != null) {
            mActivity.launchDefault(result.app);
            return;
        }
        if (result.desktopApplication != null) {
            mActivity.launchDesktopShortcut(
                    result.desktopApplication.shortcut,
                    DesktopLaunchArguments.empty(),
                    result.desktopApplication.desktopFilePath);
            return;
        }
        if (result.file != null) {
            final android.content.Intent intent = result.file.directory
                    ? FileManagerActivity.createIntent(
                            mActivity, result.file.absolutePath)
                    : FileManagerActivity.createRevealIntent(
                            mActivity, result.file);
            mActivity.launchInternalWindow(
                    intent,
                    BuiltInDesktopAppCatalog.filesTarget(),
                    mActivity.getString(R.string.file_manager_title));
            return;
        }
        if (result.builtIn != null) {
            final AppLaunchTarget target = result.builtIn.launchTarget;
            if (BuiltInDesktopAppCatalog.filesTarget().equals(target)) {
                mActivity.openFiles();
            } else if (BuiltInDesktopAppCatalog.consoleTarget().equals(target)) {
                mActivity.openConsole();
            } else if (BuiltInDesktopAppCatalog.taskManagerTarget().equals(target)) {
                mActivity.openTaskManager();
            } else if (BuiltInDesktopAppCatalog.settingsTarget().equals(target)) {
                mActivity.openSettings();
            }
            return;
        }
        if (result.action == StartSearchController.Action.SHOW_DESKTOP) {
            mActivity.toggleDesktopWorkspace();
        } else if (result.action == StartSearchController.Action.SCREENSHOT) {
            mActivity.captureDesktopScreenshot();
        } else if (result.action
                == StartSearchController.Action.SCREEN_RECORDING) {
            mActivity.toggleDesktopRecording();
        }
    }

    private int getSearchResultLimit() {
        return Math.max(4, getRowCount() * 3);
    }

    private static final class MenuApplication {
        final AppItem app;
        final DesktopApplicationRepository.Entry desktopApplication;

        private MenuApplication(
                final AppItem app,
                final DesktopApplicationRepository.Entry desktopApplication) {
            this.app = app;
            this.desktopApplication = desktopApplication;
        }

        static MenuApplication android(final AppItem app) {
            return new MenuApplication(app, null);
        }

        static MenuApplication desktop(
                final DesktopApplicationRepository.Entry application) {
            return new MenuApplication(null, application);
        }

        String label() {
            return app != null ? app.label : desktopApplication.shortcut.name;
        }

        String identity() {
            return app != null
                    ? app.packageName : desktopApplication.desktopFilePath;
        }
    }

    private void onSearchResultsChanged() {
        if (mBody != null && !mSearchQuery.trim().isEmpty()) {
            renderBody();
        }
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
