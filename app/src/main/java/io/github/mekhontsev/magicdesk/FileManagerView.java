package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;
import java.util.Set;

final class FileManagerView {
    interface Listener {
        void onBack();
        void onForward();
        void onUp();
        void onRefresh();
        void onNavigate(String path);
        void onItemClick(
                ShellFileInfo file, int metaState, long eventTime);
        void onSelectionChanged(ShellFileInfo file, boolean selected);
        boolean onContextMenu(View anchor, ShellFileInfo file);
        void onStartDrag(
                View source, ShellFileInfo file, int metaState);
        boolean onDrop(DragEvent event, ShellFileInfo destination);
        void onNewWindow();
        void onNewFile();
        void onNewFolder();
        void onCopy();
        void onCut();
        void onPaste();
        void onRename();
        void onDelete();
        void onProperties();
        void onOpenWith();
        void onOpenConsole();
        void onOpenTerminal();
        void onShowHiddenChanged(boolean showHidden);
        void onSortChanged(int sortMode);
        void onSortDirectionChanged(boolean ascending);
        void onViewModeChanged(boolean details);
        void onFilterChanged(String query);
    }

    private static final int COLOR_BACKGROUND = Color.rgb(9, 13, 20);
    private static final int COLOR_SURFACE = Color.rgb(20, 27, 38);
    private static final int COLOR_TEXT = Color.rgb(232, 238, 245);
    private static final int COLOR_MUTED = Color.rgb(157, 170, 184);

    private final Context mContext;
    private final Listener mListener;
    private final LinearLayout mRoot;
    private final EditText mPath;
    private final ListView mList;
    private final ShellFileListAdapter mAdapter;
    private final TextView mEmpty;
    private final TextView mStatus;
    private final LinearLayout mFilterPanel;
    private final EditText mFilter;
    private final ImageButton mBack;
    private final ImageButton mForward;
    private final ImageButton mUp;
    private final ImageButton mRefresh;
    private final ImageButton mNewWindow;
    private final ImageButton mCopy;
    private final ImageButton mCut;
    private final ImageButton mPaste;
    private final ImageButton mRename;
    private final ImageButton mDelete;
    private final ImageButton mProperties;
    private final ImageButton mOpenWith;
    private final ImageButton mTerminal;
    private final CheckBox mHidden;
    private final Spinner mSort;
    private final ImageButton mSortDirection;
    private final Spinner mViewMode;
    private boolean mSortAscending = true;

    FileManagerView(final Context context, final Listener listener) {
        mContext = context;
        mListener = listener;
        mRoot = new LinearLayout(context);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setBackgroundColor(COLOR_BACKGROUND);
        final int horizontalPadding = dp(10);
        final int verticalPadding = dp(8);
        mRoot.setPadding(
                horizontalPadding,
                verticalPadding,
                horizontalPadding,
                verticalPadding);
        mRoot.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            final Insets insets = windowInsets.getInsets(
                    WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout());
            view.setPadding(
                    horizontalPadding + insets.left,
                    verticalPadding + insets.top,
                    horizontalPadding + insets.right,
                    verticalPadding + insets.bottom);
            return windowInsets;
        });

        final LinearLayout navigation = horizontal();
        mBack = iconCommand(
                R.drawable.ic_file_back,
                R.string.file_manager_back,
                view -> listener.onBack());
        mForward = iconCommand(
                R.drawable.ic_file_forward,
                R.string.file_manager_forward,
                view -> listener.onForward());
        mUp = iconCommand(
                R.drawable.ic_file_up,
                R.string.file_manager_up,
                view -> listener.onUp());
        navigation.addView(mBack, compactButton());
        navigation.addView(mForward, compactButton());
        navigation.addView(mUp, compactButton());

        mPath = new EditText(context);
        mPath.setSingleLine(true);
        mPath.setHint(R.string.file_manager_path_hint);
        mPath.setTextColor(COLOR_TEXT);
        mPath.setHintTextColor(COLOR_MUTED);
        mPath.setSelectAllOnFocus(false);
        mPath.setBackgroundColor(COLOR_SURFACE);
        mPath.setPadding(dp(10), 0, dp(10), 0);
        mPath.setOnEditorActionListener((view, actionId, event) -> {
            navigateFromAddress();
            return true;
        });
        navigation.addView(mPath, new LinearLayout.LayoutParams(
                0, dp(42), 1f));
        navigation.addView(iconCommand(
                R.drawable.ic_file_forward,
                R.string.file_manager_go,
                view -> navigateFromAddress()), compactButton());
        mRefresh = iconCommand(
                R.drawable.ic_file_refresh,
                R.string.action_refresh,
                view -> listener.onRefresh());
        navigation.addView(mRefresh, compactButton());
        mRoot.addView(navigation, matchWrap());

        mFilterPanel = horizontal();
        mFilterPanel.setVisibility(View.GONE);
        mFilter = new EditText(context);
        mFilter.setSingleLine(true);
        mFilter.setHint(R.string.file_manager_filter_hint);
        mFilter.setTextColor(COLOR_TEXT);
        mFilter.setHintTextColor(COLOR_MUTED);
        mFilter.setBackgroundColor(COLOR_SURFACE);
        mFilter.setPadding(dp(10), 0, dp(10), 0);
        mFilter.addTextChangedListener(new TextWatcher() {
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
                listener.onFilterChanged(text.toString());
            }

            @Override
            public void afterTextChanged(final Editable editable) {
            }
        });
        mFilterPanel.addView(mFilter, new LinearLayout.LayoutParams(
                0, dp(42), 1f));
        mFilterPanel.addView(iconCommand(
                android.R.drawable.ic_menu_close_clear_cancel,
                R.string.file_manager_filter_clear,
                view -> clearFilter()), compactButton());
        mRoot.addView(mFilterPanel, matchWrap());

        final LinearLayout commands = horizontal();
        mNewWindow = iconCommand(
                R.drawable.ic_file_new_window,
                R.string.file_manager_new_window,
                view -> listener.onNewWindow());
        commands.addView(mNewWindow, compactButton());
        commands.addView(iconCommand(
                R.drawable.ic_desktop_file_document,
                R.string.action_new_file,
                view -> listener.onNewFile()), compactButton());
        commands.addView(iconCommand(
                R.drawable.ic_desktop_folder,
                R.string.action_new_folder,
                view -> listener.onNewFolder()), compactButton());
        mCopy = iconCommand(
                R.drawable.ic_file_copy,
                R.string.file_manager_copy,
                view -> listener.onCopy());
        mCut = iconCommand(
                R.drawable.ic_file_cut,
                R.string.file_manager_cut,
                view -> listener.onCut());
        mPaste = iconCommand(
                R.drawable.ic_file_paste,
                R.string.file_manager_paste,
                view -> listener.onPaste());
        mRename = iconCommand(
                R.drawable.ic_file_rename,
                R.string.action_rename,
                view -> listener.onRename());
        mDelete = iconCommand(
                R.drawable.ic_file_delete,
                R.string.action_delete,
                view -> listener.onDelete());
        mProperties = iconCommand(
                R.drawable.ic_file_properties,
                R.string.file_manager_properties,
                view -> listener.onProperties());
        mOpenWith = iconCommand(
                R.drawable.ic_file_open_with,
                R.string.file_manager_open_with,
                view -> listener.onOpenWith());
        final ImageButton console = iconCommand(
                R.drawable.ic_file_console,
                R.string.file_manager_console,
                view -> listener.onOpenConsole());
        mTerminal = iconCommand(
                R.drawable.ic_file_console,
                R.string.file_manager_terminal,
                view -> listener.onOpenTerminal());
        commands.addView(mCopy, compactButton());
        commands.addView(mCut, compactButton());
        commands.addView(mPaste, compactButton());
        commands.addView(mRename, compactButton());
        commands.addView(new View(context),
                new LinearLayout.LayoutParams(dp(10), 1));
        commands.addView(mDelete, compactButton());
        commands.addView(mProperties, compactButton());
        commands.addView(mOpenWith, compactButton());
        commands.addView(console, compactButton());
        commands.addView(mTerminal, compactButton());

        mHidden = new CheckBox(context);
        mHidden.setText(R.string.file_manager_show_hidden);
        mHidden.setTextColor(COLOR_TEXT);
        mHidden.setOnCheckedChangeListener((button, checked) ->
                listener.onShowHiddenChanged(checked));
        commands.addView(mHidden, wrapWrap());
        mSort = spinner(new String[]{
                context.getString(R.string.file_manager_sort_name),
                context.getString(R.string.file_manager_sort_modified),
                context.getString(R.string.file_manager_sort_size)
        });
        mSort.setOnItemSelectedListener(new SimpleItemSelectedListener(
                position -> listener.onSortChanged(position)));
        commands.addView(mSort,
                new LinearLayout.LayoutParams(dp(138), dp(40)));
        mSortDirection = iconCommand(
                R.drawable.ic_file_sort,
                R.string.file_manager_sort_ascending,
                view -> listener.onSortDirectionChanged(
                        !mSortAscending));
        commands.addView(mSortDirection, compactButton());
        mViewMode = spinner(new String[]{
                context.getString(R.string.file_manager_view_list),
                context.getString(R.string.file_manager_view_details)
        });
        mViewMode.setSelection(1);
        mViewMode.setOnItemSelectedListener(new SimpleItemSelectedListener(
                position -> listener.onViewModeChanged(position == 1)));
        commands.addView(mViewMode,
                new LinearLayout.LayoutParams(dp(126), dp(40)));
        commands.addView(iconCommand(
                android.R.drawable.ic_menu_search,
                R.string.file_manager_filter,
                view -> focusFilter()), compactButton());
        final HorizontalScrollView commandsScroll =
                new HorizontalScrollView(context);
        commandsScroll.setHorizontalScrollBarEnabled(false);
        commandsScroll.addView(commands, wrapWrap());
        mRoot.addView(commandsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        final boolean wide = context.getResources().getConfiguration()
                .screenWidthDp >= 720;
        final LinearLayout content = horizontal();
        final LinearLayout bookmarks = createBookmarks(wide);
        if (wide) {
            content.addView(bookmarks, new LinearLayout.LayoutParams(
                    dp(176), ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            final HorizontalScrollView bookmarkScroll =
                    new HorizontalScrollView(context);
            bookmarkScroll.setHorizontalScrollBarEnabled(false);
            bookmarkScroll.addView(bookmarks, wrapWrap());
            mRoot.addView(bookmarkScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        }

        final LinearLayout browser = new LinearLayout(context);
        browser.setOrientation(LinearLayout.VERTICAL);
        final FrameLayout listFrame = new FrameLayout(context);
        mList = new ListView(context);
        mList.setDivider(new ColorDrawable(Color.rgb(38, 48, 61)));
        mList.setDividerHeight(1);
        mList.setBackgroundColor(COLOR_BACKGROUND);
        mAdapter = new ShellFileListAdapter(
                context,
                listener::onItemClick,
                listener::onSelectionChanged,
                listener::onContextMenu,
                (row, file, metaState) -> {
                    listener.onStartDrag(row, file, metaState);
                    return true;
                },
                listener::onDrop);
        mList.setAdapter(mAdapter);
        listFrame.setOnDragListener((view, event) -> {
            if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) {
                return event.getClipDescription() != null;
            }
            if (event.getAction() == DragEvent.ACTION_DROP) {
                return listener.onDrop(event, null);
            }
            return true;
        });
        listFrame.addView(mList, matchMatch());
        mEmpty = new TextView(context);
        mEmpty.setTextColor(COLOR_MUTED);
        mEmpty.setTextSize(16f);
        mEmpty.setGravity(Gravity.CENTER);
        mEmpty.setVisibility(View.GONE);
        listFrame.addView(mEmpty, matchMatch());
        browser.addView(listFrame,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(browser, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        mRoot.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mStatus = new TextView(context);
        mStatus.setTextColor(COLOR_MUTED);
        mStatus.setTextSize(12f);
        mStatus.setGravity(Gravity.CENTER_VERTICAL);
        mStatus.setSingleLine(true);
        mRoot.addView(mStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        updateSelection(0, false);
    }

    View root() {
        return mRoot;
    }

    void setPath(final String path) {
        if (!mPath.hasFocus()) {
            mPath.setText(path);
            mPath.setSelection(mPath.length());
        }
    }

    void setFiles(
            final List<ShellFileInfo> files,
            final Set<String> selectedPaths,
            final boolean details) {
        mAdapter.set(files, selectedPaths, details);
        mEmpty.setText(R.string.file_manager_empty);
        mEmpty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        mList.setVisibility(files.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void setLoading() {
        mEmpty.setText(R.string.file_manager_loading);
        mEmpty.setVisibility(View.VISIBLE);
        mList.setVisibility(View.GONE);
    }

    void setStatus(final String text) {
        mStatus.setText(text);
    }

    void setNavigationEnabled(
            final boolean back, final boolean forward, final boolean up) {
        mBack.setEnabled(back);
        mForward.setEnabled(forward);
        mUp.setEnabled(up);
    }

    void updateSelection(final int count, final boolean hasClipboard) {
        final boolean any = count > 0;
        final boolean single = count == 1;
        mCopy.setEnabled(any);
        mCut.setEnabled(any);
        mPaste.setEnabled(hasClipboard);
        mRename.setEnabled(single);
        mDelete.setEnabled(any);
        mProperties.setEnabled(single);
        mOpenWith.setEnabled(single);
    }

    void setTerminalVisible(final boolean visible) {
        if (visible) {
            try {
                mTerminal.setImageDrawable(mContext.getPackageManager()
                        .getApplicationIcon(TermuxIntegration.PACKAGE_NAME));
                mTerminal.setImageTintList(null);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Keep the terminal fallback if the package disappeared.
            }
        }
        mTerminal.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    void setShellReady(final boolean ready) {
        mPath.setEnabled(ready);
        mRefresh.setEnabled(ready);
        mNewWindow.setEnabled(ready);
        mList.setEnabled(ready);
    }

    void focusPath() {
        mPath.requestFocus();
        mPath.selectAll();
    }

    void focusFilter() {
        mFilterPanel.setVisibility(View.VISIBLE);
        mFilter.requestFocus();
        mFilter.selectAll();
    }

    void clearFilter() {
        mFilter.setText("");
        mFilterPanel.setVisibility(View.GONE);
    }

    void setShowHidden(final boolean showHidden) {
        mHidden.setChecked(showHidden);
    }

    void setSortAscending(final boolean ascending) {
        mSortAscending = ascending;
        mSortDirection.setRotation(ascending ? 0f : 180f);
        mSortDirection.setContentDescription(mContext.getString(
                ascending
                        ? R.string.file_manager_sort_ascending
                        : R.string.file_manager_sort_descending));
        mSortDirection.setTooltipText(mSortDirection.getContentDescription());
    }

    private LinearLayout createBookmarks(final boolean vertical) {
        final LinearLayout bookmarks = new LinearLayout(mContext);
        bookmarks.setOrientation(vertical
                ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        bookmarks.setPadding(0, 0, dp(8), 0);
        addBookmark(bookmarks, R.string.file_manager_home,
                "/storage/emulated/0", vertical);
        addBookmark(bookmarks, R.string.file_manager_desktop,
                "/storage/emulated/0/Desktop", vertical);
        addBookmark(bookmarks, R.string.file_manager_downloads,
                "/storage/emulated/0/Download", vertical);
        addBookmark(bookmarks, R.string.file_manager_shell_access,
                "/", vertical);
        return bookmarks;
    }

    private void addBookmark(
            final LinearLayout parent,
            final int label,
            final String path,
            final boolean vertical) {
        final Button button = textCommand(label,
                view -> mListener.onNavigate(path));
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        parent.addView(button, vertical
                ? new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(42))
                : actionButton());
    }

    private void navigateFromAddress() {
        final String path = mPath.getText().toString().trim();
        if (path.length() > 0) {
            mListener.onNavigate(path);
        }
    }

    private Button textCommand(
            final int text, final View.OnClickListener listener) {
        return command(mContext.getString(text),
                mContext.getString(text), listener);
    }

    private Button command(
            final String text,
            final String description,
            final View.OnClickListener listener) {
        final Button button = new Button(mContext);
        button.setText(text);
        button.setContentDescription(description);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(13f);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackgroundColor(COLOR_SURFACE);
        button.setOnClickListener(listener);
        return button;
    }

    private ImageButton iconCommand(
            final int drawable,
            final int description,
            final View.OnClickListener listener) {
        final ImageButton button = new ImageButton(mContext);
        button.setImageResource(drawable);
        button.setImageTintList(new ColorStateList(
                new int[][]{
                    new int[]{-android.R.attr.state_enabled},
                    new int[0]
                },
                new int[]{COLOR_MUTED, COLOR_TEXT}));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackgroundColor(COLOR_SURFACE);
        button.setContentDescription(mContext.getString(description));
        button.setTooltipText(mContext.getString(description));
        button.setOnClickListener(listener);
        return button;
    }

    private Spinner spinner(final String[] items) {
        final Spinner spinner = new Spinner(mContext);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(
                mContext,
                android.R.layout.simple_spinner_dropdown_item,
                items);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private LinearLayout horizontal() {
        final LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams compactButton() {
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(dp(46), dp(42));
        params.setMarginEnd(dp(4));
        return params;
    }

    private LinearLayout.LayoutParams actionButton() {
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        params.setMarginEnd(dp(4));
        return params;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static FrameLayout.LayoutParams matchMatch() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(final int value) {
        return Math.round(value * mContext.getResources()
                .getDisplayMetrics().density);
    }

    private static final class SimpleItemSelectedListener
            implements android.widget.AdapterView.OnItemSelectedListener {
        interface Callback {
            void selected(int position);
        }

        private final Callback mCallback;

        SimpleItemSelectedListener(final Callback callback) {
            mCallback = callback;
        }

        @Override
        public void onItemSelected(
                final android.widget.AdapterView<?> parent,
                final View view,
                final int position,
                final long id) {
            mCallback.selected(position);
        }

        @Override
        public void onNothingSelected(
                final android.widget.AdapterView<?> parent) {
        }
    }
}
