package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.DragAndDropPermissions;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.PopupWindow;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class FileManagerActivity extends Activity
        implements FileManagerView.Listener,
        ShellAccess.StateListener {
    static final String EXTRA_PATH =
            "io.github.mekhontsev.magicdesk.extra.FILE_MANAGER_PATH";
    private static final String EXTRA_REVEAL_PATH =
            "io.github.mekhontsev.magicdesk.extra.FILE_MANAGER_REVEAL_PATH";
    private static final String EXTRA_SAVE_CONTENT =
            "io.github.mekhontsev.magicdesk.extra.FILE_MANAGER_SAVE_CONTENT";
    private static final String STATE_SAVE_PENDING = "save_pending";
    static final String DEFAULT_PATH = "/storage/emulated/0";

    private static final String PREFERENCES = "file_manager";
    private static final String PREF_LAST_PATH = "last_path";
    private static final String PREF_LAYOUT_MODE = "layout_mode";
    private static final String STATE_CURRENT_PATH = "current_path";
    private static final String STATE_HISTORY = "history";
    private static final String STATE_HISTORY_INDEX = "history_index";
    private static final int MAX_SEARCH_RESULTS = 1000;
    private final ExecutorService mWorker =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskFileManager");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicInteger mLoadGeneration = new AtomicInteger();
    private final List<String> mHistory = new ArrayList<>();
    private final Map<String, ShellFileInfo> mSelected =
            new LinkedHashMap<>();
    private final List<ShellFileInfo> mFiles = new ArrayList<>();
    private final Map<String, DesktopEntry> mDesktopEntries =
            new LinkedHashMap<>();
    private final Set<Integer> mHeldModifierKeys = new HashSet<>();

    private FileManagerView mView;
    private FileManagerOperationController mOperations;
    private FileManagerImportController mImporter;
    private FileManagerSearchController mSearch;
    private ShellDirectoryObserverHandle mDirectoryObserver;
    private int mDirectoryObserverGeneration;
    private FileOpenWithController mOpenWith;
    private PopupWindow mItemMenu;
    private OnBackInvokedCallback mBackCallback;
    private String mCurrentPath = DEFAULT_PATH;
    private String mSelectionAnchorPath;
    private ItemActivationPolicy mItemActivation;
    private int mHistoryIndex = -1;
    private boolean mShowHidden;
    private int mSortMode = ShellFileSystem.SORT_NAME;
    private boolean mSortAscending = true;
    private FileManagerLayoutMode mLayoutMode =
            FileManagerLayoutMode.LIST;
    private String mFilterQuery = "";
    private String mPendingRevealPath;
    private boolean mSearchMode;
    private String mSearchQuery = "";
    private boolean mDirectoryRefreshScheduled;
    private volatile boolean mDestroyed;
    private AndroidContentPayload mPendingSave;
    private boolean mSavingContent;
    private boolean mDirectoryLoaded;

    static Intent createSaveIntent(final Context context, final AndroidContentPayload content) {
        if (content.uriItems.size() != 1) throw new IllegalArgumentException("save requires one content URI");
        return AndroidContentIntentAdapter.share(content).setComponent(
                new android.content.ComponentName(context, FileManagerActivity.class))
                .putExtra(EXTRA_SAVE_CONTENT, true);
    }

    static Intent createIntent(final Context context, final String path) {
        return createIntent(context)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_PATH,
                        path == null ? DEFAULT_PATH : path);
    }

    static Intent createRevealIntent(
            final Context context, final ShellFileInfo file) {
        final String directory = file.directory
                ? file.absolutePath
                : ShellFilePathPolicy.shellParent(file.absolutePath);
        return createIntent(context, directory).putExtra(
                EXTRA_REVEAL_PATH, file.absolutePath);
    }

    static Intent createIntent(final Context context) {
        return new Intent(context, FileManagerActivity.class);
    }

    private static Intent createNewWindowIntent(
            final Context context, final String path) {
        return createIntent(context).putExtra(
                EXTRA_PATH, path == null ? DEFAULT_PATH : path);
    }

    static AppLaunchTarget launchTarget(final Context context) {
        return BuiltInDesktopAppCatalog.filesTarget();
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DesktopTaskDescription.apply(
                this,
                R.string.file_manager_title,
                R.drawable.ic_desktop_folder);
        BuiltInWindowRegistry.register(this);
        final SharedPreferences preferences = getSharedPreferences(
                PREFERENCES, MODE_PRIVATE);
        mLayoutMode = FileManagerLayoutMode.fromPreference(
                preferences.getString(PREF_LAYOUT_MODE, null));
        mView = new FileManagerView(this, this, mLayoutMode);
        mItemActivation = new ItemActivationPolicy(
                MagicDeskSettings.load().openFilesWithSingleClick,
                ViewConfiguration.getDoubleTapTimeout());
        mOpenWith = new FileOpenWithController(this, mWorker);
        mOperations = new FileManagerOperationController(
                this,
                new FileManagerOperationController.Listener() {
                    @Override
                    public void onOperationFinished(
                            final boolean successful,
                            final String message) {
                        finishOperation(successful, message);
                    }
                });
        mImporter = new FileManagerImportController(
                this,
                mWorker,
                mOperations,
                (result, failure) -> {
                    if (mDestroyed) {
                        return;
                    }
                    final int copied = result == null ? 0 : result.copied;
                    if (mSavingContent) {
                        mSavingContent = false;
                        if (copied > 0) clearPendingSave();
                    }
                    if (result != null && result.cancelled) {
                        mView.setStatus(getResources().getQuantityString(
                                R.plurals.file_import_cancelled, result.total, copied, result.total)
                                + (failure == null ? "" : "\n" + ShellAccess.usefulMessage(failure)));
                    } else if (failure == null) {
                        mView.setStatus(getString(
                                R.string.file_manager_import_complete,
                                copied));
                    } else if (copied > 0) {
                        mView.setStatus(getResources().getQuantityString(
                                R.plurals.file_manager_import_partial,
                                result.total, copied, result.total, ShellAccess.usefulMessage(failure)));
                    } else {
                        mView.setStatus(getString(
                                R.string.file_manager_import_failed,
                                ShellAccess.usefulMessage(failure)));
                    }
                    refreshContents();
                });
        mSearch = new FileManagerSearchController(
                this,
                mWorker,
                new FileManagerSearchController.Listener() {
                    @Override
                    public void onSearchBatch(
                            final List<ShellFileInfo> matches) {
                        if (!mSearchMode || mDestroyed) {
                            return;
                        }
                        mFiles.addAll(matches);
                        renderFiles();
                    }

                    @Override
                    public void onSearchFinished(
                            final boolean successful,
                            final boolean truncated,
                            final String message) {
                        finishSearch(successful, truncated, message);
                    }

                    @Override
                    public void onSearchStartFailed(
                            final Throwable error) {
                        finishSearch(
                                false,
                                false,
                                ShellAccess.usefulMessage(error));
                    }
                });
        setContentView(mView.root());
        mView.setTerminalVisible(TermuxIntegration.isInstalled(this));
        if (savedInstanceState != null
                && savedInstanceState.containsKey(STATE_CURRENT_PATH)) {
            mCurrentPath = savedInstanceState.getString(
                    STATE_CURRENT_PATH, DEFAULT_PATH);
            final ArrayList<String> restoredHistory =
                    savedInstanceState.getStringArrayList(STATE_HISTORY);
            if (restoredHistory != null) {
                mHistory.addAll(restoredHistory);
            }
            mHistoryIndex = Math.max(-1, Math.min(
                    savedInstanceState.getInt(STATE_HISTORY_INDEX, -1),
                    mHistory.size() - 1));
        } else {
            final String requested = getIntent().getStringExtra(EXTRA_PATH);
            final String stored = preferences.getString(
                    PREF_LAST_PATH, DEFAULT_PATH);
            mCurrentPath = requested == null ? stored : requested;
        }
        mPendingRevealPath = getIntent().getStringExtra(EXTRA_REVEAL_PATH);
        if (savedInstanceState == null || savedInstanceState.getBoolean(STATE_SAVE_PENDING, true)) {
            readPendingSave(getIntent());
        }
        mBackCallback = this::onBack;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                mBackCallback);
        ShellAccess.addStateListener(this);
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readPendingSave(intent);
        final String requested = intent.getStringExtra(EXTRA_PATH);
        mPendingRevealPath = intent.getStringExtra(EXTRA_REVEAL_PATH);
        if (requested != null) {
            stopSearchMode();
            loadDirectory(requested, true, -1);
        }
    }

    @Override
    protected void onDestroy() {
        BuiltInWindowRegistry.unregister(this);
        mDestroyed = true;
        ShellAccess.removeStateListener(this);
        mLoadGeneration.incrementAndGet();
        if (mOperations != null) {
            mOperations.close();
        }
        if (mSearch != null) {
            mSearch.close();
        }
        closeDirectoryObserver();
        if (mOpenWith != null) {
            mOpenWith.close();
        }
        if (mItemMenu != null) {
            mItemMenu.dismiss();
            mItemMenu = null;
        }
        if (mBackCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    mBackCallback);
            mBackCallback = null;
        }
        if (mImporter != null) {
            mImporter.close();
        }
        mWorker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(final Bundle state) {
        state.putBoolean(STATE_SAVE_PENDING, mPendingSave != null);
        state.putString(STATE_CURRENT_PATH, mCurrentPath);
        state.putStringArrayList(STATE_HISTORY, new ArrayList<>(mHistory));
        state.putInt(STATE_HISTORY_INDEX, mHistoryIndex);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mItemActivation.setSingleClick(MagicDeskSettings.load()
                .openFilesWithSingleClick);
    }

    @Override
    protected void onPause() {
        mHeldModifierKeys.clear();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(final boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && mView != null) {
            updateActionState();
        } else if (!hasFocus) {
            mHeldModifierKeys.clear();
        }
    }

    @Override
    public boolean dispatchKeyEvent(final KeyEvent event) {
        updateModifierState(event);
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onShellStateChanged(final ShellAccess.Snapshot snapshot) {
        runOnUiThread(() -> {
            if (mDestroyed) {
                return;
            }
            final boolean ready = snapshot != null && snapshot.isReady();
            mView.setShellReady(ready);
            if (ready) {
                mView.clearStatus();
                if (mSearchMode) {
                    startSearch(mSearchQuery);
                } else {
                    loadDirectory(
                            mCurrentPath,
                            mHistory.isEmpty(),
                            mHistoryIndex);
                }
            } else {
                mLoadGeneration.incrementAndGet();
                closeDirectoryObserver();
                if (mSearch != null) {
                    mSearch.cancel();
                }
                clearFileListing();
                mView.setStatus(getString(
                        R.string.file_manager_access_unavailable,
                        snapshot == null ? "unknown" : snapshot.error));
            }
        });
    }

    @Override
    public void onBack() {
        if (mSearchMode) {
            exitSearch();
            return;
        }
        if (!mSelected.isEmpty()) {
            clearSelection();
            return;
        }
        if (mHistoryIndex > 0) {
            loadDirectory(
                    mHistory.get(mHistoryIndex - 1),
                    false,
                    mHistoryIndex - 1);
        } else {
            finish();
        }
    }

    @Override
    public void onForward() {
        if (mHistoryIndex + 1 < mHistory.size()) {
            loadDirectory(
                    mHistory.get(mHistoryIndex + 1),
                    false,
                    mHistoryIndex + 1);
        }
    }

    @Override
    public void onUp() {
        final int slash = mCurrentPath.lastIndexOf('/');
        final String parent = slash <= 0
                ? "/" : mCurrentPath.substring(0, slash);
        if (!parent.equals(mCurrentPath)) {
            loadDirectory(parent, true, -1);
        }
    }

    @Override
    public void onRefresh() {
        mView.clearStatus();
        refreshContents();
    }

    private void refreshContents() {
        if (mDestroyed) {
            return;
        }
        if (mSearchMode) {
            startSearch(mSearchQuery);
            return;
        }
        loadDirectory(mCurrentPath, false, mHistoryIndex);
    }

    @Override
    public void onNavigate(final String path) {
        stopSearchMode();
        loadDirectory(path, true, -1);
    }

    @Override
    public void onItemClick(
            final ShellFileInfo file,
            final int metaState,
            final long eventTime) {
        final int normalized = KeyEvent.normalizeMetaState(
                metaState | heldModifierMetaState());
        final boolean control = (normalized & KeyEvent.META_CTRL_ON) != 0;
        final boolean shift = (normalized & KeyEvent.META_SHIFT_ON) != 0;
        if (shift) {
            mItemActivation.reset();
            selectRange(file, control);
            return;
        }
        if (control) {
            mItemActivation.reset();
            mSelectionAnchorPath = file.absolutePath;
            if (mSelected.containsKey(file.absolutePath)) {
                mSelected.remove(file.absolutePath);
            } else {
                mSelected.put(file.absolutePath, file);
            }
            renderSelection();
            return;
        }
        final long clickTime = eventTime > 0L
                ? eventTime : android.os.SystemClock.uptimeMillis();
        if (mItemActivation.shouldActivate(
                file.absolutePath, clickTime)) {
            onOpen(file);
        } else {
            selectOnly(file);
        }
    }

    private void updateModifierState(final KeyEvent event) {
        if (!isSelectionModifier(event.getKeyCode())) {
            return;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            mHeldModifierKeys.add(event.getKeyCode());
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            mHeldModifierKeys.remove(event.getKeyCode());
        }
    }

    private int heldModifierMetaState() {
        int state = 0;
        for (final int keyCode : mHeldModifierKeys) {
            if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT
                    || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
                state |= KeyEvent.META_CTRL_ON;
            } else if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT
                    || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
                state |= KeyEvent.META_SHIFT_ON;
            }
        }
        return state;
    }

    private static boolean isSelectionModifier(final int keyCode) {
        return keyCode == KeyEvent.KEYCODE_CTRL_LEFT
                || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT
                || keyCode == KeyEvent.KEYCODE_SHIFT_LEFT
                || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT;
    }

    private void onOpen(final ShellFileInfo file) {
        if (file.directory) {
            stopSearchMode();
            loadDirectory(file.absolutePath, true, -1);
        } else if (file.name.toLowerCase(Locale.ROOT).endsWith(".desktop")) {
            openDesktopEntry(file);
        } else {
            openFile(file, false);
        }
    }

    private void openDesktopEntry(final ShellFileInfo file) {
        final DesktopEntry desktopEntry =
                mDesktopEntries.get(file.absolutePath);
        if (desktopEntry == null && mSearchMode) {
            final int generation = mLoadGeneration.get();
            mWorker.execute(() -> {
                final DesktopEntry parsed = DesktopEntryFile.read(file);
                runOnUiThread(() -> {
                    if (mDestroyed
                            || !mSearchMode
                            || generation != mLoadGeneration.get()) {
                        return;
                    }
                    if (parsed != null) {
                        mDesktopEntries.put(file.absolutePath, parsed);
                        renderFiles();
                    }
                    openDesktopEntry(file, parsed);
                });
            });
            return;
        }
        openDesktopEntry(file, desktopEntry);
    }

    private void openDesktopEntry(
            final ShellFileInfo file,
            final DesktopEntry desktopEntry) {
        if (desktopEntry instanceof DesktopFolderShortcut) {
            final DesktopFolderShortcut shortcut =
                    (DesktopFolderShortcut) desktopEntry;
            if (!shortcut.available) {
                mView.setStatus(getString(
                        R.string.desktop_shortcut_unavailable));
            } else {
                loadDirectory(shortcut.targetPath, true, -1);
            }
        } else if (desktopEntry instanceof DesktopApplicationShortcut) {
            openApplicationShortcut(
                    (DesktopApplicationShortcut) desktopEntry,
                    file.absolutePath,
                    DesktopLaunchArguments.empty());
        } else if (desktopEntry instanceof DesktopWebShortcut) {
            openWebShortcut((DesktopWebShortcut) desktopEntry);
        } else {
            openFile(file, false);
        }
    }

    private void openApplicationShortcut(
            final DesktopApplicationShortcut shortcut,
            final String desktopFilePath,
            final DesktopLaunchArguments arguments) {
        final int displayId = getDisplay() == null
                ? 0 : getDisplay().getDisplayId();
        if (DesktopRuntimeBridge.hasWorkspace(displayId)) {
            if (!DesktopRuntimeBridge.launchDesktopShortcut(
                    shortcut,
                    arguments,
                    desktopFilePath,
                    displayId)) {
                mView.setStatus(getString(
                        R.string.desktop_shortcut_unavailable));
            }
            return;
        }
        // Files can itself move between displays. Capture the destination when
        // opening the entry, not when the Files Activity was constructed.
        final DesktopLaunchCoordinator launcher = new DesktopLaunchCoordinator(
                new StandaloneDesktopLaunchContext(this, displayId, null));
        if (!launcher.launchShortcut(
                shortcut, arguments, desktopFilePath)) {
            mView.setStatus(getString(R.string.desktop_shortcut_unavailable));
        }
    }

    @Override
    public boolean onApplicationDrop(
            final DragEvent event,
            final ShellFileInfo file,
            final DesktopApplicationShortcut shortcut) {
        final DesktopLaunchArguments arguments =
                DesktopDragLaunchArguments.from(event);
        if (arguments.isEmpty()) {
            return false;
        }
        openApplicationShortcut(shortcut, file.absolutePath, arguments);
        return true;
    }

    private void openWebShortcut(final DesktopWebShortcut shortcut) {
        final int displayId = getDisplay() == null
                ? 0 : getDisplay().getDisplayId();
        if (DesktopRuntimeBridge.hasWorkspace(displayId)) {
            if (!DesktopRuntimeBridge.launchDesktopWebShortcut(
                    shortcut, displayId)) {
                mView.setStatus(getString(
                        R.string.desktop_shortcut_unavailable));
            }
            return;
        }
        try {
            final ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            startActivity(shortcut.createViewIntent(), options.toBundle());
        } catch (RuntimeException error) {
            mView.setStatus(getString(
                    R.string.file_manager_open_failed,
                    ShellAccess.usefulMessage(error)));
        }
    }

    @Override
    public void onSelectionChanged(
            final ShellFileInfo file, final boolean selected) {
        mItemActivation.reset();
        if (selected) {
            mSelected.put(file.absolutePath, file);
        } else {
            mSelected.remove(file.absolutePath);
        }
        mSelectionAnchorPath = file.absolutePath;
        renderSelection();
    }

    @Override
    public boolean onContextMenu(
            final View anchor, final ShellFileInfo file) {
        if (mItemMenu != null) {
            mItemMenu.dismiss();
        }
        final PopupWindow itemMenu = FileItemContextMenu.showPopup(
                this,
                anchor,
                FileItemContextMenu.Target.from(file),
                fileMenuActions(file));
        mItemMenu = itemMenu;
        itemMenu.setOnDismissListener(() -> {
            if (mItemMenu == itemMenu) {
                mItemMenu = null;
            }
        });
        return true;
    }

    @Override
    public boolean onBackgroundContextMenu(
            final View anchor, final float rawX, final float rawY) {
        if (mItemMenu != null) {
            mItemMenu.dismiss();
        }
        clearSelection();
        final PopupWindow menu = FileManagerBackgroundContextMenu.show(
                this,
                anchor,
                rawX,
                rawY,
                mCurrentPath,
                FileClipboardInterop.canPaste(this),
                new FileManagerBackgroundContextMenu.Actions() {
                    @Override
                    public void newFile() {
                        onNewFile();
                    }

                    @Override
                    public void newFolder() {
                        onNewFolder();
                    }

                    @Override
                    public void newTerminalApplication() {
                        DesktopCommandApplicationDialog.show(
                                FileManagerActivity.this,
                                DesktopCommandApplicationDialog.InitialValues
                                        .empty(
                                                mCurrentPath,
                                                DesktopExecBackend.SHELL),
                                created -> refreshContents());
                    }

                    @Override
                    public void importFiles() {
                        openImportPicker();
                    }

                    @Override
                    public void paste() {
                        onPaste();
                    }

                    @Override
                    public void refresh() {
                        onRefresh();
                    }

                    @Override
                    public void openConsole() {
                        onOpenConsole();
                    }
                });
        mItemMenu = menu;
        menu.setOnDismissListener(() -> {
            if (mItemMenu == menu) {
                mItemMenu = null;
            }
        });
        return true;
    }

    private FileItemContextMenu.Actions fileMenuActions(
            final ShellFileInfo file) {
        return new FileItemContextMenu.Actions() {
            @Override
            public void open() {
                onItemOpen(file);
            }

            @Override
            public void openWith() {
                onItemOpenWith(file);
            }

            @Override
            public void share() {
                shareFile(file);
            }

            @Override
            public void install() {
                onItemInstall(file);
            }

            @Override
            public void runScript() {
                onItemRunScript(file);
            }

            @Override
            public void createTerminalApplication() {
                onItemCreateTerminalApplication(file);
            }

            @Override
            public void setWallpaper() {
                onItemSetWallpaper(file);
            }

            @Override
            public void createDesktopShortcut() {
                onItemCreateDesktopShortcut(file);
            }

            @Override
            public void copy() {
                onItemCopy(file);
            }

            @Override
            public void cut() {
                onItemCut(file);
            }

            @Override
            public void rename() {
                onItemRename(file);
            }

            @Override
            public void delete() {
                onItemDelete(file);
            }

            @Override
            public void copyPath() {
                onItemCopyPath(file);
            }

            @Override
            public void properties() {
                onItemProperties(file);
            }
        };
    }

    @Override
    public boolean onStartDrag(
            final View source,
            final ShellFileInfo file,
            final int metaState) {
        mItemActivation.reset();
        if (!mSelected.containsKey(file.absolutePath)) {
            mSelected.clear();
            mSelected.put(file.absolutePath, file);
            mSelectionAnchorPath = file.absolutePath;
            renderSelection();
        }
        final List<ShellFileInfo> dragged = new ArrayList<>(mSelected.values());
        if (dragged.isEmpty()) {
            return false;
        }
        List<AndroidContentPayload.UriItem> items = List.of();
        boolean started = false;
        try {
            items = ShellFileGrantStore.createReadOnlySelection(this, dragged);
            final List<String> paths = new ArrayList<>(dragged.size());
            for (final ShellFileInfo selected : dragged) {
                paths.add(selected.absolutePath);
            }
            started = startFileDrag(
                    source,
                    new FileDragPayload(
                            paths,
                            null,
                            (metaState & KeyEvent.META_CTRL_ON) != 0),
                    items);
            return started;
        } catch (RuntimeException error) {
            mView.setStatus(getString(
                    R.string.file_manager_open_failed,
                    ShellAccess.usefulMessage(error)));
            return false;
        } finally {
            if (!started) {
                ShellFileGrantStore.discardUnpublished(this, items);
            }
        }
    }

    @Override
    public boolean onDrop(
            final DragEvent event,
            final String requestedDestinationPath) {
        final String destinationPath = requestedDestinationPath == null
                ? mCurrentPath : requestedDestinationPath;
        final FileDragPayload payload = FileDragPayload.from(event);
        if (payload != null) {
            final List<String> paths =
                    payload.pathsForDestination(destinationPath);
            if (!paths.isEmpty()) {
                startOperation(
                        payload.copy
                                ? ShellFileSystem.OPERATION_COPY
                                : ShellFileSystem.OPERATION_MOVE,
                        paths,
                        destinationPath);
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
        final DragAndDropPermissions permissions;
        try {
            permissions = requestDragAndDropPermissions(event);
        } catch (RuntimeException error) {
            mImporter.importContent(destinationPath, content, null);
            return true;
        }
        mImporter.importContent(destinationPath, content, permissions);
        return true;
    }

    @Override
    public void onNewWindow() {
        final String path = mCurrentPath;
        mView.setStatus(getString(R.string.file_manager_opening_new_window));
        BuiltInWindowLauncher.launch(this, createNewWindowIntent(this, path), launchTarget(this), error -> {
            if (mDestroyed) { return; }
            if (error == null) { renderFiles(); }
            else { mView.setStatus(getString(R.string.file_manager_new_window_failed,
                    ShellAccess.usefulMessage(error))); }
        });
    }

    @Override
    public void onNewFile() {
        showNameDialog(R.string.file_manager_new_file_title, "", name ->
                createEntry(name, false));
    }

    @Override
    public void onNewFolder() {
        showNameDialog(R.string.file_manager_new_folder_title, "", name ->
                createEntry(name, true));
    }

    @Override
    public void onCopy() {
        setClipboard(false);
    }

    @Override
    public void onCut() {
        setClipboard(true);
    }

    @Override
    public void onPaste() {
        final FileClipboardInterop.PasteSource source =
                FileClipboardInterop.resolvePaste(this);
        if (source.kind == FileClipboardInterop.PasteKind.INTERNAL_PATHS) {
            startOperation(
                    source.files.isMove()
                            ? ShellFileSystem.OPERATION_MOVE
                            : ShellFileSystem.OPERATION_COPY,
                    source.files.paths,
                    mCurrentPath,
                    source.files.isMove()
                            ? source.files.generation : -1L);
        } else if (source.kind
                == FileClipboardInterop.PasteKind.ANDROID_CONTENT) {
            mImporter.importContent(
                    mCurrentPath, source.content, null);
        }
    }

    @Override
    public void onRename() {
        final ShellFileInfo file = singleSelection();
        if (file == null) {
            return;
        }
        showNameDialog(R.string.file_manager_rename_title, file.name,
                name -> renameEntry(file, name));
    }

    @Override
    public void onDelete() {
        if (mSelected.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.file_manager_delete_title)
                .setMessage(getString(
                        R.string.file_manager_delete_message,
                        mSelected.size()))
                .setPositiveButton(R.string.action_delete,
                        (dialog, which) -> startOperation(
                                ShellFileSystem.OPERATION_DELETE,
                                new ArrayList<>(mSelected.keySet()),
                                ""))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onProperties() {
        final ShellFileInfo file = singleSelection();
        if (file != null) {
            showProperties(file);
        }
    }

    @Override
    public void onOpenWith() {
        final ShellFileInfo file = singleSelection();
        if (file != null && !file.directory) {
            openFile(file, true);
        }
    }

    @Override
    public void onOpenConsole() {
        try {
            startConsoleWindow(
                    CommandConsoleActivity.createIntentAtDirectory(
                            this, mCurrentPath));
        } catch (RuntimeException error) {
            mView.setStatus(getString(
                    R.string.file_manager_open_failed,
                    ShellAccess.usefulMessage(error)));
        }
    }

    @Override
    public void onOpenTerminal() {
        try {
            if (!TermuxIntegration.ensureRunCommandPermission(this)) {
                Toast.makeText(
                        this,
                        R.string.file_manager_termux_permission,
                        Toast.LENGTH_LONG).show();
                return;
            }
            startConsoleWindow(
                    CommandConsoleActivity.createTermuxIntentAtDirectory(
                            this, mCurrentPath));
        } catch (RuntimeException error) {
            Toast.makeText(
                    this,
                    R.string.file_manager_termux_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onShowHiddenChanged(final boolean showHidden) {
        if (mShowHidden == showHidden) {
            return;
        }
        mShowHidden = showHidden;
        onRefresh();
    }

    @Override
    public void onSortChanged(final int sortMode) {
        if (mSortMode == sortMode) {
            return;
        }
        mSortMode = sortMode;
        onRefresh();
    }

    @Override
    public void onSortDirectionChanged(final boolean ascending) {
        if (mSortAscending == ascending) {
            return;
        }
        mSortAscending = ascending;
        mView.setSortAscending(ascending);
        onRefresh();
    }

    @Override
    public void onViewModeChanged(
            final FileManagerLayoutMode layoutMode) {
        if (mLayoutMode == layoutMode) {
            return;
        }
        mLayoutMode = layoutMode;
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .edit()
                .putString(PREF_LAYOUT_MODE, layoutMode.name())
                .apply();
    }

    @Override
    public void onFilterChanged(final String query) {
        final String normalized = query == null
                ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals(mFilterQuery)) {
            return;
        }
        mFilterQuery = normalized;
        mView.clearStatus();
        mSelected.clear();
        mSelectionAnchorPath = null;
        renderFiles();
    }

    @Override
    public void onRecursiveSearchRequested() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.file_manager_search_hint);
        input.setText(mSearchQuery);
        input.setSelection(input.length());
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.file_manager_search_title)
                .setView(input)
                .setPositiveButton(R.string.file_manager_search, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(
                AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            final String query = input.getText().toString().trim();
            if (query.isEmpty()) {
                input.setError(getString(R.string.file_manager_search_hint));
                return;
            }
            dialog.dismiss();
            mView.clearStatus();
            startSearch(query);
        }));
        dialog.show();
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode,
            final String[] permissions,
            final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == TermuxIntegration.PERMISSION_REQUEST_CODE
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            onOpenTerminal();
        }
    }

    @Override
    public boolean onKeyShortcut(
            final int keyCode, final KeyEvent event) {
        final FileKeyboardCommand command =
                FileKeyboardCommand.fromShortcut(keyCode, event);
        if (command == FileKeyboardCommand.FIND) {
            mView.focusFilter();
            return true;
        }
        if (command == FileKeyboardCommand.NEW_WINDOW) {
            onNewWindow();
            return true;
        }
        if (getCurrentFocus() instanceof EditText) {
            return super.onKeyShortcut(keyCode, event);
        }
        switch (command) {
            case FOCUS_LOCATION:
                mView.focusPath();
                return true;
            case SELECT_ALL:
                selectAll();
                return true;
            case COPY:
                onCopy();
                return true;
            case CUT:
                onCut();
                return true;
            case PASTE:
                onPaste();
                return true;
            case TOGGLE_HIDDEN:
                onShowHiddenChanged(!mShowHidden);
                mView.setShowHidden(mShowHidden);
                return true;
            case NEW_FOLDER:
                onNewFolder();
                return true;
            default:
                break;
        }
        return super.onKeyShortcut(keyCode, event);
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        if (getCurrentFocus() instanceof EditText) {
            return super.onKeyDown(keyCode, event);
        }
        switch (FileKeyboardCommand.fromKeyDown(keyCode, event)) {
            case RENAME:
                onRename();
                return true;
            case DELETE:
                onDelete();
                return true;
            case REFRESH:
                onRefresh();
                return true;
            case OPEN:
                final ShellFileInfo selected = singleSelection();
                if (selected != null) {
                    onOpen(selected);
                    return true;
                }
                break;
            case CLEAR_SELECTION:
                if (!mSelected.isEmpty()) {
                    clearSelection();
                    return true;
                }
                break;
            case UP:
                onUp();
                return true;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void onItemOpen(final ShellFileInfo file) {
        onOpen(file);
    }

    public void onItemCopy(final ShellFileInfo file) {
        selectOnly(file);
        onCopy();
    }

    public void onItemCut(final ShellFileInfo file) {
        selectOnly(file);
        onCut();
    }

    public void onItemRename(final ShellFileInfo file) {
        selectOnly(file);
        onRename();
    }

    public void onItemDelete(final ShellFileInfo file) {
        selectOnly(file);
        onDelete();
    }

    public void onItemProperties(final ShellFileInfo file) {
        showProperties(file);
    }

    public void onItemOpenWith(final ShellFileInfo file) {
        openFile(file, true);
    }

    public void onItemCopyPath(final ShellFileInfo file) {
        copyPath(file);
    }

    public void onItemInstall(final ShellFileInfo file) {
        if (!ShellPackageInstaller.supports(file)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.file_manager_install_title)
                .setMessage(getString(
                        R.string.file_manager_install_message,
                        file.absolutePath))
                .setPositiveButton(R.string.file_manager_install_apk,
                        (dialog, which) -> installApk(file))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public void onItemRunScript(final ShellFileInfo file) {
        if (!ShellScriptLauncher.supports(file)) {
            return;
        }
        try {
            startConsoleWindow(CommandConsoleActivity.createScriptIntent(
                    this, file.absolutePath));
        } catch (RuntimeException error) {
            mView.setStatus(getString(
                    R.string.file_manager_open_failed,
                    ShellAccess.usefulMessage(error)));
        }
    }

    public void onItemSetWallpaper(final ShellFileInfo file) {
        if (!DesktopWallpaperFileAction.supports(file)) {
            return;
        }
        mView.setStatus(getString(R.string.file_manager_setting_wallpaper));
        mWorker.execute(() -> {
            try {
                DesktopWallpaperFileAction.apply(file);
                runOnUiThread(() -> {
                    if (!mDestroyed) {
                        mView.setStatus(getString(
                                R.string.status_desktop_wallpaper_changed));
                    }
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (!mDestroyed) {
                        mView.setStatus(getString(
                                R.string.status_desktop_wallpaper_failed,
                                ShellAccess.usefulMessage(error)));
                    }
                });
            }
        });
    }

    public void onItemCreateDesktopShortcut(final ShellFileInfo file) {
        if (file == null || !file.directory) {
            return;
        }
        runAsync(
                () -> DesktopEntryFile.createFolder(file),
                R.string.file_manager_desktop_shortcut_failed,
                () -> mView.setStatus(getString(
                        R.string.file_manager_desktop_shortcut_created,
                        file.name)));
    }

    private void onItemCreateTerminalApplication(final ShellFileInfo file) {
        if (file == null || file.directory
                || (!file.executable && !ShellScriptLauncher.supports(file))) {
            return;
        }
        DesktopCommandApplicationDialog.show(
                this,
                DesktopCommandApplicationDialog.InitialValues.fromFile(file),
                created -> refreshContents());
    }

    private void loadDirectory(
            final String requestedPath,
            final boolean addHistory,
            final int requestedHistoryIndex) {
        if (mDestroyed) {
            return;
        }
        mItemActivation.reset();
        final int generation = mLoadGeneration.incrementAndGet();
        // Invalidate both pending reads and queued observer events before navigation.
        closeDirectoryObserver();
        if (!ShellAccess.isReady()) {
            final ShellAccess.Snapshot snapshot = ShellAccess.currentSnapshot();
            clearFileListing();
            mView.setStatus(getString(
                    R.string.file_manager_access_unavailable,
                    snapshot.error));
            return;
        }
        final String path = normalizeInputPath(requestedPath);
        if (!path.equals(mCurrentPath)) {
            mView.clearStatus();
            if (!mFilterQuery.isEmpty()) {
                mFilterQuery = "";
                mView.clearFilter();
            }
        }
        final FileDirectoryReader.Request request = new FileDirectoryReader.Request(
                path, mShowHidden, mSortMode, mSortAscending);
        clearFileListing();
        mView.setLoading();
        mWorker.execute(() -> {
            try {
                final FileDirectoryReader.Listing listing = FileDirectoryReader.read(
                        request, () -> mDestroyed || generation != mLoadGeneration.get());
                runOnUiThread(() -> {
                    if (mDestroyed
                            || generation != mLoadGeneration.get()) {
                        return;
                    }
                    final String canonicalPath = listing.path;
                    mCurrentPath = canonicalPath;
                    mDirectoryLoaded = true;
                    mFiles.clear();
                    mFiles.addAll(listing.files);
                    mDesktopEntries.clear();
                    mDesktopEntries.putAll(listing.desktopEntries);
                    mSelected.clear();
                    mSelectionAnchorPath = null;
                    if (mPendingRevealPath != null) {
                        for (final ShellFileInfo entry : listing.files) {
                            if (mPendingRevealPath.equals(entry.absolutePath)) {
                                mSelected.put(entry.absolutePath, entry);
                                mSelectionAnchorPath = entry.absolutePath;
                                break;
                            }
                        }
                        mPendingRevealPath = null;
                    }
                    if (addHistory) {
                        while (mHistory.size() > mHistoryIndex + 1) {
                            mHistory.remove(mHistory.size() - 1);
                        }
                        if (mHistory.isEmpty()
                                || !canonicalPath.equals(
                                        mHistory.get(mHistory.size() - 1))) {
                            mHistory.add(canonicalPath);
                        }
                        mHistoryIndex = mHistory.size() - 1;
                    } else if (requestedHistoryIndex >= 0
                            && requestedHistoryIndex < mHistory.size()) {
                        mHistoryIndex = requestedHistoryIndex;
                    }
                    getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                            .edit()
                            .putString(PREF_LAST_PATH, canonicalPath)
                            .apply();
                    observeDirectory(canonicalPath);
                    renderFiles();
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (!mDestroyed
                            && generation == mLoadGeneration.get()) {
                        clearFileListing();
                        mView.setStatus(getString(
                                R.string.file_manager_load_failed,
                                ShellAccess.usefulMessage(error)));
                    }
                });
            }
        });
    }

    private void clearFileListing() {
        mDirectoryLoaded = false;
        mFiles.clear();
        mDesktopEntries.clear();
        mSelected.clear();
        mSelectionAnchorPath = null;
        renderFiles();
    }

    private void renderFiles() {
        updateSaveAction();
        final List<ShellFileInfo> visible = visibleFiles();
        mView.setPath(mCurrentPath);
        mView.setFiles(
                visible, mSelected.keySet(), mDesktopEntries);
        mView.setNavigationEnabled(
                mSearchMode || mHistoryIndex > 0,
                !mSearchMode && mHistoryIndex + 1 < mHistory.size(),
                !mSearchMode && !"/".equals(mCurrentPath));
        updateSelectionSummary(visible);
    }

    private void readPendingSave(final Intent intent) {
        mPendingSave = null;
        if (intent.getBooleanExtra(EXTRA_SAVE_CONTENT, false)) {
            final AndroidContentPayload content = AndroidContentPayload.fromSendIntent(intent);
            if (content.uriItems.size() == 1 && !content.truncated) mPendingSave = content;
            else Toast.makeText(this, R.string.desktop_share_unsupported, Toast.LENGTH_LONG).show();
        }
        updateSaveAction();
    }

    private void clearPendingSave() {
        mPendingSave = null;
        getIntent().removeExtra(EXTRA_SAVE_CONTENT);
        updateSaveAction();
    }

    private void updateSaveAction() {
        mView.setSaveAction(mPendingSave == null ? null : mPendingSave.label,
                mDirectoryLoaded && !mSearchMode && !mSavingContent && ShellAccess.isReady(),
                () -> {
                    if (mPendingSave == null || !mDirectoryLoaded || mSearchMode || mOperations.isBusy()) return;
                    mSavingContent = true;
                    updateSaveAction();
                    mImporter.importContent(mCurrentPath, mPendingSave, null);
                }, this::clearPendingSave);
    }

    private void renderSelection() {
        mView.clearStatus();
        mView.updateSelection(mSelected.keySet());
        updateSelectionSummary(visibleFiles());
    }

    private void updateSelectionSummary(
            final List<ShellFileInfo> visible) {
        updateActionState();
        if (!mSelected.isEmpty()) {
            mView.setSummary(getString(
                    R.string.file_manager_selected,
                    mSelected.size(),
                    FileSizeFormatter.format(selectedFileSize())));
        } else if (mSearchMode) {
            mView.setSummary(getString(
                    R.string.file_manager_search_results,
                    visible.size(), mSearchQuery));
        } else if (!mFilterQuery.isEmpty()) {
            mView.setSummary(getString(
                    R.string.file_manager_filtered,
                    visible.size(), mFiles.size()));
        } else {
            mView.setSummary(getString(
                    R.string.file_manager_items, mFiles.size()));
        }
    }

    private void startSearch(final String query) {
        if (mDestroyed || mSearch == null || query == null || query.trim().isEmpty()) {
            return;
        }
        closeDirectoryObserver();
        mSearchMode = true;
        mSearchQuery = query.trim();
        mLoadGeneration.incrementAndGet();
        mView.setSearchResults(true);
        clearFileListing();
        mView.setLoading();
        mView.setSummary(getString(
                R.string.file_manager_searching,
                mSearchQuery,
                mCurrentPath));
        if (!mSearch.start(
                mCurrentPath,
                mSearchQuery,
                mShowHidden,
                MAX_SEARCH_RESULTS)) {
            finishSearch(false, false, "search unavailable");
        }
    }

    private void finishSearch(
            final boolean successful,
            final boolean truncated,
            final String message) {
        if (!mSearchMode || mDestroyed) {
            return;
        }
        mFiles.sort(Comparator.comparing(file -> file.absolutePath));
        renderFiles();
        if (!successful) {
            mView.setStatus(getString(
                    R.string.file_manager_search_failed, message));
        } else if (truncated) {
            mView.setStatus(getString(
                    R.string.file_manager_search_truncated,
                    mFiles.size(), MAX_SEARCH_RESULTS));
        }
    }

    private void exitSearch() {
        stopSearchMode();
        loadDirectory(mCurrentPath, false, mHistoryIndex);
    }

    private void stopSearchMode() {
        if (!mSearchMode) {
            return;
        }
        mSearchMode = false;
        mSearchQuery = "";
        mView.clearStatus();
        if (mSearch != null) {
            mSearch.cancel();
        }
        mView.setSearchResults(false);
    }

    private void observeDirectory(final String path) {
        closeDirectoryObserver();
        if (mDestroyed || mSearchMode || !ShellAccess.isReady()) {
            return;
        }
        final int observerGeneration = mDirectoryObserverGeneration;
        try {
            mDirectoryObserver = ShellAccess.openShellDirectoryObserver(
                    path,
                    new IShellDirectoryObserverCallback.Stub() {
                        @Override
                        public void onDirectoryChanged(final String absolutePath) {
                            runOnUiThread(() -> scheduleObservedRefresh(
                                    absolutePath, observerGeneration));
                        }
                    },
                    () -> runOnUiThread(() -> {
                        if (!mDestroyed
                                && observerGeneration
                                == mDirectoryObserverGeneration) {
                            mDirectoryObserver = null;
                        }
                    }));
        } catch (IOException | RuntimeException ignored) {
            // Manual refresh remains available on filesystems without inotify.
        }
    }

    private void scheduleObservedRefresh(final String path, final int observerGeneration) {
        if (mDestroyed
                || observerGeneration != mDirectoryObserverGeneration
                || mSearchMode
                || !mCurrentPath.equals(path)
                || mDirectoryRefreshScheduled) {
            return;
        }
        mDirectoryRefreshScheduled = true;
        mView.root().post(() -> {
            if (observerGeneration != mDirectoryObserverGeneration) {
                return;
            }
            mDirectoryRefreshScheduled = false;
            if (!mDestroyed
                    && !mSearchMode
                    && mCurrentPath.equals(path)) {
                loadDirectory(mCurrentPath, false, mHistoryIndex);
            }
        });
    }

    private void closeDirectoryObserver() {
        mDirectoryObserverGeneration++;
        mDirectoryRefreshScheduled = false;
        if (mDirectoryObserver != null) {
            mDirectoryObserver.close();
            mDirectoryObserver = null;
        }
    }

    private long selectedFileSize() {
        long total = 0L;
        for (ShellFileInfo file : mSelected.values()) {
            if (file.directory || file.size <= 0L) {
                continue;
            }
            if (Long.MAX_VALUE - total < file.size) {
                return Long.MAX_VALUE;
            }
            total += file.size;
        }
        return total;
    }

    private void clearSelection() {
        mSelected.clear();
        mSelectionAnchorPath = null;
        renderSelection();
    }

    private void selectOnly(final ShellFileInfo file) {
        mSelected.clear();
        mSelected.put(file.absolutePath, file);
        mSelectionAnchorPath = file.absolutePath;
        renderSelection();
    }

    private void selectAll() {
        mSelected.clear();
        final List<ShellFileInfo> visible = visibleFiles();
        for (final ShellFileInfo file : visible) {
            mSelected.put(file.absolutePath, file);
        }
        mSelectionAnchorPath = visible.isEmpty()
                ? null : visible.get(0).absolutePath;
        renderSelection();
    }

    private void selectRange(
            final ShellFileInfo target,
            final boolean additive) {
        final List<ShellFileInfo> visible = visibleFiles();
        int targetIndex = -1;
        int anchorIndex = -1;
        for (int index = 0; index < visible.size(); index++) {
            final String path = visible.get(index).absolutePath;
            if (path.equals(target.absolutePath)) {
                targetIndex = index;
            }
            if (path.equals(mSelectionAnchorPath)) {
                anchorIndex = index;
            }
        }
        if (targetIndex < 0) {
            return;
        }
        if (anchorIndex < 0) {
            for (int index = 0; index < visible.size(); index++) {
                if (mSelected.containsKey(visible.get(index).absolutePath)) {
                    anchorIndex = index;
                    break;
                }
            }
        }
        if (anchorIndex < 0) {
            anchorIndex = targetIndex;
            mSelectionAnchorPath = target.absolutePath;
        }
        if (!additive) {
            mSelected.clear();
        }
        final int first = Math.min(anchorIndex, targetIndex);
        final int last = Math.max(anchorIndex, targetIndex);
        for (int index = first; index <= last; index++) {
            final ShellFileInfo file = visible.get(index);
            mSelected.put(file.absolutePath, file);
        }
        renderSelection();
    }

    private List<ShellFileInfo> visibleFiles() {
        if (mFilterQuery.isEmpty()) {
            return mFiles;
        }
        final List<ShellFileInfo> visible = new ArrayList<>();
        for (final ShellFileInfo file : mFiles) {
            if (file.name.toLowerCase(Locale.ROOT).contains(mFilterQuery)) {
                visible.add(file);
            }
        }
        return visible;
    }

    private ShellFileInfo singleSelection() {
        return mSelected.size() == 1
                ? mSelected.values().iterator().next() : null;
    }

    private void setClipboard(final boolean move) {
        if (mSelected.isEmpty()) {
            return;
        }
        FileClipboardInterop.storeShellFiles(
                this,
                new ArrayList<>(mSelected.values()),
                move
                        ? FileOperationClipboard.Mode.MOVE
                        : FileOperationClipboard.Mode.COPY);
        clearSelection();
    }

    private void updateActionState() {
        mView.updateSelection(
                mSelected.size(),
                FileClipboardInterop.canPaste(this));
    }

    private void createEntry(final String name, final boolean directory) {
        final String destination = mCurrentPath;
        runAsync(() -> ShellAccess.createShellEntry(
                destination, name, directory),
                R.string.file_manager_create_failed,
                this::refreshContents);
    }

    private void renameEntry(
            final ShellFileInfo file, final String name) {
        runAsync(() -> ShellAccess.renameShellEntry(
                file.absolutePath, name),
                R.string.file_manager_rename_failed,
                this::refreshContents);
    }

    private void startOperation(
            final int operation,
            final List<String> paths,
            final String destination) {
        startOperation(operation, paths, destination, -1L);
    }

    private void startOperation(
            final int operation,
            final List<String> paths,
            final String destination,
            final long clipboardGeneration) {
        if (!mOperations.startRemote(
                operation,
                paths,
                destination,
                clipboardGeneration)) {
            mView.setStatus(getString(
                    R.string.file_manager_operation_busy));
        }
    }

    private void finishOperation(
            final boolean successful,
            final String message) {
        if (successful) {
            mSelected.clear();
            mSelectionAnchorPath = null;
            refreshContents();
        } else {
            mView.setStatus(getString(
                    R.string.file_manager_operation_failed, message));
        }
    }

    private void openFile(
            final ShellFileInfo file, final boolean chooser) {
        try {
            final Uri uri = ShellFileGrantStore.create(
                    this, file, file.writable);
            final AndroidContentPayload content = AndroidContentPayload.uris(
                    file.name,
                    List.of(new AndroidContentPayload.UriItem(
                            uri, file.mimeType)),
                    List.of(),
                    AndroidContentPayload.Origin.APPLICATION);
            final Intent view = AndroidContentIntentAdapter.open(content)
                    .addFlags(file.writable
                            ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
            mOpenWith.open(
                    view,
                    DesktopLaunchArguments.files(
                            List.of(file.absolutePath)),
                    chooser,
                    new FileOpenWithController.Launcher() {
                        @Override
                        public void launchAndroid(final Intent selected) {
                            launchFileIntent(selected);
                        }

                        @Override
                        public void launchDesktop(
                                final DesktopApplicationShortcut shortcut,
                                final DesktopLaunchArguments arguments,
                                final String desktopFilePath) {
                            openApplicationShortcut(
                                    shortcut,
                                    desktopFilePath,
                                    arguments);
                        }

                        @Override
                        public void noHandler() {
                            mView.setStatus(getString(R.string.file_manager_no_handler));
                        }

                        @Override
                        public void failed(final Throwable error) {
                            mView.setStatus(getString(R.string.file_manager_open_failed,
                                    ShellAccess.usefulMessage(error)));
                        }
                    });
        } catch (RuntimeException error) {
            mView.setStatus(getString(
                    R.string.file_manager_open_failed,
                    ShellAccess.usefulMessage(error)));
        }
    }

    private void launchFileIntent(final Intent intent) {
        final int displayId = currentDisplayId();
        mWorker.execute(() -> executeAndroidActivity(
                "open-file",
                intent,
                DesktopLaunchPresentation.automatic(),
                displayId));
    }

    private void shareFile(final ShellFileInfo file) {
        if (file == null || file.directory) {
            return;
        }
        final int displayId = currentDisplayId();
        mWorker.execute(() -> {
            try {
                final Uri uri = ShellFileGrantStore.create(
                        this, file, false);
                final AndroidContentPayload content = AndroidContentPayload.uris(
                        file.name,
                        List.of(new AndroidContentPayload.UriItem(
                                uri, file.mimeType)),
                        List.of(),
                        AndroidContentPayload.Origin.APPLICATION);
                final DesktopAutomationResult result =
                        new AndroidIntegrationGateway(this).shareContent(
                                content, displayId);
                showAndroidResult(result);
            } catch (IOException | org.json.JSONException
                    | RuntimeException error) {
                showAndroidError(error);
            }
        });
    }

    private void openImportPicker() {
        mImporter.chooseFiles(mCurrentPath, currentDisplayId());
    }

    private void executeAndroidActivity(
            final String id,
            final Intent intent,
            final DesktopLaunchPresentation presentation,
            final int displayId) {
        try {
            final AndroidIntegrationRequest request =
                    AndroidIntegrationRequest.activity(
                            intent,
                            intent.getComponent() == null
                                    ? id : intent.getComponent()
                                            .getPackageName(),
                            presentation,
                            false,
                            "",
                            false);
            showAndroidResult(new AndroidIntegrationGateway(this).execute(
                    AndroidDesktopAction.request(id, "files", request),
                    displayId));
        } catch (IOException | org.json.JSONException
                | RuntimeException error) {
            showAndroidError(error);
        }
    }

    private int currentDisplayId() {
        return getDisplay() == null
                ? android.view.Display.DEFAULT_DISPLAY
                : getDisplay().getDisplayId();
    }

    private void showAndroidResult(final DesktopAutomationResult result) {
        if (result == null || result.success || mDestroyed) {
            return;
        }
        showAndroidErrorMessage(result.message);
    }

    private void showAndroidError(final Throwable error) {
        showAndroidErrorMessage(ShellAccess.usefulMessage(error));
    }

    private void showAndroidErrorMessage(final String message) {
        runOnUiThread(() -> {
            if (!mDestroyed) {
                mView.setStatus(getString(R.string.file_manager_open_failed, message));
            }
        });
    }

    private boolean startFileDrag(
            final View source,
            final FileDragPayload payload,
            final List<AndroidContentPayload.UriItem> items) {
        final ClipData data = payload.clipData(
                getString(R.string.file_manager_drag_label),
                items);
        return source.startDragAndDrop(
                data,
                new View.DragShadowBuilder(source),
                payload,
                FileDragPayload.dragFlags(!items.isEmpty()));
    }

    private void showNameDialog(
            final int title,
            final String initial,
            final NameConsumer consumer) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.file_manager_name_hint);
        input.setText(initial);
        input.setSelection(input.length());
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(
                AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                final String name = ShellFileNamePolicy.validate(
                        input.getText().toString());
                dialog.dismiss();
                consumer.accept(name);
            } catch (IllegalArgumentException error) {
                input.setError(ShellAccess.usefulMessage(error));
            }
        }));
        dialog.show();
    }

    private void showProperties(final ShellFileInfo file) {
        new AlertDialog.Builder(this)
                .setTitle(file.name)
                .setMessage(FilePropertiesFormatter.format(this, file))
                .setNeutralButton(R.string.file_manager_copy_path,
                        (dialog, which) -> copyPath(file))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void copyPath(final ShellFileInfo file) {
        final AndroidClipboardGateway.OperationResult copied =
                AndroidClipboardGateway.get(this).writeText(
                        file.name, file.absolutePath, false);
        if (!copied.successful) {
            return;
        }
        mView.setStatus(getString(R.string.file_manager_path_copied));
    }

    private void installApk(final ShellFileInfo file) {
        mView.setStatus(getString(R.string.file_manager_installing));
        runAsync(
                () -> ShellAccess.run(
                        ShellPackageInstaller.command(file.absolutePath)),
                R.string.file_manager_install_failed,
                () -> mView.setStatus(getString(
                        R.string.file_manager_install_complete,
                        file.name)));
    }

    private void startConsoleWindow(final Intent intent) {
        mView.setStatus(getString(
                R.string.status_launching_window,
                getString(R.string.console_title)));
        BuiltInWindowLauncher.launch(
                this,
                intent,
                CommandConsoleActivity.launchTarget(),
                error -> {
                    if (mDestroyed) {
                        return;
                    }
                    mView.setStatus(error == null
                            ? getString(
                                    R.string.status_switch_done,
                                    getString(R.string.console_title))
                            : getString(
                                    R.string.file_manager_open_failed,
                                    ShellAccess.usefulMessage(error)));
                });
    }

    private void runAsync(
            final ThrowingRunnable action,
            final int errorResource,
            final Runnable success) {
        mWorker.execute(() -> {
            try {
                action.run();
                if (success != null) {
                    runOnUiThread(() -> {
                        if (!mDestroyed) {
                            success.run();
                        }
                    });
                }
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (!mDestroyed) {
                        mView.setStatus(getString(
                                errorResource,
                                ShellAccess.usefulMessage(error)));
                    }
                });
            }
        });
    }

    private static String normalizeInputPath(final String path) {
        if (path == null || path.trim().length() == 0) {
            return DEFAULT_PATH;
        }
        final String trimmed = path.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private interface NameConsumer {
        void accept(String name);
    }

    private interface ThrowingRunnable {
        void run() throws IOException;
    }
}
