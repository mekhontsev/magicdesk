package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Shared Start contents. Each host owns a separate instance and launch destination. */
final class StartMenuContent {
    interface Host {
        ApplicationCatalog.Snapshot catalog();
        List<AppItem> apps();
        default List<DesktopApplicationRepository.Entry> desktopApplications() {
            return java.util.Collections.emptyList();
        }
        default boolean hasRunningSection() { return false; }
        default List<StartMenuEntry> runningEntries() { return List.of(); }
        default String runningAppsError() { return ""; }
        default List<StartMenuEntry> entries(int section) {
            if (section == MENU_RUNNING) return runningEntries();
            return catalog().applications(apps(), desktopApplications());
        }
        default void onSectionShown(int section) { }
        default List<StartMenuEntry> searchEntries(int section) { return entries(MENU_APPS); }
        DesktopAutomationUiRegistry automation();
        void open(StartMenuEntry result);
        void dismiss();
        default void appContext(View view, AppItem app) { }
        default void fileContext(View view, DesktopFile file) { }
        default void populateTools(LinearLayout parent, int spacing, boolean capture) { }
        default void requestSearchFocus() { }
        default boolean mouseTouch(MotionEvent event) { return false; }
        default boolean mouseMotion(MotionEvent event) { return false; }
    }

    static final int MENU_RECENT = 0;
    static final int MENU_APPS = 1;
    static final int MENU_TOOLS = 2;
    static final int MENU_CAPTURE = 3;
    static final int MENU_RUNNING = 4;

    private final Activity mActivity;
    private final Host mHost;
    private final StartMenuScope mScope;
    private final DesktopUiFactory mUi;
    private final StartSearchController mSearchController;
    private final ApplicationCatalog mCatalog;
    private final Runnable mCatalogListener = this::catalogChanged;
    private ApplicationCatalog.Snapshot mObservedCatalog;

    private LinearLayout mPanel;
    private LinearLayout mContent;
    private LinearLayout mBody;
    private EditText mSearch;
    private LinearLayout mSearchRow;
    private StartLaunchControls mLaunchControls;
    private boolean mFocusable = true;
    private int mMode = MENU_RECENT;
    private int mPage;
    private int mSearchSelection;
    private String mSearchQuery = "";
    private int mColumns = 3;
    private int mRows = 3;
    private GridView mEntriesView;
    private List<StartMenuEntry> mVisibleEntries = List.of();
    private int mEntriesMode, mEntriesStart;
    private boolean mPrepared, mReleased;
    private ShellComposition.Start mAppearance;
    private final Runnable mAppearanceChanged = this::appearanceChanged;

    StartMenuContent(
            final Activity activity,
            final DesktopUiFactory ui,
            final StartMenuScope scope,
            final Host host) {
        mHost = host;
        mScope = scope;
        mAppearance = AppearanceStore.current(activity).composition().start();
        mMode = scope == StartMenuScope.APPLICATIONS ? MENU_APPS : MENU_RECENT;
        if (mAppearance.sections().stream().noneMatch(section -> sectionMode(section) == mMode)) mMode = MENU_APPS;
        mActivity = activity;
        mCatalog = ApplicationCatalog.get(activity);
        mUi = ui;
        mSearchController = new StartSearchController(
                activity, scope,
                this::onSearchResultsChanged);
    }

    // The touch observer only enables IME display; EditText retains click handling.
    @SuppressLint("ClickableViewAccessibility")
    LinearLayout create() {
        final LinearLayout menu = new LinearLayout(mActivity) {
            // Child application windows bypass the host Activity dispatch path.
            @Override
            public boolean dispatchTouchEvent(final MotionEvent event) {
                if (mHost.mouseTouch(event)) {
                    return true;
                }
                return super.dispatchTouchEvent(event);
            }

            @Override
            public boolean dispatchGenericMotionEvent(
                    final MotionEvent event) {
                if (mHost.mouseMotion(event)) {
                    return true;
                }
                return super.dispatchGenericMotionEvent(event);
            }

            @Override
            public void onWindowFocusChanged(final boolean hasWindowFocus) {
                super.onWindowFocusChanged(hasWindowFocus);
                if (hasWindowFocus && mScope == StartMenuScope.DESKTOP) {
                    StartMenuContent.this.focusSearch();
                }
            }
        };
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(14), dp(14), dp(14), dp(12));
        if (mScope == StartMenuScope.DESKTOP) {
            menu.setBackground(mUi.rounded(
                    UiColor.PANEL, dp(18),
                    UiColor.ACCENT));
        }
        mSearch = new EditText(mActivity);
        final int searchHint = mScope == StartMenuScope.APPLICATIONS
                ? R.string.search_phone_apps_hint : R.string.search_apps_hint;
        mSearch.setHint(searchHint);
        UiAppearance.hint(mSearch, UiColor.MUTED);
        UiAppearance.text(mSearch, UiColor.TEXT);
        mSearch.setTextSize(14);
        mSearch.setSingleLine(true);
        mSearch.setShowSoftInputOnFocus(false);
        mSearch.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mSearch.setShowSoftInputOnFocus(true);
            }
            return false;
        });
        mSearch.setPadding(dp(12), dp(8), dp(12), dp(8));
        UiAppearance.component(mSearch, ShellControls.Role.SEARCH_FIELD);
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
                        entries(mMode, true));
                renderBody();
            }

            @Override
            public void afterTextChanged(final Editable editable) {
            }
        });
        mSearch.setOnKeyListener((view, keyCode, event) ->
                handleSearchKey(keyCode, event));
        mSearch.setOnClickListener(view -> {
            focusSearch();
            mSearch.setShowSoftInputOnFocus(true);
            final android.view.inputmethod.InputMethodManager keyboard =
                    mActivity.getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) {
                keyboard.showSoftInput(mSearch,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
        mHost.automation().register(
                mSearch,
                "start.search",
                "text_field",
                mActivity.getString(searchHint));

        mContent = new LinearLayout(mActivity);
        mSearchRow = new LinearLayout(mActivity);
        mSearchRow.setGravity(Gravity.CENTER_VERTICAL);
        mSearchRow.addView(mSearch, new LinearLayout.LayoutParams(0, dp(48), 1));
        final android.widget.ImageButton refresh = new android.widget.ImageButton(mActivity);
        refresh.setImageDrawable(UiAppearance.symbol(mActivity, R.drawable.ic_file_refresh, UiColor.TEXT));
        refresh.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        refresh.setContentDescription(mActivity.getString(R.string.action_refresh));
        refresh.setTooltipText(mActivity.getString(R.string.action_refresh));
        refresh.setOnClickListener(view -> mCatalog.refreshApplications());
        mSearchRow.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        mHost.automation().register(refresh, "start.refresh", "button", mActivity.getString(R.string.action_refresh));
        mLaunchControls = new StartLaunchControls(mActivity, mUi, mHost.automation(), this::destinationChanged);
        mContent.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        contentParams.setMargins(0, dp(10), 0, 0);
        menu.addView(mContent, contentParams);
        mPanel = menu;
        AppearanceStore.listen(mAppearanceChanged);
        mHost.automation().register(
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
        for (var section : mAppearance.sections()) {
            if (section == ShellComposition.Section.RUNNING && !mHost.hasRunningSection()) continue;
            if (section == ShellComposition.Section.TOOLS && mScope != StartMenuScope.DESKTOP) continue;
            int title = switch (section) {
                case RECENT -> R.string.section_recent; case APPS -> R.string.section_apps;
                case RUNNING -> R.string.section_running; case TOOLS -> R.string.section_tools;
            };
            final var params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            params.setMargins(dp(2), 0, dp(2), 0);
            tabs.addView(createTab(title, sectionMode(section)), params);
        }
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
            mContent.addView(mSearchRow, searchParams);
            mContent.addView(mLaunchControls.view(), new LinearLayout.LayoutParams(-1, dp(52)));
        }

        mBody = new LinearLayout(mActivity);
        mBody.setOrientation(LinearLayout.VERTICAL);
        mContent.addView(mBody, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        renderBody();
    }

    void showSection(final int mode) {
        mMode = mode;
        mPage = 0;
        mSearchSelection = 0;
        mSearchQuery = "";
        if (mSearch != null && mSearch.length() > 0) {
            mSearch.setText("");
        }
        prepare(mFocusable);
        mHost.onSectionShown(mode);
    }

    boolean isUtilityVisible() {
        return isUtilityMode(mMode);
    }

    void prepare(final boolean focusable) {
        mFocusable = focusable;
        mLaunchControls.refresh();
        mSearch.setShowSoftInputOnFocus(false);
        mSearchController.update(
                mSearchQuery, entries(mMode, true));
        if (!mPrepared) {
            mPrepared = true;
            mCatalog.subscribe(mCatalogListener);
            mCatalog.refresh();
            mObservedCatalog = mCatalog.snapshot();
            RecentApplications.refresh(mActivity, () -> {
                if (mReleased || !mPrepared) return;
                mSearchController.update(mSearchQuery, entries(mMode, true));
                render();
            });
        }
        render();
    }

    private List<StartMenuEntry> entries(int section, boolean search) {
        if (section == MENU_RUNNING) return mHost.runningEntries();
        if (!search && section == MENU_RECENT) return RecentApplications.entries(mLaunchControls.recentScope())
                .stream().map(entry -> StartMenuEntry.recent(entry, mHost.apps())).toList();
        if ((search || section == MENU_APPS) && !mCatalog.snapshot().android().ready()) return List.of();
        List<StartMenuEntry> result = new ArrayList<>(search ? mHost.searchEntries(section) : mHost.entries(section));
        if (search || section == MENU_APPS) {
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (StartMenuEntry entry : result) keys.add(entry.stableKey());
            if (search) for (var application : mCatalog.snapshot().termux().entries()) {
                StartMenuEntry entry = StartMenuEntry.desktopApplication(application);
                if (keys.add(entry.stableKey())) result.add(entry);
            }
            if (search) for (var application : mCatalog.snapshot().guest().entries()) {
                StartMenuEntry entry = StartMenuEntry.desktopApplication(application);
                if (keys.add(entry.stableKey())) result.add(entry);
            }
            if (search) for (var recent : RecentApplications.entries(mLaunchControls.recentScope())) {
                boolean inCatalog = !recent.sourcePath().isEmpty() && result.stream().anyMatch(entry ->
                        entry.desktopApplication != null && entry.desktopApplication.desktopFilePath.equals(recent.sourcePath()));
                boolean androidApp = recent.shortcut().defaultLaunch && result.stream().anyMatch(entry ->
                        entry.app != null && entry.app.reference != null && entry.app.reference.equals(
                                AppReference.forTarget(recent.shortcut().application, recent.shortcut().launchTarget)));
                StartMenuEntry entry = StartMenuEntry.recent(recent, mHost.apps());
                if (!inCatalog && !androidApp && keys.add(entry.stableKey())) result.add(entry);
            }
            return ApplicationCatalog.sortedUnique(result);
        }
        return result;
    }

    private void destinationChanged() {
        mPage = 0;
        mSearchSelection = 0;
        mSearchController.update(mSearchQuery, entries(mMode, true));
        renderBody();
    }

    private void catalogChanged() {
        if (mReleased || !mPrepared) return;
        final var next = mCatalog.snapshot();
        final var previous = mObservedCatalog;
        mObservedCatalog = next;
        if (previous != null && previous.android().entries() == next.android().entries()
                && previous.android().ready() == next.android().ready()
                && previous.termux().entries() == next.termux().entries()
                && previous.guest().entries() == next.guest().entries()
                && (next.android().ready() || (previous.android().loading() == next.android().loading()
                        && previous.android().error().equals(next.android().error())))) {
            if (previous.termuxIcons() != next.termuxIcons()) refreshIcons(mBody);
            return;
        }
        mSearchController.update(mSearchQuery, entries(mMode, true));
        renderBody();
    }

    void pause() {
        mPrepared = false;
        mCatalog.unsubscribe(mCatalogListener);
        mLaunchControls.dismiss();
        mSearch.setShowSoftInputOnFocus(false);
        mSearchController.pause();
    }

    void release() {
        AppearanceStore.unlisten(mAppearanceChanged);
        mReleased = true;
        mPrepared = false;
        mCatalog.unsubscribe(mCatalogListener);
        mLaunchControls.dismiss();
        mSearchController.close();
        mEntriesView = null;
        mVisibleEntries = List.of();
    }

    private static int sectionMode(ShellComposition.Section section) {
        return switch (section) { case RECENT -> MENU_RECENT; case APPS -> MENU_APPS; case RUNNING -> MENU_RUNNING; case TOOLS -> MENU_TOOLS; };
    }

    private void appearanceChanged() {
        final var next = AppearanceStore.current(mActivity).composition().start();
        if (next.equals(mAppearance)) return;
        mAppearance = next;
        if (next.sections().stream().noneMatch(section -> sectionMode(section) == (mMode == MENU_CAPTURE ? MENU_TOOLS : mMode))) mMode = MENU_APPS;
        if (mPrepared) {
            boolean focused = mSearch.hasFocus();
            int selection = mSearch.getSelectionStart();
            render();
            if (focused) { mSearch.requestFocus(); mSearch.setSelection(Math.max(0, Math.min(selection, mSearch.length()))); }
        }
    }

    StartDisplaySelector.Target destination() { return mLaunchControls.target(); }
    DesktopLaunchPresentation presentation() { return mLaunchControls.presentation(); }

    void focusSearch() {
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
        String anchor = null;
        int anchorOffset = 0;
        if (mEntriesView != null && mEntriesMode == mMode && mEntriesView.getChildCount() > 0
                && mAppearance.navigation() == ShellComposition.Navigation.SCROLL) {
            int first = mEntriesView.getFirstVisiblePosition();
            if (first < mVisibleEntries.size()) anchor = mVisibleEntries.get(first).stableKey();
            anchorOffset = mEntriesView.getChildAt(0).getTop();
        }
        mEntriesView = null;
        mBody.removeAllViews();

        final var androidApps = mCatalog.snapshot().android();
        if ((mMode == MENU_APPS || !mSearchQuery.trim().isEmpty()) && !androidApps.ready()) {
            final TextView status = new TextView(mActivity);
            status.setText(androidApps.error().isEmpty()
                    ? mActivity.getString(R.string.apps_loading) : androidApps.error());
            UiAppearance.text(status, UiColor.MUTED);
            status.setTextSize(14);
            status.setGravity(Gravity.CENTER);
            mBody.addView(status, new LinearLayout.LayoutParams(-1, 0, 1));
            mHost.automation().register(status, "start.catalog", "status", status.getText().toString());
            return;
        }

        if (!mSearchQuery.trim().isEmpty()) {
            renderSearchResults();
            return;
        }

        final List<StartMenuEntry> menuApps = entries(mMode, false);
        final String recentError = mMode == MENU_RECENT ? RecentApplications.error(mLaunchControls.recentScope())
                : mMode == MENU_RUNNING ? mHost.runningAppsError() : "";
        if (menuApps.isEmpty() || !recentError.isEmpty()) {
            final TextView empty = new TextView(mActivity);
            empty.setText(recentError.isEmpty() ? mActivity.getString(mMode == MENU_RECENT
                    ? R.string.recent_apps_empty
                    : R.string.status_no_apps) : recentError);
            UiAppearance.text(empty, UiColor.MUTED);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            mBody.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            return;
        }
        final boolean list = mAppearance.presentation() == ShellComposition.Presentation.LIST;
        final boolean pages = mAppearance.navigation() == ShellComposition.Navigation.PAGES;
        final int pageSize = pages ? getPageSize() : menuApps.size();
        final int pageCount = Math.max(
                1, (menuApps.size() + pageSize - 1) / pageSize);
        if (mPage >= pageCount) {
            mPage = pageCount - 1;
        }
        if (mPage < 0) {
            mPage = 0;
        }
        final GridView grid = new GridView(mActivity) {
            @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                updateEntryCapacity(this);
            }
        };
        grid.setNumColumns(list ? 1 : getColumnCount());
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(mAppearance.gapDp()));
        grid.setVerticalSpacing(dp(mAppearance.gapDp()));
        grid.setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        final int start = mPage * pageSize;
        final int end = Math.min(menuApps.size(), start + pageSize);
        final List<StartMenuEntry> entries = List.copyOf(menuApps.subList(start, end));
        mVisibleEntries = entries; mEntriesStart = start; mEntriesMode = mMode; mEntriesView = grid;
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return entries.size(); }
            @Override public Object getItem(int position) { return entries.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                return list ? createSearchRow(entries.get(position), false, recycled) : createAppTile(entries.get(position), recycled);
            }
        });
        final LinearLayout.LayoutParams gridParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        gridParams.setMargins(0, dp(8), 0, dp(8));
        mBody.addView(grid, gridParams);
        if (pages) addPager(pageCount);
        if (anchor != null) for (int i = 0; i < entries.size(); i++) {
            if (anchor.equals(entries.get(i).stableKey())) {
                restoreEntryAnchor(grid, i, anchorOffset);
                break;
            }
        }
    }

    private void restoreEntryAnchor(GridView grid, int position, int offset) {
        // GridView resolves its actual column count during measurement, before restoring selection.
        grid.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                grid.removeOnLayoutChangeListener(this);
                if (grid == mEntriesView && !mReleased) grid.setSelectionFromTop(position, offset);
            }
        });
    }

    private void updateEntryCapacity(GridView grid) {
        if (grid != mEntriesView || grid.getWidth() <= 0 || grid.getHeight() <= 0 || grid.getChildCount() == 0) return;
        boolean list = mAppearance.presentation() == ShellComposition.Presentation.LIST;
        int gap = dp(mAppearance.gapDp());
        int columns = list ? 1 : StartMenuLayout.columns(grid.getWidth(), dp(mAppearance.tileWidthDp()), dp(mAppearance.iconSizeDp() + 20), gap);
        int rows = StartMenuLayout.rows(grid.getHeight(), grid.getChildAt(0).getMeasuredHeight(), gap);
        if (columns == mColumns && rows == mRows) return;
        int first = mEntriesStart + grid.getFirstVisiblePosition();
        mColumns = columns; mRows = rows;
        mPage = mAppearance.navigation() == ShellComposition.Navigation.PAGES ? first / getPageSize() : 0;
        // Layout completion, not a timed settling delay; detached/replaced views discard the update.
        grid.post(() -> { if (grid == mEntriesView && grid.isAttachedToWindow()) renderBody(); });
    }

    private Button createTab(final int textResId, final int mode) {
        final Button button = mUi.actionButton(
                textResId,
                tabSelected(mode)
                        ? UiColor.ACCENT
                        : UiColor.SURFACE);
        UiAppearance.component(button, ShellControls.Role.TAB);
        button.setSelected(tabSelected(mode));
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
                mPanel.post(mHost::requestSearchFocus);
                return;
            }
            prepare(mFocusable);
            mHost.onSectionShown(mode);
        });
        mHost.automation().register(
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
        mHost.populateTools(tools, dp(10), false);

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
        mHost.populateTools(capture, dp(10), true);

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

    private record EntryViews(ImageView icon, TextView name, TextView detail) { }

    private View createAppTile(final StartMenuEntry application, View recycled) {
        if (recycled instanceof LinearLayout tile && tile.getTag() instanceof EntryViews views) {
            bindEntry(tile, views, application, false);
            return tile;
        }
        final LinearLayout tile = new LinearLayout(mActivity);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(6), dp(5), dp(6), dp(5));
        UiAppearance.component(tile, ShellControls.Role.APP_TILE);
        tile.setClickable(true);
        tile.setFocusable(true);

        final ImageView icon = new ImageView(mActivity);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(mAppearance.iconSizeDp()), dp(mAppearance.iconSizeDp())));

        final TextView label = new TextView(mActivity);
        UiAppearance.text(label, UiColor.TEXT);
        label.setTextSize(11);
        UiAppearance.componentText(label, ShellControls.Role.APP_TILE);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(4), 0, 0);
        tile.addView(label, labelParams);
        var views = new EntryViews(icon, label, null); tile.setTag(views);
        bindEntry(tile, views, application, false);
        return tile;
    }

    private void addPager(final int pageCount) {
        final LinearLayout pager = new LinearLayout(mActivity);
        pager.setOrientation(LinearLayout.HORIZONTAL);
        pager.setGravity(Gravity.CENTER_VERTICAL);

        final Button previous = mUi.actionButton(
                R.string.action_previous,
                UiColor.SURFACE);
        previous.setEnabled(mPage > 0);
        previous.setOnClickListener(view -> {
            if (mPage > 0) {
                mPage--;
                renderBody();
            }
        });
        mHost.automation().register(
                previous, "start.page.previous", "button",
                previous.getText());
        pager.addView(previous, new LinearLayout.LayoutParams(
                dp(108), LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView page = new TextView(mActivity);
        page.setText(mActivity.getString(
                R.string.page_status,
                Integer.valueOf(mPage + 1),
                Integer.valueOf(pageCount)));
        UiAppearance.text(page, UiColor.MUTED);
        page.setTextSize(13);
        page.setGravity(Gravity.CENTER);
        pager.addView(page, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final Button next = mUi.actionButton(
                R.string.action_next,
                UiColor.SURFACE);
        next.setEnabled(mPage + 1 < pageCount);
        next.setOnClickListener(view -> {
            if (mPage + 1 < pageCount) {
                mPage++;
                renderBody();
            }
        });
        mHost.automation().register(
                next, "start.page.next", "button", next.getText());
        pager.addView(next, new LinearLayout.LayoutParams(
                dp(108), LinearLayout.LayoutParams.WRAP_CONTENT));
        mBody.addView(pager, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void renderSearchResults() {
        final List<StartMenuEntry> matches =
                mSearchController.results(getSearchResultLimit());
        if (matches.isEmpty()) {
            final TextView empty = new TextView(mActivity);
            empty.setText(R.string.search_no_results);
            UiAppearance.text(empty, UiColor.MUTED);
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
                            index == mSearchSelection, null),
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(StartMenuLayout.rowHeight(mAppearance.iconSizeDp()))));
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
        final List<StartMenuEntry> matches =
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
            final StartMenuEntry result = matches.get(
                    Math.min(mSearchSelection, matches.size() - 1));
            openSearchResult(result);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            mHost.dismiss();
            return true;
        }
        return false;
    }

    private View createSearchRow(
            final StartMenuEntry result,
            final boolean selected, View recycled) {
        if (recycled instanceof LinearLayout row && row.getTag() instanceof EntryViews views) {
            bindEntry(row, views, result, selected);
            return row;
        }
        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(5), dp(8), dp(5));
        UiAppearance.component(row, ShellControls.Role.APP_TILE);
        row.setSelected(selected);
        row.setClickable(true);
        row.setFocusable(true);

        final ImageView icon = new ImageView(mActivity);
        bindIcon(icon, result);
        row.addView(icon, new LinearLayout.LayoutParams(dp(mAppearance.iconSizeDp()), dp(mAppearance.iconSizeDp())));

        final LinearLayout labels = new LinearLayout(mActivity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, 0, 0);
        final TextView name = new TextView(mActivity);
        name.setText(result.label);
        UiAppearance.text(name, UiColor.TEXT);
        name.setTextSize(14);
        labels.setDuplicateParentStateEnabled(true);
        UiAppearance.componentText(name, ShellControls.Role.APP_TILE);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        final TextView detail = new TextView(mActivity);
        detail.setText(result.detail);
        UiAppearance.text(detail, UiColor.MUTED);
        detail.setTextSize(10);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        labels.addView(name);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        var views = new EntryViews(icon, name, detail); row.setTag(views);
        bindEntry(row, views, result, selected);
        return row;
    }

    private void bindEntry(View view, EntryViews views, StartMenuEntry entry, boolean selected) {
        views.name().setText(entry.label);
        if (views.detail() != null) views.detail().setText(entry.detail);
        bindIcon(views.icon(), entry);
        view.setSelected(selected);
        view.setContentDescription(entry.label + ", " + entry.detail);
        view.setOnClickListener(v -> mHost.open(entry));
        view.setOnLongClickListener(null); view.setOnContextClickListener(null);
        bindContextMenu(view, entry);
        mHost.automation().register(view, "start.app." + DesktopAutomationUiRegistry.identitySegment(entry.stableKey()),
                "application", entry.label, entry.app == null ? "" : entry.app.packageName, entry.task == null ? -1 : entry.task.taskId);
    }

    private int searchIcon(final StartMenuEntry result) {
        if (result.kind == StartMenuEntry.Kind.TERMINALS) { return R.drawable.ic_file_console; }
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
                return R.drawable.ic_sections;
            }
            return R.drawable.ic_settings;
        }
        if (result.action == StartMenuEntry.Action.SCREENSHOT) {
            return R.drawable.ic_camera;
        }
        if (result.action == StartMenuEntry.Action.SCREEN_RECORDING) {
            return R.drawable.ic_video;
        }
        return R.drawable.ic_show_desktop;
    }

    private void bindIcon(final ImageView icon, final StartMenuEntry entry) {
        icon.setTag(entry);
        if (entry.app != null) { UiApplicationIcon.bind(icon, entry.app, UiColor.TEXT); }
        else if (entry.builtIn != null) { UiAppearance.icon(icon, searchIcon(entry), UiColor.TEXT); }
        else if (entry.desktopApplication != null) {
            icon.setImageDrawable(DesktopApplicationIconResolver.resolve(
                    mActivity, entry.desktopApplication.shortcut));
        } else { UiAppearance.icon(icon, searchIcon(entry), UiColor.TEXT); }
    }

    private void refreshIcons(View view) {
        if (view instanceof ImageView icon && icon.getTag() instanceof StartMenuEntry entry) {
            if (entry.desktopApplication != null) bindIcon(icon, entry);
        } else if (view instanceof android.view.ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) refreshIcons(group.getChildAt(i));
    }

    private void bindContextMenu(View view, StartMenuEntry entry) {
        if (entry.app != null) mHost.appContext(view, entry.app);
        else if (entry.desktopApplication != null) {
            var application = entry.desktopApplication;
            if (application.desktopFile != null) mHost.fileContext(view, application.desktopFile);
            else if (application.shortcut.execBackend == DesktopExecBackend.TERMUX) {
                view.setOnLongClickListener(anchor -> {
                    final boolean userShortcut = (entry.recent == null
                            || entry.recent.termuxPackage().equals(IntegrationPackage.TERMUX.selected()))
                            && mCatalog.snapshot().termux().entries().stream().anyMatch(current -> current.userShortcut
                                    && current.desktopFilePath.equals(application.desktopFilePath));
                    final boolean scale = application.shortcut.graphics != null
                            && !application.desktopFilePath.isBlank();
                    if (!userShortcut && !scale) return false;
                    final android.widget.PopupMenu menu = new android.widget.PopupMenu(mActivity, anchor);
                    if (scale) menu.getMenu().add(R.string.app_presentation_scale)
                            .setOnMenuItemClickListener(item -> {
                                GraphicalScaleDialog.show(mActivity, entry.label, entry.recent == null
                                        ? IntegrationPackage.TERMUX.selected() : entry.recent.termuxPackage(),
                                        application.desktopFilePath);
                                return true;
                            });
                    if (userShortcut) menu.getMenu().add(R.string.action_delete_shortcut)
                            .setOnMenuItemClickListener(item -> {
                                TermuxShortcutDialog.confirmDelete(mActivity, entry);
                                return true;
                            });
                    menu.show();
                    return true;
                });
                view.setOnContextClickListener(View::performLongClick);
            }
        }
    }

    private static String modeName(final int mode) {
        switch (mode) {
            case MENU_RUNNING:
                return "running";
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

    private void openSearchResult(final StartMenuEntry result) {
        mHost.open(result);
    }

    private int getSearchResultLimit() {
        return Math.max(4, getRowCount() * 3);
    }

    private void onSearchResultsChanged() {
        if (mBody != null && !mSearchQuery.trim().isEmpty()) {
            renderBody();
        }
    }

    private int getColumnCount() {
        return mColumns;
    }

    private int getPageSize() {
        return getColumnCount() * getRowCount();
    }

    private int getRowCount() {
        return mRows;
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
