package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.appwidget.AppWidgetHostView;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DesktopWorkspaceController {
    private static final String TAG = "MagicDeskWorkspace";
    private static final String APP_PREFIX = "app:";
    private static final String FILE_PREFIX = "file:";

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final DesktopContentStore mContent;
    private final DesktopItemViewFactory mViews;
    private final DesktopFolderController mFolder;
    private final DesktopWidgetController mWidgets;

    private DesktopGridLayout mGrid;
    private List<AppItem> mApps = new ArrayList<>();
    private List<DesktopFile> mFiles = new ArrayList<>();
    private final Map<String, DesktopPlacement> mRenderedPlacements =
            new LinkedHashMap<>();
    private int mLastCapacity;
    private int mEditingWidgetId = -1;

    DesktopWorkspaceController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
        mContent = new DesktopContentStore();
        mViews = new DesktopItemViewFactory(activity, ui);
        mFolder = new DesktopFolderController(
                activity,
                new DesktopFileRepository(activity),
                this::onFilesChanged,
                activity::onDesktopMetadataChanged);
        mWidgets = new DesktopWidgetController(
                activity, ui, this::onWidgetsChanged);
    }

    DesktopContentStore content() {
        return mContent;
    }

    DesktopGridLayout createGrid() {
        final DesktopGridLayout grid = new DesktopGridLayout(
                mActivity,
                desktopDp(112, 82),
                desktopDp(102, 78));
        grid.setListener(new DesktopGridLayout.Listener() {
            @Override
            public void onGridSizeChanged(final int columns, final int rows) {
                final int capacity = columns * rows;
                render(mApps);
                if (capacity > mLastCapacity) {
                    mFolder.refresh(true, capacity);
                }
                mLastCapacity = capacity;
            }

            @Override
            public void onItemDropped(
                    final String itemId,
                    final int column,
                    final int row) {
                moveItem(itemId, column, row);
            }
        });
        mGrid = grid;
        return grid;
    }

    void start() {
        mFolder.start();
        mWidgets.start();
    }

    void stop() {
        mFolder.stop();
        mWidgets.stop();
    }

    void release() {
        mFolder.release();
        mWidgets.release();
        mGrid = null;
    }

    boolean handleActivityResult(
            final int requestCode,
            final int resultCode,
            final Intent data) {
        return mWidgets.handleActivityResult(
                requestCode, resultCode, data);
    }

    void render(final List<AppItem> apps) {
        mApps = apps == null ? new ArrayList<>() : new ArrayList<>(apps);
        if (mGrid == null
                || mGrid.getColumnCount() <= 0
                || mGrid.getRowCount() <= 0) {
            return;
        }
        final List<Entry> entries = collectEntries();
        final DisplayProfileStore.Profile profile =
                mActivity.getDisplayProfile();
        final List<DesktopPlacementEngine.Request> requests =
                new ArrayList<>();
        addRequests(entries, profile, requests, true);
        addRequests(entries, profile, requests, false);
        final Map<String, DesktopPlacement> arranged =
                DesktopPlacementEngine.arrange(
                        requests,
                        mGrid.getColumnCount(),
                        mGrid.getRowCount());

        final List<Entry> hiddenFiles = new ArrayList<>();
        for (final Entry entry : entries) {
            if (entry.file != null && !arranged.containsKey(entry.itemId)) {
                hiddenFiles.add(entry);
            }
        }
        DesktopPlacement overflowPlacement = null;
        if (!hiddenFiles.isEmpty()) {
            for (int index = entries.size() - 1; index >= 0; index--) {
                final Entry entry = entries.get(index);
                if (entry.file == null) {
                    continue;
                }
                overflowPlacement = arranged.remove(entry.itemId);
                if (overflowPlacement != null) {
                    hiddenFiles.add(entry);
                    break;
                }
            }
        }

        mRenderedPlacements.clear();
        mRenderedPlacements.putAll(arranged);
        if (overflowPlacement != null) {
            mRenderedPlacements.put("folder:overflow", overflowPlacement);
        }

        mGrid.removeAllViews();
        for (final Entry entry : entries) {
            final DesktopPlacement placement = arranged.get(entry.itemId);
            if (placement != null) {
                addEntryView(entry, placement);
            }
        }
        if (overflowPlacement != null) {
            final View overflow = mViews.overflow(hiddenFiles.size());
            overflow.setOnClickListener(view -> openFolder());
            mGrid.addItem(overflow, "folder:overflow", overflowPlacement);
        }
        saveArrangedPlacements(profile, entries, arranged);
    }

    boolean isDesktopShortcut(final AppItem app) {
        return app != null
                && mContent.containsShortcut(app.launchTarget);
    }

    void toggleDesktopShortcut(final AppItem app) {
        final boolean added;
        if (mContent.containsShortcut(app.launchTarget)) {
            if (!mContent.removeShortcut(app.launchTarget)) {
                return;
            }
            added = false;
            final String itemId = appItemId(app.launchTarget);
            mActivity.getDisplayProfile().placements.remove(itemId);
            mActivity.saveDisplayProfile();
            DisplayProfileStore.removePlacementEverywhere(itemId);
        } else {
            if (!mContent.addShortcut(app.launchTarget)) {
                return;
            }
            added = true;
        }
        render(mActivity.getLauncherApps());
        mActivity.setStatus(mActivity.getString(
                added
                        ? R.string.status_desktop_shortcut_added
                        : R.string.status_desktop_shortcut_removed,
                app.label));
    }

    void openFolder() {
        openDirectory(null);
    }

    void refreshFolder(final boolean force) {
        final int capacity = mGrid == null
                ? 0 : mGrid.getColumnCount() * mGrid.getRowCount();
        mFolder.refresh(force, capacity);
    }

    void addWidget() {
        mWidgets.addWidget();
    }

    void configureWidget(final int appWidgetId) {
        mWidgets.configure(appWidgetId);
    }

    void removeWidget(final int appWidgetId) {
        final String itemId = widgetItemId(appWidgetId);
        mActivity.getDisplayProfile().placements.remove(itemId);
        mActivity.saveDisplayProfile();
        DisplayProfileStore.removePlacementEverywhere(itemId);
        mWidgets.remove(appWidgetId);
    }

    void resizeWidget(
            final int appWidgetId,
            final int columnDelta,
            final int rowDelta) {
        if (mGrid == null) {
            return;
        }
        final String itemId = widgetItemId(appWidgetId);
        final DisplayProfileStore.Profile profile =
                mActivity.getDisplayProfile();
        DesktopPlacement placement = profile.placements.get(itemId);
        if (placement == null) {
            placement = new DesktopPlacement(0, 0, 1, 1);
        }
        final int columns = mGrid.getColumnCount();
        final int rows = mGrid.getRowCount();
        int minimumColumns = 1;
        int minimumRows = 1;
        int maximumColumns = columns;
        int maximumRows = rows;
        for (final DesktopWidgetController.WidgetEntry widget
                : mWidgets.widgets()) {
            if (widget.appWidgetId != appWidgetId) {
                continue;
            }
            minimumColumns = Math.min(columns, initialSpan(
                    widget.info.minResizeWidth,
                    mGrid.getCellWidth()));
            minimumRows = Math.min(rows, initialSpan(
                    widget.info.minResizeHeight,
                    mGrid.getCellHeight()));
            if (widget.info.maxResizeWidth > 0) {
                maximumColumns = Math.min(
                        columns,
                        initialSpan(
                                widget.info.maxResizeWidth,
                                mGrid.getCellWidth()));
            }
            if (widget.info.maxResizeHeight > 0) {
                maximumRows = Math.min(
                        rows,
                        initialSpan(
                                widget.info.maxResizeHeight,
                                mGrid.getCellHeight()));
            }
            break;
        }
        profile.placements.put(itemId, placement.withSpan(
                Math.max(minimumColumns, Math.min(
                        maximumColumns,
                        placement.columnSpan + columnDelta)),
                Math.max(minimumRows, Math.min(
                        maximumRows,
                        placement.rowSpan + rowDelta))));
        mActivity.saveDisplayProfile();
        render(mApps);
    }

    void beginWidgetMove(final int appWidgetId) {
        mEditingWidgetId = appWidgetId;
        render(mApps);
    }

    void cancelEditMode() {
        if (mEditingWidgetId < 0) {
            return;
        }
        mEditingWidgetId = -1;
        render(mApps);
    }

    void openFile(final DesktopFile file) {
        if (file.directory) {
            openDirectory(file.relativePath);
            return;
        }
        mActivity.hideAllPanels();
        final Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                        file.uri,
                        file.mimeType == null ? "*/*" : file.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(mActivity.getCurrentDisplayId());
        try {
            mActivity.startActivity(intent, options.toBundle());
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot open desktop file " + file.uri, error);
            mActivity.setErrorStatus(
                    "FILES-003",
                    mActivity.getString(
                            R.string.status_desktop_file_failed,
                            file.name),
                    "mime=" + file.mimeType,
                    error);
        }
    }

    private void openDirectory(final String relativePath) {
        mActivity.hideAllPanels();
        final StringBuilder documentId = new StringBuilder("primary:Desktop");
        if (relativePath != null && relativePath.length() > 0) {
            documentId.append('/').append(relativePath);
        }
        final Uri uri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                documentId.toString());
        final Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                        uri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(mActivity.getCurrentDisplayId());
        try {
            mActivity.startActivity(intent, options.toBundle());
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot open desktop directory " + uri, error);
            mActivity.setErrorStatus(
                    "FILES-003",
                    mActivity.getString(
                            R.string.status_desktop_folder_open_failed),
                    "uri=" + uri,
                    error);
        }
    }

    void createFile(final String name, final boolean directory) {
        mFolder.create(name, directory, created -> mActivity.setStatus(
                mActivity.getString(
                        R.string.status_desktop_entry_created,
                        created.name)));
    }

    void renameFile(final DesktopFile file, final String newName) {
        final String previousItemId = fileItemId(file.relativePath);
        mFolder.rename(file, newName, renamed -> {
            final String newItemId = fileItemId(renamed.relativePath);
            final DisplayProfileStore.Profile profile =
                    mActivity.getDisplayProfile();
            final DesktopPlacement placement =
                    profile.placements.remove(previousItemId);
            if (placement != null) {
                profile.placements.put(newItemId, placement);
                mActivity.saveDisplayProfile();
            }
            DisplayProfileStore.renamePlacementEverywhere(
                    previousItemId, newItemId);
            mActivity.setStatus(mActivity.getString(
                    R.string.status_desktop_entry_renamed,
                    renamed.name));
        });
    }

    void deleteFile(final DesktopFile file) {
        final String itemId = fileItemId(file.relativePath);
        mFolder.delete(file, () -> {
            mActivity.getDisplayProfile().placements.remove(itemId);
            mActivity.saveDisplayProfile();
            DisplayProfileStore.removePlacementEverywhere(itemId);
            mActivity.setStatus(mActivity.getString(
                    R.string.status_desktop_entry_deleted,
                    file.name));
        });
    }

    void deleteShortcut(final AppItem app) {
        if (app == null
                || !mContent.removeShortcut(app.launchTarget)) {
            return;
        }
        final String itemId = appItemId(app.launchTarget);
        mActivity.getDisplayProfile().placements.remove(itemId);
        mActivity.saveDisplayProfile();
        DisplayProfileStore.removePlacementEverywhere(itemId);
        render(mActivity.getLauncherApps());
        mActivity.setStatus(mActivity.getString(
                R.string.status_desktop_shortcut_removed,
                app.label));
    }

    void resetDisplayProfile() {
        render(mApps);
    }

    private List<Entry> collectEntries() {
        final List<Entry> entries = new ArrayList<>();
        for (final AppLaunchTarget target : mContent.shortcuts()) {
            final AppItem app = mActivity.findOrLoadApp(mApps, target);
            if (app != null) {
                entries.add(Entry.app(appItemId(target), app));
            }
        }
        for (final DesktopWidgetController.WidgetEntry widget
                : mWidgets.widgets()) {
            final DesktopPlacement stored = mActivity.getDisplayProfile()
                    .placements.get(widget.itemId());
            final int columnSpan = stored == null
                    ? initialSpan(widget.info.minWidth, mGrid.getCellWidth())
                    : stored.columnSpan;
            final int rowSpan = stored == null
                    ? initialSpan(widget.info.minHeight, mGrid.getCellHeight())
                    : stored.rowSpan;
            entries.add(Entry.widget(
                    widget.itemId(), widget, columnSpan, rowSpan));
        }
        for (final DesktopFile file : mFiles) {
            entries.add(Entry.file(
                    fileItemId(file.relativePath), file));
        }
        return entries;
    }

    private void addRequests(
            final List<Entry> entries,
            final DisplayProfileStore.Profile profile,
            final List<DesktopPlacementEngine.Request> requests,
            final boolean placed) {
        for (int priority = 0; priority <= 2; priority++) {
            for (final Entry entry : entries) {
                if (entry.placementPriority() != priority) {
                    continue;
                }
                final DesktopPlacement preferred =
                        profile.placements.get(entry.itemId);
                if ((preferred != null) != placed) {
                    continue;
                }
                requests.add(new DesktopPlacementEngine.Request(
                        entry.itemId,
                        entry.columnSpan,
                        entry.rowSpan,
                        preferred));
            }
        }
    }

    private void addEntryView(
            final Entry entry,
            final DesktopPlacement placement) {
        final View view;
        if (entry.app != null) {
            view = mViews.app(
                    entry.app,
                    mActivity.isWorkspaceApp(entry.app.packageName));
            view.setOnClickListener(target -> {
                mActivity.hideAllPanels();
                mActivity.launchDefault(entry.app);
            });
            enableDrag(view, entry.itemId);
            mActivity.registerDesktopAppContextTarget(view, entry.app);
        } else if (entry.file != null) {
            view = mViews.file(entry.file);
            view.setOnClickListener(target -> openFile(entry.file));
            enableDrag(view, entry.itemId);
            mActivity.registerFileContextTarget(view, entry.file);
        } else {
            final AppWidgetHostView widgetView =
                    mWidgets.createView(entry.widget);
            mWidgets.updateSize(
                    widgetView,
                    placement,
                    mGrid.getCellWidth(),
                    mGrid.getCellHeight());
            final FrameLayout frame = new DesktopWidgetContainer(mActivity);
            frame.addView(widgetView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            if (mEditingWidgetId == entry.widget.appWidgetId) {
                final View moveLayer = new View(mActivity);
                moveLayer.setClickable(true);
                moveLayer.setBackground(mUi.rounded(
                        0x11000000,
                        mUi.dp(4),
                        DesktopUiFactory.COLOR_CYAN));
                enableDrag(moveLayer, entry.itemId);
                frame.addView(moveLayer, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
            }
            mActivity.registerWidgetContextTarget(
                    frame,
                    entry.widget.appWidgetId,
                    entry.widget.info.label,
                    entry.widget.info.configure != null,
                    entry.widget.info.resizeMode);
            view = frame;
        }
        mGrid.addItem(view, entry.itemId, placement);
    }

    // This passive listener detects drag slop and leaves ordinary clicks to the view.
    @SuppressLint("ClickableViewAccessibility")
    private void enableDrag(final View view, final String itemId) {
        final int touchSlop =
                ViewConfiguration.get(mActivity).getScaledTouchSlop();
        final float[] down = new float[2];
        final boolean[] dragging = new boolean[1];
        view.setOnTouchListener((target, event) -> {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                down[0] = event.getX();
                down[1] = event.getY();
                dragging[0] = false;
            } else if (action == MotionEvent.ACTION_MOVE
                    && !dragging[0]
                    && (Math.abs(event.getX() - down[0]) > touchSlop
                            || Math.abs(event.getY() - down[1]) > touchSlop)) {
                final ClipData data = ClipData.newPlainText(
                        mActivity.getString(R.string.desktop_drag_label),
                        itemId);
                dragging[0] = target.startDragAndDrop(
                        data,
                        new View.DragShadowBuilder(target),
                        new DesktopGridLayout.DragToken(itemId),
                        0);
                return dragging[0];
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                dragging[0] = false;
            }
            return false;
        });
    }

    private void moveItem(
            final String itemId,
            final int column,
            final int row) {
        final DisplayProfileStore.Profile profile =
                mActivity.getDisplayProfile();
        DesktopPlacement current = mRenderedPlacements.get(itemId);
        if (current == null) {
            current = profile.placements.get(itemId);
        }
        if (current == null) {
            current = new DesktopPlacement(column, row, 1, 1);
        }
        final List<DesktopPlacement> occupied = new ArrayList<>();
        for (final Map.Entry<String, DesktopPlacement> entry
                : mRenderedPlacements.entrySet()) {
            if (!itemId.equals(entry.getKey())) {
                occupied.add(entry.getValue());
            }
        }
        final DesktopPlacement placement =
                DesktopPlacementEngine.findNearestFree(
                        occupied,
                        mGrid.getColumnCount(),
                        mGrid.getRowCount(),
                        current.columnSpan,
                        current.rowSpan,
                        column,
                        row);
        if (placement == null) {
            return;
        }
        profile.placements.put(itemId, placement);
        mEditingWidgetId = -1;
        mActivity.saveDisplayProfile();
        render(mApps);
    }

    private void saveArrangedPlacements(
            final DisplayProfileStore.Profile profile,
            final List<Entry> entries,
            final Map<String, DesktopPlacement> arranged) {
        boolean changed = false;
        final Set<String> validIds = new HashSet<>();
        for (final Entry entry : entries) {
            validIds.add(entry.itemId);
            final DesktopPlacement placement = arranged.get(entry.itemId);
            if (placement != null
                    && !placement.equals(
                            profile.placements.get(entry.itemId))) {
                profile.placements.put(entry.itemId, placement);
                changed = true;
            }
        }
        final List<String> stale = new ArrayList<>();
        for (final String itemId : profile.placements.keySet()) {
            if ((itemId.startsWith(APP_PREFIX)
                    || itemId.startsWith("widget:"))
                    && !validIds.contains(itemId)) {
                stale.add(itemId);
            }
        }
        if (!stale.isEmpty()) {
            changed = true;
            for (final String itemId : stale) {
                profile.placements.remove(itemId);
            }
        }
        if (changed) {
            mActivity.saveDisplayProfile();
        }
    }

    private void onFilesChanged(
            final List<DesktopFile> files,
            final boolean successfulRead) {
        mFiles = new ArrayList<>(files);
        if (successfulRead) {
            final Set<String> liveFiles = new HashSet<>();
            for (final DesktopFile file : files) {
                liveFiles.add(fileItemId(file.relativePath));
            }
            final DisplayProfileStore.Profile profile =
                    mActivity.getDisplayProfile();
            final List<String> stale = new ArrayList<>();
            for (final String itemId : profile.placements.keySet()) {
                if (itemId.startsWith(FILE_PREFIX)
                        && !liveFiles.contains(itemId)) {
                    stale.add(itemId);
                }
            }
            for (final String itemId : stale) {
                profile.placements.remove(itemId);
            }
            if (!stale.isEmpty()) {
                mActivity.saveDisplayProfile();
            }
        }
        render(mApps);
    }

    private void onWidgetsChanged() {
        render(mApps);
    }

    private static String appItemId(final AppLaunchTarget target) {
        return APP_PREFIX + target.stableKey();
    }

    private static String fileItemId(final String relativePath) {
        return FILE_PREFIX + relativePath;
    }

    private static String widgetItemId(final int appWidgetId) {
        return "widget:" + appWidgetId;
    }

    private static int initialSpan(
            final int minimumPixels,
            final int cellPixels) {
        return Math.max(
                1,
                (Math.max(1, minimumPixels) + Math.max(1, cellPixels) - 1)
                        / Math.max(1, cellPixels));
    }

    private int desktopDp(
            final int normalValue,
            final int compactValue) {
        return mUi.desktopDp(
                normalValue,
                compactValue,
                mActivity.isCompactDesktopPreview());
    }

    private static final class Entry {
        final String itemId;
        final AppItem app;
        final DesktopFile file;
        final DesktopWidgetController.WidgetEntry widget;
        final int columnSpan;
        final int rowSpan;

        private Entry(
                final String itemId,
                final AppItem app,
                final DesktopFile file,
                final DesktopWidgetController.WidgetEntry widget,
                final int columnSpan,
                final int rowSpan) {
            this.itemId = itemId;
            this.app = app;
            this.file = file;
            this.widget = widget;
            this.columnSpan = columnSpan;
            this.rowSpan = rowSpan;
        }

        static Entry app(final String itemId, final AppItem app) {
            return new Entry(itemId, app, null, null, 1, 1);
        }

        static Entry file(final String itemId, final DesktopFile file) {
            return new Entry(itemId, null, file, null, 1, 1);
        }

        static Entry widget(
                final String itemId,
                final DesktopWidgetController.WidgetEntry widget,
                final int columnSpan,
                final int rowSpan) {
            return new Entry(
                    itemId, null, null, widget, columnSpan, rowSpan);
        }

        int placementPriority() {
            if (widget != null) {
                return 0;
            }
            return app != null ? 1 : 2;
        }
    }
}
