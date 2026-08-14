package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.DragAndDropPermissions;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
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
        implements FileManagerView.Listener, FileManagerItemMenu.Listener,
        ShellAccess.StateListener {
    static final String EXTRA_PATH =
            "io.github.mekhontsev.magicdesk.extra.FILE_MANAGER_PATH";
    static final String DEFAULT_PATH = "/storage/emulated/0";

    private static final String PREFERENCES = "file_manager";
    private static final String PREF_LAST_PATH = "last_path";
    private static final int PAGE_SIZE = 500;
    private static final Object INTERNAL_DRAG = new Object();

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

    private FileManagerView mView;
    private FileManagerOperationController mOperations;
    private FileManagerImportController mImporter;
    private OnBackInvokedCallback mBackCallback;
    private String mCurrentPath = DEFAULT_PATH;
    private int mHistoryIndex = -1;
    private boolean mShowHidden;
    private int mSortMode = ShellFileSystem.SORT_NAME;
    private boolean mSortAscending = true;
    private boolean mDetails = true;
    private List<String> mClipboardPaths = new ArrayList<>();
    private boolean mClipboardMove;
    private volatile boolean mDestroyed;

    static Intent createIntent(final Context context, final String path) {
        return new Intent(context, FileManagerActivity.class)
                .putExtra(EXTRA_PATH, path == null ? DEFAULT_PATH : path);
    }

    static AppLaunchTarget launchTarget(final Context context) {
        return AppLaunchTarget.explicit(
                context.getPackageName(),
                FileManagerActivity.class.getName(),
                Intent.ACTION_MAIN);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mView = new FileManagerView(this, this);
        mOperations = new FileManagerOperationController(
                this,
                mWorker,
                new FileManagerOperationController.Listener() {
                    @Override
                    public void onOperationFinished(
                            final boolean successful,
                            final String message,
                            final boolean movedClipboard) {
                        finishOperation(successful, message, movedClipboard);
                    }

                    @Override
                    public void onOperationStartFailed(
                            final Throwable error) {
                        mView.setStatus(getString(
                                R.string.file_manager_operation_failed,
                                ShellAccess.usefulMessage(error)));
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
        setContentView(mView.root());
        mView.setTerminalVisible(TermuxIntegration.isInstalled(this));
        final String requested = getIntent().getStringExtra(EXTRA_PATH);
        final String stored = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getString(PREF_LAST_PATH, DEFAULT_PATH);
        mCurrentPath = requested == null ? stored : requested;
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
        if (requested != null) {
            loadDirectory(requested, true, -1);
        }
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        ShellAccess.removeStateListener(this);
        mLoadGeneration.incrementAndGet();
        if (mOperations != null) {
            mOperations.close();
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
    public void onShellStateChanged(final ShellAccess.Snapshot snapshot) {
        runOnUiThread(() -> {
            if (mDestroyed) {
                return;
            }
            final boolean ready = snapshot != null && snapshot.isReady();
            mView.setShellReady(ready);
            if (ready) {
                loadDirectory(
                        mCurrentPath,
                        mHistory.isEmpty(),
                        mHistoryIndex);
            } else {
                mView.setStatus(getString(
                        R.string.file_manager_access_unavailable,
                        snapshot == null ? "unknown" : snapshot.error));
            }
        });
    }

    @Override
    public void onBack() {
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
        loadDirectory(mCurrentPath, false, mHistoryIndex);
    }

    @Override
    public void onNavigate(final String path) {
        loadDirectory(path, true, -1);
    }

    @Override
    public void onOpen(final ShellFileInfo file) {
        if (file.directory) {
            loadDirectory(file.absolutePath, true, -1);
        } else {
            openFile(file, false);
        }
    }

    @Override
    public void onSelectionChanged(
            final ShellFileInfo file, final boolean selected) {
        if (selected) {
            mSelected.put(file.absolutePath, file);
        } else {
            mSelected.remove(file.absolutePath);
        }
        renderFiles();
    }

    @Override
    public boolean onContextMenu(
            final View anchor, final ShellFileInfo file) {
        FileManagerItemMenu.show(this, anchor, file, this);
        return true;
    }

    @Override
    public void onStartDrag(final View source, final ShellFileInfo file) {
        if (!mSelected.containsKey(file.absolutePath)) {
            mSelected.clear();
            mSelected.put(file.absolutePath, file);
            renderFiles();
        }
        final List<ShellFileInfo> dragged = new ArrayList<>();
        for (final ShellFileInfo selected : mSelected.values()) {
            if (!selected.directory) {
                dragged.add(selected);
            }
        }
        if (dragged.isEmpty()) {
            return;
        }
        try {
            final List<Uri> uris = new ArrayList<>();
            for (final ShellFileInfo selected : dragged) {
                uris.add(ShellFileGrantStore.create(
                        this, selected, false));
            }
            startFileDrag(source, uris);
        } catch (RuntimeException error) {
            mView.setStatus(getString(
                    R.string.file_manager_open_failed,
                    ShellAccess.usefulMessage(error)));
        }
    }

    @Override
    public boolean onDrop(final DragEvent event) {
        if (event.getLocalState() == INTERNAL_DRAG) {
            return false;
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
            importDroppedFiles(uris, null);
            return true;
        }
        importDroppedFiles(uris, permissions);
        return true;
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
        if (mClipboardPaths.isEmpty()) {
            return;
        }
        startOperation(
                mClipboardMove
                        ? ShellFileSystem.OPERATION_MOVE
                        : ShellFileSystem.OPERATION_COPY,
                mClipboardPaths,
                mCurrentPath);
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
        final String command = "cd -- "
                + ShellCommandLine.quote(mCurrentPath) + "\npwd\n";
        try {
            startOnCurrentDisplay(CommandConsoleActivity.createIntent(
                    this, command));
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
            }
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
    public void onViewModeChanged(final boolean details) {
        mDetails = details;
        renderFiles();
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
        if (getCurrentFocus() instanceof EditText) {
            return super.onKeyShortcut(keyCode, event);
        }
        if (event.isCtrlPressed() && keyCode == KeyEvent.KEYCODE_L) {
            mView.focusPath();
            return true;
        }
        if (!event.isCtrlPressed()) {
            return super.onKeyShortcut(keyCode, event);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_A:
                selectAll();
                return true;
            case KeyEvent.KEYCODE_C:
                onCopy();
                return true;
            case KeyEvent.KEYCODE_X:
                onCut();
                return true;
            case KeyEvent.KEYCODE_V:
                onPaste();
                return true;
            case KeyEvent.KEYCODE_H:
                onShowHiddenChanged(!mShowHidden);
                mView.setShowHidden(mShowHidden);
                return true;
            case KeyEvent.KEYCODE_N:
                if (event.isShiftPressed()) {
                    onNewFolder();
                    return true;
                }
                break;
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
        if (event.isAltPressed() && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            onUp();
            return true;
        }
        if (event.getRepeatCount() == 0) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_F2:
                    onRename();
                    return true;
                case KeyEvent.KEYCODE_FORWARD_DEL:
                    onDelete();
                    return true;
                case KeyEvent.KEYCODE_F5:
                    onRefresh();
                    return true;
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    final ShellFileInfo selected = singleSelection();
                    if (selected != null) {
                        onOpen(selected);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_ESCAPE:
                    if (!mSelected.isEmpty()) {
                        clearSelection();
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onItemOpen(final ShellFileInfo file) {
        onOpen(file);
    }

    @Override
    public void onItemCopy(final ShellFileInfo file) {
        selectOnly(file);
        onCopy();
    }

    @Override
    public void onItemCut(final ShellFileInfo file) {
        selectOnly(file);
        onCut();
    }

    @Override
    public void onItemRename(final ShellFileInfo file) {
        selectOnly(file);
        onRename();
    }

    @Override
    public void onItemDelete(final ShellFileInfo file) {
        selectOnly(file);
        onDelete();
    }

    @Override
    public void onItemProperties(final ShellFileInfo file) {
        showProperties(file);
    }

    @Override
    public void onItemOpenWith(final ShellFileInfo file) {
        openFile(file, true);
    }

    @Override
    public void onItemCopyPath(final ShellFileInfo file) {
        copyPath(file);
    }

    @Override
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

    private void loadDirectory(
            final String requestedPath,
            final boolean addHistory,
            final int requestedHistoryIndex) {
        if (!ShellAccess.isReady()) {
            final ShellAccess.Snapshot snapshot = ShellAccess.currentSnapshot();
            mView.setStatus(getString(
                    R.string.file_manager_access_unavailable,
                    snapshot.error));
            return;
        }
        final String path = normalizeInputPath(requestedPath);
        final int generation = mLoadGeneration.incrementAndGet();
        mView.setLoading();
        mWorker.execute(() -> {
            try {
                final List<ShellFileInfo> loaded = new ArrayList<>();
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
                    mSelected.clear();
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
                    renderFiles();
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (!mDestroyed
                            && generation == mLoadGeneration.get()) {
                        mView.setFiles(
                                new ArrayList<>(),
                                new HashSet<>(),
                                mDetails);
                        mView.setStatus(getString(
                                R.string.file_manager_load_failed,
                                ShellAccess.usefulMessage(error)));
                    }
                });
            }
        });
    }

    private void renderFiles() {
        mView.setPath(mCurrentPath);
        mView.setFiles(mFiles, mSelected.keySet(), mDetails);
        mView.setNavigationEnabled(
                mHistoryIndex > 0,
                mHistoryIndex + 1 < mHistory.size(),
                !"/".equals(mCurrentPath));
        mView.updateSelection(
                mSelected.size(), !mClipboardPaths.isEmpty());
        mView.setStatus(getString(
                mSelected.isEmpty()
                        ? R.string.file_manager_items
                        : R.string.file_manager_selected,
                mSelected.isEmpty() ? mFiles.size() : mSelected.size()));
    }

    private void clearSelection() {
        mSelected.clear();
        renderFiles();
    }

    private void selectOnly(final ShellFileInfo file) {
        mSelected.clear();
        mSelected.put(file.absolutePath, file);
        renderFiles();
    }

    private void selectAll() {
        mSelected.clear();
        for (final ShellFileInfo file : mFiles) {
            mSelected.put(file.absolutePath, file);
        }
        renderFiles();
    }

    private ShellFileInfo singleSelection() {
        return mSelected.size() == 1
                ? mSelected.values().iterator().next() : null;
    }

    private void setClipboard(final boolean move) {
        if (mSelected.isEmpty()) {
            return;
        }
        mClipboardPaths = new ArrayList<>(mSelected.keySet());
        mClipboardMove = move;
        clearSelection();
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
        mOperations.startRemote(
                operation,
                paths,
                destination,
                operation == ShellFileSystem.OPERATION_MOVE
                        && mClipboardMove
                        && paths == mClipboardPaths);
    }

    private void finishOperation(
            final boolean successful,
            final String message,
            final boolean movedClipboard) {
        if (successful) {
            if (movedClipboard) {
                mClipboardPaths = new ArrayList<>();
                mClipboardMove = false;
            }
            mSelected.clear();
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
            final Intent intent = chooser
                    ? Intent.createChooser(view,
                            getString(R.string.file_manager_open_with))
                    : view;
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | (file.writable
                            ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            : 0));
            startOnCurrentDisplay(intent);
        } catch (RuntimeException error) {
            mView.setStatus(getString(
                    R.string.file_manager_open_failed,
                    ShellAccess.usefulMessage(error)));
        }
    }

    private void startFileDrag(
            final View source,
            final List<Uri> uris) {
        if (uris.isEmpty()) {
            return;
        }
        final ClipData data = new ClipData(
                getString(R.string.file_manager_drag_label),
                new String[]{ClipDescription.MIMETYPE_TEXT_URILIST},
                new ClipData.Item(uris.get(0)));
        for (int index = 1; index < uris.size(); index++) {
            data.addItem(new ClipData.Item(uris.get(index)));
        }
        source.startDragAndDrop(
                data,
                new View.DragShadowBuilder(source),
                INTERNAL_DRAG,
                View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ);
    }

    private void importDroppedFiles(
            final List<Uri> uris,
            final DragAndDropPermissions permissions) {
        mImporter.importFiles(
                mCurrentPath,
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
        final StringBuilder message = new StringBuilder()
                .append(getString(R.string.file_manager_path,
                        file.absolutePath)).append('\n')
                .append(getString(R.string.file_manager_type,
                        file.symbolicLink ? "symbolic link"
                                : file.directory ? "folder" : file.mimeType))
                .append('\n')
                .append(getString(R.string.file_manager_size,
                        formatSize(file.size))).append('\n')
                .append(getString(R.string.file_manager_modified,
                        DateFormat.getDateTimeInstance().format(
                                new Date(file.modified))))
                .append('\n')
                .append(getString(R.string.file_manager_permissions,
                        permissions(file))).append('\n')
                .append(getString(R.string.file_manager_mode,
                        String.format(Locale.ROOT, "%04o",
                                file.mode & 07777))).append('\n')
                .append(getString(R.string.file_manager_owner,
                        file.ownerUid, file.ownerGid)).append('\n')
                .append(getString(R.string.file_manager_identity,
                        ShellAccess.currentSnapshot().uid));
        if (file.symbolicLink) {
            message.append('\n').append(getString(
                    R.string.file_manager_link_target, file.linkTarget));
        }
        new AlertDialog.Builder(this)
                .setTitle(file.name)
                .setMessage(message.toString())
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

    private static String permissions(final ShellFileInfo file) {
        return (file.readable ? "r" : "-")
                + (file.writable ? "w" : "-")
                + (file.executable ? "x" : "-");
    }

    private static String formatSize(final long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        final String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024d;
            unit++;
        } while (value >= 1024d && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private interface NameConsumer {
        void accept(String name);
    }

    private interface ThrowingRunnable {
        void run() throws IOException;
    }
}
