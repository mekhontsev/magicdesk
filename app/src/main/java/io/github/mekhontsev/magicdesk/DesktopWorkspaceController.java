package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.appwidget.AppWidgetHostView;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.KeyEvent;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DesktopWorkspaceController {
    private static final String TAG = "MagicDeskWorkspace";
    private static final String FILE_PREFIX = "file:";

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final DesktopItemViewFactory mViews;
    private final DesktopFolderController mFolder;
    private final DesktopWidgetController mWidgets;
    private final ItemActivationPolicy mItemActivation;
    private final FileOpenWithController mOpenWith;
    private final AndroidContentActionGateway mContentActions;
    private final ExecutorService mContentWorker =
            Executors.newSingleThreadExecutor(runnable ->
                    new Thread(runnable, "MagicDeskContentAction"));

    private DesktopGridLayout mGrid;
    private List<AppItem> mApps = new ArrayList<>();
    private List<DesktopFile> mFiles = new ArrayList<>();
    private final Map<String, DesktopPlacement> mRenderedPlacements =
            new LinkedHashMap<>();
    private int mLastCapacity;
    private int mEditingWidgetId = -1;
    private String mSelectedFileItemId;

    DesktopWorkspaceController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
        mViews = new DesktopItemViewFactory(activity, ui);
        mItemActivation = new ItemActivationPolicy(
                MagicDeskSettings.load().openFilesWithSingleClick,
                ViewConfiguration.getDoubleTapTimeout());
        mOpenWith = new FileOpenWithController(activity);
        mContentActions = new AndroidContentActionGateway(activity);
        mFolder = new DesktopFolderController(
                activity,
                new DesktopFileRepository(activity),
                this::onFilesChanged,
                activity::onDesktopMetadataChanged);
        mWidgets = new DesktopWidgetController(
                activity, ui, this::onWidgetsChanged);
    }

    DesktopGridLayout createGrid() {
        final DesktopGridLayout grid = new DesktopGridLayout(
                mActivity,
                desktopDp(112, 82),
                desktopDp(102, 78));
        grid.setOnClickListener(view -> clearFileSelection());
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

            @Override
            public boolean onExternalDrop(final DragEvent event) {
                return importDroppedFiles(event);
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
        mOpenWith.close();
        mContentWorker.shutdownNow();
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
        final Map<String, GlobalDesktopPlacement> storedPlacements =
                DesktopLayoutStore.snapshot();
        final List<Entry> entries = collectEntries(storedPlacements);
        final List<DesktopPlacementEngine.Request> requests =
                new ArrayList<>();
        addRequests(entries, storedPlacements, requests, true);
        addRequests(entries, storedPlacements, requests, false);
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
        saveArrangedPlacements(storedPlacements, entries, arranged);
    }

    void refreshSettings(final MagicDeskSettings.Values settings) {
        if (settings != null) {
            mItemActivation.setSingleClick(
                    settings.openFilesWithSingleClick);
        }
    }

    boolean isDesktopShortcut(final AppItem app) {
        return findDefaultApplicationShortcut(app) != null;
    }

    void toggleDesktopShortcut(final AppItem app) {
        if (app == null) {
            return;
        }
        final DesktopFile existing = findDefaultApplicationShortcut(app);
        if (existing != null) {
            deleteApplicationShortcut(existing, app.label);
            return;
        }
        final Intent intent = app.launchTarget.resolve(
                mActivity.getPackageManager());
        if (intent == null) {
            mActivity.setErrorStatus(
                    "APP-LAUNCH-002",
                    mActivity.getString(
                            R.string.status_launch_failed,
                            "no launcher activity"));
            return;
        }
        createApplicationShortcut(
                app,
                app.label,
                intent,
                true);
    }

    void addDesktopShortcut(
            final AppItem app,
            final AppShortcutAction action) {
        if (app == null || action == null) {
            return;
        }
        storeApplicationShortcut(new DesktopApplicationShortcut(
                action.label,
                app.packageName,
                "",
                app.launchTarget,
                "",
                DesktopLaunchMode.AUTO,
                false,
                DesktopExecBackend.SHELL,
                false,
                "",
                DesktopMimeTypes.empty(),
                action.id));
    }

    private DesktopFile findDefaultApplicationShortcut(final AppItem app) {
        if (app == null) {
            return null;
        }
        for (final DesktopFile file : mFiles) {
            final DesktopApplicationShortcut shortcut =
                    file.applicationShortcut();
            if (shortcut != null
                    && app.launchTarget.equals(shortcut.launchTarget)
                    && (shortcut.defaultLaunch
                            || DesktopLaunchIntegrationRegistry
                                    .isDefaultShortcut(
                                            mActivity, app, shortcut))) {
                return file;
            }
        }
        return null;
    }

    private void createApplicationShortcut(
            final AppItem app,
            final String name,
            final Intent intent,
            final boolean defaultLaunch) {
        if (app == null || intent == null) {
            return;
        }
        DesktopApplicationShortcut shortcut = defaultLaunch
                ? DesktopLaunchIntegrationRegistry.defaultShortcut(
                        mActivity, app, name)
                : null;
        if (shortcut == null) {
            final String intentUri = intent.toUri(Intent.URI_INTENT_SCHEME);
            shortcut = new DesktopApplicationShortcut(
                    name,
                    app.packageName,
                    DesktopEntryFile.applicationExec(intentUri),
                    app.launchTarget,
                    intentUri,
                    DesktopLaunchMode.AUTO,
                    defaultLaunch,
                    DesktopExecBackend.SHELL,
                    false);
        }
        storeApplicationShortcut(shortcut);
    }

    private void storeApplicationShortcut(
            final DesktopApplicationShortcut shortcut) {
        mFolder.createApplicationShortcut(shortcut, created ->
                mActivity.setStatus(mActivity.getString(
                        R.string.status_desktop_shortcut_added,
                        shortcut.name)));
    }

    private void deleteApplicationShortcut(
            final DesktopFile file,
            final String label) {
        final String itemId = fileItemId(file.relativePath);
        mFolder.delete(file, () -> {
            DesktopLayoutStore.remove(itemId);
            mActivity.setStatus(mActivity.getString(
                    R.string.status_desktop_shortcut_removed,
                    label));
        });
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

    boolean hasWidgets(final String packageName) {
        return mWidgets.hasWidgets(packageName);
    }

    void addWidgets(final String packageName) {
        mWidgets.addWidgets(packageName);
    }

    void configureWidget(final int appWidgetId) {
        mWidgets.configure(appWidgetId);
    }

    void removeWidget(final int appWidgetId) {
        final String itemId = widgetItemId(appWidgetId);
        DesktopLayoutStore.remove(itemId);
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
        final Map<String, GlobalDesktopPlacement> storedPlacements =
                DesktopLayoutStore.snapshot();
        final GlobalDesktopPlacement stored =
                storedPlacements.get(itemId);
        DesktopPlacement placement = stored == null
                ? mRenderedPlacements.get(itemId)
                : stored.resolve(
                        mGrid.getColumnCount(), mGrid.getRowCount());
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
        final DesktopPlacement resized = placement.withSpan(
                Math.max(minimumColumns, Math.min(
                        maximumColumns,
                        placement.columnSpan + columnDelta)),
                Math.max(minimumRows, Math.min(
                        maximumRows,
                        placement.rowSpan + rowDelta)));
        final GlobalDesktopPlacement global =
                GlobalDesktopPlacement.from(resized, columns, rows);
        DesktopLayoutStore.update(
                placements -> placements.put(itemId, global));
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

    List<DesktopApplicationRepository.Entry> desktopApplications() {
        return DesktopApplicationRepository.fromDesktopFiles(mFiles);
    }

    void openFile(final DesktopFile file) {
        final DesktopFolderShortcut folderShortcut = file.folderShortcut();
        if (folderShortcut != null) {
            openFolderShortcut(folderShortcut);
            return;
        }
        final DesktopApplicationShortcut applicationShortcut =
                file.applicationShortcut();
        if (applicationShortcut != null) {
            openApplicationShortcut(
                    applicationShortcut,
                    desktopAbsolutePath(file),
                    DesktopLaunchArguments.empty());
            return;
        }
        final DesktopWebShortcut webShortcut = file.webShortcut();
        if (webShortcut != null) {
            openWebShortcut(webShortcut);
            return;
        }
        if (file.directory) {
            openDirectory(file.relativePath);
            return;
        }
        openFile(file, false);
    }

    void openFileWith(final DesktopFile file) {
        if (file == null || file.directory) {
            return;
        }
        openFile(file, true);
    }

    private void openFile(
            final DesktopFile file, final boolean alwaysAsk) {
        mActivity.hideAllPanels();
        final AndroidContentPayload content = AndroidContentPayload.uris(
                file.name,
                List.of(new AndroidContentPayload.UriItem(
                        file.uri,
                        file.mimeType == null ? "*/*" : file.mimeType)),
                List.of(),
                AndroidContentPayload.Origin.APPLICATION);
        final Intent intent = AndroidContentIntentAdapter.open(content)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
        final DesktopLaunchArguments arguments =
                DesktopLaunchArguments.files(
                        List.of(desktopAbsolutePath(file)));
        if (!mOpenWith.open(
                intent,
                arguments,
                alwaysAsk,
                new FileOpenWithController.Launcher() {
                    @Override
                    public void launchAndroid(final Intent selected) {
                        launchFileIntent(selected, file);
                    }

                    @Override
                    public void launchDesktop(
                            final DesktopApplicationShortcut shortcut,
                            final DesktopLaunchArguments selectedArguments,
                            final String desktopFilePath) {
                        openApplicationShortcut(
                                shortcut,
                                desktopFilePath,
                                selectedArguments);
                    }
                })) {
            mActivity.setErrorStatus(
                    "FILES-003",
                    mActivity.getString(
                            R.string.status_desktop_file_failed,
                            file.name),
                    "mime=" + file.mimeType + " no handler",
                    null);
        }
    }

    private void launchFileIntent(
            final Intent intent, final DesktopFile file) {
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

    void copyFile(final DesktopFile file, final boolean move) {
        FileClipboardInterop.storeDesktopFile(
                mActivity,
                file,
                desktopAbsolutePath(file),
                move
                        ? FileOperationClipboard.Mode.MOVE
                        : FileOperationClipboard.Mode.COPY);
        mActivity.setStatus(mActivity.getString(
                move
                        ? R.string.status_desktop_item_cut
                        : R.string.status_desktop_item_copied));
    }

    void pasteFiles() {
        final FileClipboardInterop.PasteSource source =
                FileClipboardInterop.resolvePaste(mActivity);
        if (source.kind == FileClipboardInterop.PasteKind.INTERNAL_PATHS) {
            mFolder.transferPaths(
                    source.files.paths,
                    !source.files.isMove(),
                    source.files.isMove()
                            ? source.files.generation : -1L);
        } else if (source.kind
                == FileClipboardInterop.PasteKind.ANDROID_CONTENT) {
            mFolder.importContent(source.content, null);
        }
    }

    void openClipboardContent() {
        executeClipboardAction(true);
    }

    void shareClipboardContent() {
        executeClipboardAction(false);
    }

    private void executeClipboardAction(final boolean open) {
        mActivity.hideAllPanels();
        final int displayId = mActivity.getCurrentDisplayId();
        mContentWorker.execute(() -> {
            final DesktopAutomationResult result = open
                    ? mContentActions.openClipboard(displayId)
                    : mContentActions.shareClipboard(displayId);
            mActivity.runOnUiThread(() -> {
                if (mActivity.isActivityUnavailable() || result.success) {
                    return;
                }
                mActivity.setErrorStatus(
                        result.errorCode.isEmpty()
                                ? "CLIPBOARD-002" : result.errorCode,
                        result.message);
            });
        });
    }

    boolean handleKeyboardCommand(final FileKeyboardCommand command) {
        final DesktopFile selected = selectedFile();
        switch (command) {
            case COPY:
                if (selected != null) {
                    copyFile(selected, false);
                    return true;
                }
                break;
            case CUT:
                if (selected != null) {
                    copyFile(selected, true);
                    return true;
                }
                break;
            case PASTE:
                if (FileClipboardInterop.canPaste(mActivity)) {
                    pasteFiles();
                    return true;
                }
                break;
            case OPEN:
                if (selected != null) {
                    openFile(selected);
                    return true;
                }
                break;
            case RENAME:
                if (selected != null) {
                    mActivity.renameDesktopFile(selected);
                    return true;
                }
                break;
            case DELETE:
                if (selected != null) {
                    mActivity.confirmDeleteDesktopFile(selected);
                    return true;
                }
                break;
            case CLEAR_SELECTION:
                if (selected != null) {
                    clearFileSelection();
                    return true;
                }
                break;
            case REFRESH:
                mFolder.refresh(true, Math.max(1, mLastCapacity));
                return true;
            default:
                break;
        }
        return false;
    }

    void copyFilePath(final DesktopFile file) {
        final DesktopFolderShortcut folderShortcut = file.folderShortcut();
        final DesktopWebShortcut webShortcut = file.webShortcut();
        final String target = folderShortcut != null
                ? folderShortcut.targetPath
                : webShortcut != null
                        ? webShortcut.url
                        : desktopAbsolutePath(file);
        final AndroidClipboardGateway.OperationResult copied =
                AndroidClipboardGateway.get(mActivity).writeText(
                        file.displayName(), target, false);
        if (copied.successful) {
            mActivity.setStatus(R.string.file_manager_path_copied);
        }
    }

    void showFileProperties(final DesktopFile file) {
        mFolder.inspect(file, info -> {
            if (file.folderShortcut() == null) {
                mActivity.showDesktopFileProperties(info);
            } else {
                mActivity.showDesktopFolderShortcutProperties(
                        info, file.folderShortcut());
            }
        });
    }

    void installApk(final DesktopFile file) {
        mActivity.setStatus(R.string.file_manager_installing);
        mFolder.installApk(file);
    }

    void runScript(final DesktopFile file) {
        final Intent intent = CommandConsoleActivity.createScriptIntent(
                mActivity,
                desktopAbsolutePath(file));
        openFiles(intent, desktopAbsolutePath(file));
    }

    void setWallpaper(final DesktopFile file) {
        mActivity.setStatus(R.string.file_manager_setting_wallpaper);
        mFolder.setWallpaper(file);
    }

    private void openDirectory(final String relativePath) {
        final String path = relativePath == null || relativePath.length() == 0
                ? ShellDesktopDirectory.ABSOLUTE_PATH
                : ShellDesktopDirectory.ABSOLUTE_PATH + "/" + relativePath;
        openFiles(FileManagerActivity.createIntent(mActivity, path), path);
    }

    private void openFolderShortcut(
            final DesktopFolderShortcut shortcut) {
        if (!shortcut.available) {
            mActivity.setStatus(R.string.desktop_shortcut_unavailable);
        }
        openFiles(
                FileManagerActivity.createIntent(
                        mActivity, shortcut.targetPath),
                shortcut.targetPath);
    }

    private void openApplicationShortcut(
            final DesktopApplicationShortcut shortcut,
            final String desktopFilePath,
            final DesktopLaunchArguments arguments) {
        if (!mActivity.launchDesktopShortcut(
                shortcut, arguments, desktopFilePath)) {
            mActivity.setStatus(R.string.desktop_shortcut_unavailable);
        }
    }

    private void openWebShortcut(final DesktopWebShortcut shortcut) {
        if (!mActivity.launchDesktopWebShortcut(shortcut)) {
            mActivity.setStatus(R.string.desktop_shortcut_unavailable);
        }
    }

    private void openFiles(final Intent intent, final String detail) {
        mActivity.hideAllPanels();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(mActivity.getCurrentDisplayId());
        try {
            mActivity.startActivity(intent, options.toBundle());
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot open Files at " + detail, error);
            mActivity.setErrorStatus(
                    "FILES-003",
                    mActivity.getString(
                            R.string.status_desktop_folder_open_failed),
                    "path=" + detail,
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
        if (previousItemId.equals(mSelectedFileItemId)) {
            mSelectedFileItemId = null;
            mItemActivation.reset();
        }
        mFolder.rename(file, newName, renamed -> {
            final String newItemId = fileItemId(renamed.relativePath);
            DesktopLayoutStore.rename(previousItemId, newItemId);
            mActivity.setStatus(mActivity.getString(
                    R.string.status_desktop_entry_renamed,
                    renamed.name));
        });
    }

    void deleteFile(final DesktopFile file) {
        final String itemId = fileItemId(file.relativePath);
        if (itemId.equals(mSelectedFileItemId)) {
            mSelectedFileItemId = null;
            mItemActivation.reset();
        }
        mFolder.delete(file, () -> {
            DesktopLayoutStore.remove(itemId);
            mActivity.setStatus(mActivity.getString(
                    R.string.status_desktop_entry_deleted,
                    file.name));
        });
    }

    void resetDisplayProfile() {
        render(mApps);
    }

    private List<Entry> collectEntries(
            final Map<String, GlobalDesktopPlacement> storedPlacements) {
        final List<Entry> entries = new ArrayList<>();
        for (final DesktopWidgetController.WidgetEntry widget
                : mWidgets.widgets()) {
            final GlobalDesktopPlacement stored =
                    storedPlacements.get(widget.itemId());
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
            final DesktopApplicationShortcut shortcut =
                    file.applicationShortcut();
            final AppItem app = shortcut == null
                    || shortcut.launchTarget == null
                    ? null
                    : mActivity.findOrLoadApp(
                            mApps, shortcut.launchTarget);
            entries.add(app == null
                    ? Entry.file(fileItemId(file.relativePath), file)
                    : Entry.app(
                            fileItemId(file.relativePath), app, file));
        }
        return entries;
    }

    private void addRequests(
            final List<Entry> entries,
            final Map<String, GlobalDesktopPlacement> storedPlacements,
            final List<DesktopPlacementEngine.Request> requests,
            final boolean placed) {
        for (int priority = 0; priority <= 2; priority++) {
            for (final Entry entry : entries) {
                if (entry.placementPriority() != priority) {
                    continue;
                }
                final GlobalDesktopPlacement stored =
                        storedPlacements.get(entry.itemId);
                if ((stored != null) != placed) {
                    continue;
                }
                final DesktopPlacement preferred = stored == null
                        ? null : stored.resolve(
                                mGrid.getColumnCount(),
                                mGrid.getRowCount());
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
            final DesktopApplicationShortcut shortcut =
                    entry.file.applicationShortcut();
            view = mViews.app(entry.app, shortcut.name);
            view.setOnClickListener(target -> {
                mActivity.hideAllPanels();
                mActivity.launchDesktopShortcut(
                        shortcut,
                        DesktopLaunchArguments.empty(),
                        desktopAbsolutePath(entry.file));
            });
            mActivity.registerDraggableDesktopAppContextTarget(
                    view, entry.app, entry.file);
            enableDrag(view, entry.itemId, entry.file, true);
        } else if (entry.file != null) {
            view = mViews.file(
                    entry.file,
                    entry.itemId.equals(mSelectedFileItemId));
            view.setOnClickListener(target ->
                    activateFile(entry.itemId, entry.file));
            mActivity.registerDraggableFileContextTarget(view, entry.file);
            enableDrag(view, entry.itemId, entry.file, true);
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
                enableDrag(moveLayer, entry.itemId, null, false);
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
        // addItem installs the default reorder listener, so specialized drop
        // targets must be attached after the view joins the grid.
        if (entry.file != null && entry.file.folderShortcut() != null) {
            enableFolderShortcutDrop(
                    view, entry.itemId, entry.file.folderShortcut());
        } else if (entry.file != null
                && entry.file.applicationShortcut() != null) {
            enableApplicationShortcutDrop(
                    view,
                    entry.itemId,
                    entry.file,
                    entry.file.applicationShortcut());
        }
    }

    private void enableFolderShortcutDrop(
            final View view,
            final String itemId,
            final DesktopFolderShortcut shortcut) {
        final float restingAlpha = view.getAlpha();
        view.setOnDragListener((target, event) -> {
            final FileDragPayload payload = FileDragPayload.from(event);
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    if (!shortcut.available
                            || event.getLocalState()
                                    instanceof DesktopGridLayout.DragToken
                            || (payload != null
                                    && itemId.equals(payload.desktopItemId))) {
                        return false;
                    }
                    return payload != null || event.getClipDescription() != null;
                case DragEvent.ACTION_DRAG_ENTERED:
                    target.setAlpha(1f);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    target.setAlpha(restingAlpha);
                    return true;
                case DragEvent.ACTION_DROP:
                    target.setAlpha(restingAlpha);
                    return importDroppedFiles(
                            event,
                            shortcut.targetPath,
                            shortcut.name);
                default:
                    return true;
            }
        });
    }

    private void enableApplicationShortcutDrop(
            final View view,
            final String itemId,
            final DesktopFile file,
            final DesktopApplicationShortcut shortcut) {
        if (shortcut == null
                || !shortcut.hasExecLaunch()
                || !DesktopExecTemplate.acceptsArguments(shortcut.exec)) {
            return;
        }
        final float restingAlpha = view.getAlpha();
        view.setOnDragListener((target, event) -> {
            final FileDragPayload payload = FileDragPayload.from(event);
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    if (event.getLocalState()
                                    instanceof DesktopGridLayout.DragToken
                            || (payload != null
                            && itemId.equals(payload.desktopItemId))
                            || event.getClipDescription() == null) {
                        return false;
                    }
                    return true;
                case DragEvent.ACTION_DRAG_ENTERED:
                    target.setAlpha(1f);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    target.setAlpha(restingAlpha);
                    return true;
                case DragEvent.ACTION_DROP:
                    target.setAlpha(restingAlpha);
                    final DesktopLaunchArguments arguments =
                            DesktopDragLaunchArguments.from(event);
                    if (arguments.isEmpty()) {
                        return false;
                    }
                    openApplicationShortcut(
                            shortcut,
                            desktopAbsolutePath(file),
                            arguments);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void activateFile(
            final String itemId, final DesktopFile file) {
        if (mItemActivation.shouldActivate(
                itemId, SystemClock.uptimeMillis())) {
            mSelectedFileItemId = null;
            openFile(file);
            return;
        }
        if (!itemId.equals(mSelectedFileItemId)) {
            mSelectedFileItemId = itemId;
            render(mApps);
        }
    }

    private void clearFileSelection() {
        mItemActivation.reset();
        if (mSelectedFileItemId != null) {
            mSelectedFileItemId = null;
            render(mApps);
        }
    }

    private DesktopFile selectedFile() {
        if (mSelectedFileItemId == null) {
            return null;
        }
        for (final DesktopFile file : mFiles) {
            if (mSelectedFileItemId.equals(fileItemId(file.relativePath))) {
                return file;
            }
        }
        return null;
    }

    private void enableDrag(
            final View view,
            final String itemId,
            final DesktopFile file,
            final boolean deferContextMenu) {
        new DeferredContextDragGesture(
                view,
                false,
                deferContextMenu,
                new DeferredContextDragGesture.Listener() {
                    @Override
                    public boolean onStartDrag(
                            final View target, final MotionEvent event) {
                mItemActivation.reset();
                final FileDragPayload filePayload = file == null
                        ? null : new FileDragPayload(
                                List.of(desktopAbsolutePath(file)),
                                itemId,
                                (event.getMetaState()
                                        & KeyEvent.META_CTRL_ON) != 0);
                final ClipData data = dragData(
                        itemId, file, filePayload);
                final int flags = file == null
                        ? 0 : View.DRAG_FLAG_GLOBAL
                                | (file.directory
                                        ? 0
                                        : View.DRAG_FLAG_GLOBAL_URI_READ);
                return target.startDragAndDrop(
                        data,
                        new View.DragShadowBuilder(target),
                        filePayload == null
                                ? new DesktopGridLayout.DragToken(itemId)
                                : filePayload,
                        flags);
                    }

                    @Override
                    public void onShowContextMenu(final View target) {
                        mActivity.showRegisteredContextMenu(target);
                    }

                    @Override
                    public boolean onTap(
                            final View target, final MotionEvent event) {
                        return target.performClick();
                    }
                });
    }

    private ClipData dragData(
            final String itemId,
            final DesktopFile file,
            final FileDragPayload payload) {
        if (file == null) {
            return AndroidContentPayload.text(
                    mActivity.getString(R.string.desktop_drag_label),
                    itemId,
                    false,
                    AndroidContentPayload.Origin.DRAG).toClipData();
        }
        if (file.directory) {
            return payload.clipData(
                    mActivity.getString(R.string.desktop_drag_label),
                    List.of());
        }
        return payload.clipData(file.name, List.of(file.uri));
    }

    private boolean importDroppedFiles(final DragEvent event) {
        return importDroppedFiles(
                event, ShellDesktopDirectory.ABSOLUTE_PATH, null);
    }

    private boolean importDroppedFiles(
            final DragEvent event,
            final String destination,
            final String destinationLabel) {
        final FileDragPayload payload = FileDragPayload.from(event);
        if (payload != null) {
            final List<String> paths = payload.pathsForDestination(
                    destination);
            if (!paths.isEmpty()) {
                mFolder.transferPaths(
                        paths,
                        payload.copy,
                        destination,
                        destinationLabel);
            }
            return true;
        }
        final AndroidContentPayload content =
                AndroidContentPayload.fromClipData(
                        event.getClipData(),
                        AndroidContentPayload.Origin.DRAG);
        if (content.isEmpty()) {
            return false;
        }
        DragAndDropPermissions permissions = null;
        try {
            permissions = mActivity.requestDragAndDropPermissions(event);
        } catch (RuntimeException error) {
            Log.d(TAG, "Drag URI permission was not granted", error);
        }
        mFolder.importContent(
                content,
                permissions,
                destination,
                destinationLabel);
        return true;
    }

    private static String desktopAbsolutePath(final DesktopFile file) {
        return ShellDesktopDirectory.ABSOLUTE_PATH
                + "/" + file.relativePath;
    }

    private void moveItem(
            final String itemId,
            final int column,
            final int row) {
        final Map<String, GlobalDesktopPlacement> storedPlacements =
                DesktopLayoutStore.snapshot();
        DesktopPlacement current = mRenderedPlacements.get(itemId);
        if (current == null) {
            final GlobalDesktopPlacement stored =
                    storedPlacements.get(itemId);
            current = stored == null ? null : stored.resolve(
                    mGrid.getColumnCount(), mGrid.getRowCount());
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
        final GlobalDesktopPlacement global = GlobalDesktopPlacement.from(
                placement,
                mGrid.getColumnCount(),
                mGrid.getRowCount());
        DesktopLayoutStore.update(
                placements -> placements.put(itemId, global));
        mEditingWidgetId = -1;
        render(mApps);
    }

    private void saveArrangedPlacements(
            final Map<String, GlobalDesktopPlacement> storedPlacements,
            final List<Entry> entries,
            final Map<String, DesktopPlacement> arranged) {
        boolean hasNewPlacement = false;
        for (final Entry entry : entries) {
            if (!storedPlacements.containsKey(entry.itemId)
                    && arranged.containsKey(entry.itemId)) {
                hasNewPlacement = true;
                break;
            }
        }
        if (!hasNewPlacement) {
            return;
        }
        DesktopLayoutStore.update(placements -> {
            for (final Entry entry : entries) {
                if (storedPlacements.containsKey(entry.itemId)) {
                    continue;
                }
                final DesktopPlacement placement =
                        arranged.get(entry.itemId);
                final GlobalDesktopPlacement global =
                        GlobalDesktopPlacement.from(
                                placement,
                                mGrid.getColumnCount(),
                                mGrid.getRowCount());
                if (global != null) {
                    placements.put(entry.itemId, global);
                }
            }
        });
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
            if (mSelectedFileItemId != null
                    && !liveFiles.contains(mSelectedFileItemId)) {
                mSelectedFileItemId = null;
                mItemActivation.reset();
            }
            final Map<String, GlobalDesktopPlacement> storedPlacements =
                    DesktopLayoutStore.snapshot();
            boolean hasStaleFiles = false;
            for (final String itemId : storedPlacements.keySet()) {
                if (itemId.startsWith(FILE_PREFIX)
                        && !liveFiles.contains(itemId)) {
                    hasStaleFiles = true;
                    break;
                }
            }
            if (hasStaleFiles) {
                DesktopLayoutStore.update(placements ->
                        placements.entrySet().removeIf(entry ->
                                entry.getKey().startsWith(FILE_PREFIX)
                                        && !liveFiles.contains(
                                                entry.getKey())));
            }
        }
        render(mApps);
    }

    private void onWidgetsChanged() {
        render(mApps);
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

        static Entry app(
                final String itemId,
                final AppItem app,
                final DesktopFile file) {
            return new Entry(itemId, app, file, null, 1, 1);
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
