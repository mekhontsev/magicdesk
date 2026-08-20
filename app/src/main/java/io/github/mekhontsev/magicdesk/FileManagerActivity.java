package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
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
    static final String DEFAULT_PATH = "/storage/emulated/0";

    private static final String PREFERENCES = "file_manager";
    private static final String PREF_LAST_PATH = "last_path";
    private static final String PREF_LAYOUT_MODE = "layout_mode";
    private static final String STATE_CURRENT_PATH = "current_path";
    private static final String STATE_HISTORY = "history";
    private static final String STATE_HISTORY_INDEX = "history_index";
    private static final int PAGE_SIZE = 500;
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
    private final IShellDirectoryObserverCallback mDirectoryCallback =
            new IShellDirectoryObserverCallback.Stub() {
                @Override
                public void onDirectoryChanged(final String absolutePath) {
                    runOnUiThread(() -> scheduleObservedRefresh(absolutePath));
                }
            };

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
        mOpenWith = new FileOpenWithController(this);
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
                (copied, failure) -> {
                    if (mDestroyed) {
                        return;
                    }
                    if (failure == null) {
                        mView.setStatus(getString(
                                R.string.file_manager_import_complete,
                                copied));
                    } else {
                        mView.setStatus(getString(
                                R.string.file_manager_import_failed,
                                ShellAccess.usefulMessage(failure)));
                    }
                    onRefresh();
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
        mWorker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(final Bundle state) {
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
                if (mSearchMode) {
                    startSearch(mSearchQuery);
                } else {
                    loadDirectory(
                            mCurrentPath,
                            mHistory.isEmpty(),
                            mHistoryIndex);
                }
            } else {
                closeDirectoryObserver();
                if (mSearch != null) {
                    mSearch.cancel();
                }
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
                    (DesktopApplicationShortcut) desktopEntry);
        } else if (desktopEntry instanceof DesktopWebShortcut) {
            openWebShortcut((DesktopWebShortcut) desktopEntry);
        } else {
            openFile(file, false);
        }
    }

    private void openApplicationShortcut(
            final DesktopApplicationShortcut shortcut) {
        final int displayId = getDisplay() == null
                ? 0 : getDisplay().getDisplayId();
        if (DesktopRuntimeBridge.getActiveDesktopDisplayId() == displayId) {
            if (!DesktopRuntimeBridge.launchDesktopShortcut(
                    shortcut, displayId)) {
                mView.setStatus(getString(
                        R.string.desktop_shortcut_unavailable));
            }
            return;
        }
        final Intent intent = shortcut.resolveIntent(getPackageManager());
        if (intent == null) {
            mView.setStatus(getString(R.string.desktop_shortcut_unavailable));
            return;
        }
        try {
            final ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            startActivity(intent, options.toBundle());
        } catch (RuntimeException error) {
            mView.setStatus(getString(
                    R.string.file_manager_open_failed,
                    ShellAccess.usefulMessage(error)));
        }
    }

    private void openWebShortcut(final DesktopWebShortcut shortcut) {
        final int displayId = getDisplay() == null
                ? 0 : getDisplay().getDisplayId();
        if (DesktopRuntimeBridge.getActiveDesktopDisplayId() == displayId) {
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
                !FileManagerClipboard.snapshot().isEmpty(),
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
            public void install() {
                onItemInstall(file);
            }

            @Override
            public void runScript() {
                onItemRunScript(file);
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
    public void onStartDrag(
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
        final List<ShellFileInfo> dragged = new ArrayList<>();
        for (final ShellFileInfo selected : mSelected.values()) {
            dragged.add(selected);
        }
        if (dragged.isEmpty()) {
            return;
        }
        try {
            final List<Uri> uris = new ArrayList<>();
            for (final ShellFileInfo selected : dragged) {
                if (!selected.directory) {
                    uris.add(ShellFileGrantStore.create(
                            this, selected, false));
                }
            }
            final List<String> paths = new ArrayList<>(dragged.size());
            for (final ShellFileInfo selected : dragged) {
                paths.add(selected.absolutePath);
            }
            startFileDrag(
                    source,
                    new FileDragPayload(
                            paths,
                            null,
                            (metaState & KeyEvent.META_CTRL_ON) != 0),
                    uris);
        } catch (RuntimeException error) {
            mView.setStatus(getString(
                    R.string.file_manager_open_failed,
                    ShellAccess.usefulMessage(error)));
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
        final ClipData data = event.getClipData();
        if (data == null) {
            return false;
        }
        final List<Uri> uris = new ArrayList<>();
        for (int index = 0; index < data.getItemCount(); index++) {
            final Uri uri = data.getItemAt(index).getUri();
            if (uri != null) {
                uris.add(uri);
            }
        }
        if (uris.isEmpty()) {
            return false;
        }
        final DragAndDropPermissions permissions;
        try {
            permissions = requestDragAndDropPermissions(event);
        } catch (RuntimeException error) {
            importDroppedFiles(destinationPath, uris, null);
            return true;
        }
        importDroppedFiles(destinationPath, uris, permissions);
        return true;
    }

    @Override
    public void onNewWindow() {
        final int displayId = getDisplay() == null
                ? 0 : getDisplay().getDisplayId();
        final String path = mCurrentPath;
        mView.setStatus(getString(R.string.file_manager_opening_new_window));
        mWorker.execute(() -> {
            try {
                WindowedAppLauncher.launch(
                        createNewWindowIntent(this, path),
                        launchTarget(this),
                        displayId,
                        null,
                        true,
                        null,
                        WindowedAppLauncher.TaskReusePolicy.CREATE_NEW,
                        null);
                runOnUiThread(() -> {
                    if (!mDestroyed) {
                        renderFiles();
                    }
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (!mDestroyed) {
                        mView.setStatus(getString(
                                R.string.file_manager_new_window_failed,
                                ShellAccess.usefulMessage(error)));
                    }
                });
            }
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
        final FileManagerClipboard.Snapshot clipboard =
                FileManagerClipboard.snapshot();
        if (clipboard.isEmpty()) {
            return;
        }
        startOperation(
                clipboard.move
                        ? ShellFileSystem.OPERATION_MOVE
                        : ShellFileSystem.OPERATION_COPY,
                clipboard.paths,
                mCurrentPath,
                clipboard.move ? clipboard.generation : -1L);
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
            if (!TermuxIntegration.openDirectory(this, mCurrentPath)) {
                Toast.makeText(
                        this,
                        R.string.file_manager_termux_permission,
                        Toast.LENGTH_LONG).show();
                return;
            }
        } catch (RuntimeException error) {
            Toast.makeText(
                    this,
                    R.string.file_manager_termux_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }
        final int displayId = getDisplay() == null
                ? 0 : getDisplay().getDisplayId();
        mWorker.execute(() -> {
            try {
                TermuxIntegration.showOnDisplay(this, displayId);
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (!mDestroyed) {
                        Toast.makeText(
                                this,
                                getString(
                                        R.string.file_manager_termux_window_failed,
                                        ShellAccess.usefulMessage(error)),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
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

    private void loadDirectory(
            final String requestedPath,
            final boolean addHistory,
            final int requestedHistoryIndex) {
        mItemActivation.reset();
        if (!ShellAccess.isReady()) {
            final ShellAccess.Snapshot snapshot = ShellAccess.currentSnapshot();
            mView.setStatus(getString(
                    R.string.file_manager_access_unavailable,
                    snapshot.error));
            return;
        }
        // Do not let a late event from the previous directory supersede the
        // navigation request while the new directory is being loaded.
        closeDirectoryObserver();
        final String path = normalizeInputPath(requestedPath);
        if (!path.equals(mCurrentPath) && !mFilterQuery.isEmpty()) {
            mFilterQuery = "";
            mView.clearFilter();
        }
        final int generation = mLoadGeneration.incrementAndGet();
        mView.setLoading();
        mWorker.execute(() -> {
            try {
                final List<ShellFileInfo> loaded = new ArrayList<>();
                final Map<String, DesktopEntry> desktopEntries =
                        new LinkedHashMap<>();
                int offset = 0;
                ShellFilePage page;
                do {
                    page = ShellAccess.listShellDirectory(
                            path,
                            offset,
                            PAGE_SIZE,
                            mShowHidden,
                            mSortMode,
                            mSortAscending);
                    for (final ShellFileInfo entry : page.entries) {
                        loaded.add(entry);
                        final DesktopEntry desktopEntry =
                                DesktopEntryFile.read(entry);
                        if (desktopEntry != null) {
                            desktopEntries.put(
                                    entry.absolutePath, desktopEntry);
                        }
                    }
                    offset = page.nextOffset;
                } while (!page.complete && generation == mLoadGeneration.get());
                final String canonicalPath = page.directoryPath;
                runOnUiThread(() -> {
                    if (mDestroyed
                            || generation != mLoadGeneration.get()) {
                        return;
                    }
                    mCurrentPath = canonicalPath;
                    mFiles.clear();
                    mFiles.addAll(loaded);
                    mDesktopEntries.clear();
                    mDesktopEntries.putAll(desktopEntries);
                    mSelected.clear();
                    mSelectionAnchorPath = null;
                    if (mPendingRevealPath != null) {
                        for (final ShellFileInfo entry : loaded) {
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
                        mView.setFiles(
                                new ArrayList<>(),
                                new HashSet<>(),
                                new LinkedHashMap<>());
                        mView.setStatus(getString(
                                R.string.file_manager_load_failed,
                                ShellAccess.usefulMessage(error)));
                    }
                });
            }
        });
    }

    private void renderFiles() {
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

    private void renderSelection() {
        mView.updateSelection(mSelected.keySet());
        updateSelectionSummary(visibleFiles());
    }

    private void updateSelectionSummary(
            final List<ShellFileInfo> visible) {
        updateActionState();
        if (!mSelected.isEmpty()) {
            mView.setStatus(getString(
                    R.string.file_manager_selected,
                    mSelected.size(),
                    FileSizeFormatter.format(selectedFileSize())));
        } else if (mSearchMode) {
            mView.setStatus(getString(
                    R.string.file_manager_search_results,
                    visible.size(), mSearchQuery));
        } else if (!mFilterQuery.isEmpty()) {
            mView.setStatus(getString(
                    R.string.file_manager_filtered,
                    visible.size(), mFiles.size()));
        } else {
            mView.setStatus(getString(
                    R.string.file_manager_items, mFiles.size()));
        }
    }

    private void startSearch(final String query) {
        if (mSearch == null || query == null || query.trim().isEmpty()) {
            return;
        }
        closeDirectoryObserver();
        mSearchMode = true;
        mSearchQuery = query.trim();
        mLoadGeneration.incrementAndGet();
        mFiles.clear();
        mSelected.clear();
        mSelectionAnchorPath = null;
        mDesktopEntries.clear();
        mView.setSearchResults(true);
        mView.setLoading();
        mView.setStatus(getString(
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
                    mDirectoryCallback,
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

    private void scheduleObservedRefresh(final String path) {
        if (mDestroyed
                || mSearchMode
                || !mCurrentPath.equals(path)
                || mDirectoryRefreshScheduled) {
            return;
        }
        mDirectoryRefreshScheduled = true;
        mView.root().post(() -> {
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
        FileManagerClipboard.set(
                new ArrayList<>(mSelected.keySet()), move);
        clearSelection();
    }

    private void updateActionState() {
        mView.updateSelection(
                mSelected.size(),
                !FileManagerClipboard.snapshot().isEmpty());
    }

    private void createEntry(final String name, final boolean directory) {
        runAsync(() -> ShellAccess.createShellEntry(
                mCurrentPath, name, directory),
                R.string.file_manager_create_failed,
                this::onRefresh);
    }

    private void renameEntry(
            final ShellFileInfo file, final String name) {
        runAsync(() -> ShellAccess.renameShellEntry(
                file.absolutePath, name),
                R.string.file_manager_rename_failed,
                this::onRefresh);
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
            onRefresh();
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
            final Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, file.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | (file.writable
                                    ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    : 0));
            view.setClipData(ClipData.newUri(
                    getContentResolver(), file.name, uri));
            if (!mOpenWith.open(
                    view,
                    chooser,
                    this::launchFileIntent)) {
                mView.setStatus(getString(
                        R.string.file_manager_no_handler));
            }
        } catch (RuntimeException error) {
            mView.setStatus(getString(
                    R.string.file_manager_open_failed,
                    ShellAccess.usefulMessage(error)));
        }
    }

    private void launchFileIntent(final Intent intent) {
        try {
            startOnCurrentDisplay(intent);
        } catch (RuntimeException error) {
            mView.setStatus(getString(
                    R.string.file_manager_open_failed,
                    ShellAccess.usefulMessage(error)));
        }
    }

    private void startFileDrag(
            final View source,
            final FileDragPayload payload,
            final List<Uri> uris) {
        final ClipData data = payload.clipData(
                getString(R.string.file_manager_drag_label),
                uris);
        final int flags = View.DRAG_FLAG_GLOBAL
                | (uris.isEmpty()
                        ? 0 : View.DRAG_FLAG_GLOBAL_URI_READ);
        source.startDragAndDrop(
                data,
                new View.DragShadowBuilder(source),
                payload,
                flags);
    }

    private void importDroppedFiles(
            final String destination,
            final List<Uri> uris,
            final DragAndDropPermissions permissions) {
        mImporter.importFiles(
                destination,
                uris,
                permissions);
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
        final ClipboardManager clipboard = getSystemService(
                ClipboardManager.class);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(
                file.name,
                file.absolutePath));
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

    private void startOnCurrentDisplay(final Intent intent) {
        final ActivityOptions options = ActivityOptions.makeBasic();
        if (getDisplay() != null) {
            options.setLaunchDisplayId(getDisplay().getDisplayId());
        }
        startActivity(intent, options.toBundle());
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
            final ThrowingRunnable action, final int errorResource) {
        runAsync(action, errorResource, null);
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
