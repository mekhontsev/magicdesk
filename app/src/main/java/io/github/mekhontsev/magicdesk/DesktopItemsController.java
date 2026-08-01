package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ClipData;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

final class DesktopItemsController {
    static final int REQUEST_FOLDER = 1001;
    private static final String TAG = "MagicDesk";

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final DesktopFileRepository mFilesRepository;

    private GridLayout mGrid;
    private int mLoadGeneration;
    private String mLoadedFolderUri;
    private List<DesktopFile> mFiles = Collections.emptyList();

    DesktopItemsController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui,
            final DesktopFileRepository filesRepository) {
        mActivity = activity;
        mUi = ui;
        mFilesRepository = filesRepository;
    }

    GridLayout createGrid() {
        final GridLayout grid = new GridLayout(mActivity);
        grid.setColumnCount(getColumnCount());
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setOnDragListener((view, event) -> handleGridDrop(event));
        mGrid = grid;
        return grid;
    }

    void cancel() {
        mLoadGeneration++;
    }

    void resetProfileState() {
        mLoadGeneration++;
        mLoadedFolderUri = null;
        mFiles = Collections.emptyList();
    }

    boolean handleActivityResult(
            final int requestCode,
            final int resultCode,
            final Intent data) {
        if (requestCode != REQUEST_FOLDER
                || resultCode != Activity.RESULT_OK
                || data == null
                || data.getData() == null) {
            return false;
        }
        final Uri treeUri = data.getData();
        if (!hasPersistedReadPermission(treeUri)) {
            final int grantFlags = data.getFlags();
            if ((grantFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0
                    || (grantFlags
                            & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            == 0) {
                rejectTransientFolderGrant(
                        treeUri,
                        "The document provider did not offer a persistent "
                                + "read grant");
                return true;
            }
            try {
                mActivity.getContentResolver().takePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                Log.w(TAG,
                        "Cannot persist desktop folder permission for "
                                + treeUri,
                        e);
                rejectTransientFolderGrant(treeUri, e.getMessage());
                return true;
            }
        }
        if (!hasPersistedReadPermission(treeUri)) {
            rejectTransientFolderGrant(
                    treeUri,
                    "Android did not retain the document provider grant");
            return true;
        }
        final WorkspaceProfileStore.Profile profile =
                mActivity.getWorkspaceProfile();
        profile.folderUri = treeUri.toString();
        mActivity.saveWorkspaceProfile();
        refreshFolder(true);
        mActivity.setStatus(R.string.status_desktop_folder_selected);
        return true;
    }

    private boolean hasPersistedReadPermission(final Uri treeUri) {
        for (final UriPermission permission :
                mActivity.getContentResolver().getPersistedUriPermissions()) {
            if (treeUri.equals(permission.getUri())
                    && permission.isReadPermission()) {
                return true;
            }
        }
        return false;
    }

    private void rejectTransientFolderGrant(
            final Uri treeUri,
            final String reason) {
        final String detail = reason == null || reason.length() == 0
                ? "Persistent folder access was denied"
                : reason;
        mActivity.setErrorStatus(
                "FILES-003",
                mActivity.getString(
                        R.string.status_desktop_folder_failed,
                        detail),
                "Folder: " + treeUri,
                null);
    }

    void render(final List<AppItem> apps) {
        if (mGrid == null) {
            return;
        }
        mGrid.removeAllViews();
        mGrid.setColumnCount(getColumnCount());
        final int capacity = getItemCapacity();
        int rendered = 0;
        for (final String packageName :
                mActivity.getDesktopShortcutPackages()) {
            final AppItem app =
                    LauncherAppRepository.find(apps, packageName);
            if (app == null) {
                continue;
            }
            mGrid.addView(createAppIcon(app), createItemParams());
            rendered++;
            if (rendered >= capacity) {
                return;
            }
        }
        for (final DesktopFile file : mFiles) {
            mGrid.addView(createFileIcon(file), createItemParams());
            rendered++;
            if (rendered >= capacity) {
                return;
            }
        }
    }

    void chooseFolder() {
        mActivity.hideAllPanels();
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        final String currentUri =
                mActivity.getWorkspaceProfile().folderUri;
        if (currentUri != null
                && currentUri.length() > 0) {
            intent.putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse(currentUri));
        }
        try {
            mActivity.startActivityForResult(intent, REQUEST_FOLDER);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot open desktop folder picker", e);
            mActivity.setErrorStatus(
                    "FILES-001",
                    mActivity.getString(
                            R.string.status_desktop_folder_failed,
                            e.getMessage()),
                    "",
                    e);
        }
    }

    void clearFolder() {
        final WorkspaceProfileStore.Profile profile =
                mActivity.getWorkspaceProfile();
        final String previous = profile.folderUri;
        profile.folderUri = null;
        mActivity.saveWorkspaceProfile();
        mLoadedFolderUri = null;
        mFiles = Collections.emptyList();
        if (previous != null) {
            try {
                mActivity.getContentResolver()
                        .releasePersistableUriPermission(
                                Uri.parse(previous),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // The provider may already have revoked the grant.
            }
        }
        render(mActivity.getLauncherApps());
        mActivity.setStatus(R.string.status_desktop_folder_hidden);
    }

    void refreshFolder(final boolean force) {
        if (!mActivity.isDesktopShell()) {
            return;
        }
        final String folderUri =
                mActivity.getWorkspaceProfile().folderUri;
        if (folderUri == null || folderUri.length() == 0) {
            if (!mFiles.isEmpty()) {
                mFiles = Collections.emptyList();
                render(mActivity.getLauncherApps());
            }
            mLoadedFolderUri = null;
            return;
        }
        if (!force && folderUri.equals(mLoadedFolderUri)) {
            return;
        }
        mLoadedFolderUri = folderUri;
        final int generation = ++mLoadGeneration;
        new Thread(() -> {
            final List<DesktopFile> files;
            try {
                files = mFilesRepository.load(Uri.parse(folderUri));
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot load desktop folder " + folderUri, e);
                mActivity.runOnUiThread(() -> {
                    if (generation == mLoadGeneration) {
                        mFiles = Collections.emptyList();
                        render(mActivity.getLauncherApps());
                        mActivity.setErrorStatus(
                                "FILES-002",
                                mActivity.getString(
                                        R.string.status_desktop_folder_failed,
                                        e.getMessage()),
                                "",
                                e);
                    }
                });
                return;
            }
            mActivity.runOnUiThread(() -> {
                if (generation != mLoadGeneration
                        || mActivity.isActivityUnavailable()) {
                    return;
                }
                mFiles = files;
                render(mActivity.getLauncherApps());
            });
        }, "MagicDeskDesktopFolder").start();
    }

    private View createAppIcon(final AppItem app) {
        final LinearLayout item = new LinearLayout(mActivity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(8), dp(6), dp(8), dp(6));
        if (mActivity.isWorkspaceApp(app.packageName)) {
            item.setBackground(mUi.rounded(
                    0x55172033,
                    dp(8),
                    DesktopUiFactory.COLOR_AMBER));
        }
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(view -> {
            mActivity.hideAllPanels();
            mActivity.launchDefault(app);
        });

        final int touchSlop =
                ViewConfiguration.get(mActivity).getScaledTouchSlop();
        final float[] dragDown = new float[2];
        final boolean[] dragging = new boolean[1];
        item.setOnTouchListener((view, event) -> {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                dragDown[0] = event.getX();
                dragDown[1] = event.getY();
                dragging[0] = false;
            } else if (action == MotionEvent.ACTION_MOVE
                    && !dragging[0]
                    && (Math.abs(event.getX() - dragDown[0]) > touchSlop
                            || Math.abs(event.getY() - dragDown[1])
                                    > touchSlop)) {
                dragging[0] = startShortcutDrag(view, app);
                return dragging[0];
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                dragging[0] = false;
            }
            return false;
        });
        item.setOnDragListener((view, event) ->
                handleShortcutDrop(event, app.packageName));
        mActivity.registerContextTarget(item, app, null);

        final ImageView icon = new ImageView(mActivity);
        icon.setImageDrawable(app.icon);
        item.addView(icon, new LinearLayout.LayoutParams(
                desktopDp(44, 34), desktopDp(44, 34)));

        final TextView label = createItemLabel(app.label);
        final LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(6), 0, 0);
        item.addView(label, labelParams);
        return item;
    }

    private boolean startShortcutDrag(
            final View view,
            final AppItem app) {
        final ClipData data = ClipData.newPlainText(
                mActivity.getString(R.string.desktop_drag_label),
                app.packageName);
        final View.DragShadowBuilder shadow =
                new View.DragShadowBuilder(view);
        return view.startDragAndDrop(data, shadow, app.packageName, 0);
    }

    private View createFileIcon(final DesktopFile file) {
        final LinearLayout item = new LinearLayout(mActivity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(8), dp(6), dp(8), dp(6));
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(view -> openFile(file));

        final ImageView icon = new ImageView(mActivity);
        icon.setScaleType(file.thumbnail == null
                ? ImageView.ScaleType.CENTER_INSIDE
                : ImageView.ScaleType.CENTER_CROP);
        if (file.thumbnail != null) {
            icon.setImageBitmap(file.thumbnail);
            icon.setBackground(mUi.rounded(
                    0x66111827,
                    dp(6),
                    0x99E5E7EB));
            icon.setClipToOutline(true);
            icon.setPadding(dp(1), dp(1), dp(1), dp(1));
        } else {
            icon.setImageResource(file.directory
                    ? R.drawable.ic_desktop_folder
                    : fileIcon(file.mimeType));
        }
        icon.setContentDescription(file.name);
        item.addView(icon, new LinearLayout.LayoutParams(
                desktopDp(44, 34), desktopDp(44, 34)));

        final TextView label = createItemLabel(file.name);
        final LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(6), 0, 0);
        item.addView(label, labelParams);
        return item;
    }

    private int fileIcon(final String mimeType) {
        if (mimeType != null && mimeType.startsWith("image/")) {
            return R.drawable.ic_desktop_file_image;
        }
        if (mimeType != null
                && (mimeType.startsWith("audio/")
                        || mimeType.startsWith("video/"))) {
            return R.drawable.ic_desktop_file_media;
        }
        if ("application/pdf".equals(mimeType)) {
            return R.drawable.ic_desktop_file_pdf;
        }
        if (mimeType != null
                && (mimeType.startsWith("text/")
                        || mimeType.contains("json")
                        || mimeType.contains("xml"))) {
            return R.drawable.ic_desktop_file_text;
        }
        if (mimeType != null
                && (mimeType.contains("zip")
                        || mimeType.contains("archive")
                        || mimeType.contains("compressed"))) {
            return R.drawable.ic_desktop_file_archive;
        }
        return R.drawable.ic_desktop_file_document;
    }

    private TextView createItemLabel(final CharSequence text) {
        final TextView label = new TextView(mActivity);
        label.setText(text);
        label.setTextColor(DesktopUiFactory.COLOR_TEXT);
        label.setTextSize(
                mActivity.isCompactDesktopPreview() ? 10 : 12);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setShadowLayer(dp(2), 0, dp(1), 0xE6000000);
        return label;
    }

    private GridLayout.LayoutParams createItemParams() {
        final GridLayout.LayoutParams params =
                new GridLayout.LayoutParams();
        params.width = desktopDp(104, 78);
        params.height = desktopDp(94, 74);
        params.setMargins(
                desktopDp(4, 2),
                desktopDp(4, 2),
                desktopDp(4, 2),
                desktopDp(4, 2));
        return params;
    }

    private int getColumnCount() {
        final int availableDp = Math.max(
                1,
                mActivity.getResources().getConfiguration().screenWidthDp
                        - (mActivity.isCompactDesktopPreview() ? 20 : 48));
        final int cellDp =
                mActivity.isCompactDesktopPreview() ? 82 : 112;
        return Math.max(1, Math.min(12, availableDp / cellDp));
    }

    private int getItemCapacity() {
        final int heightDp =
                mActivity.getResources().getConfiguration().screenHeightDp;
        final int reservedDp =
                mActivity.isCompactDesktopPreview() ? 116 : 158;
        final int cellDp =
                mActivity.isCompactDesktopPreview() ? 78 : 102;
        final int rows =
                Math.max(1, (heightDp - reservedDp) / cellDp);
        return getColumnCount() * rows;
    }

    private boolean handleShortcutDrop(
            final DragEvent event,
            final String targetPackage) {
        if (event.getAction() != DragEvent.ACTION_DROP) {
            return event.getAction() == DragEvent.ACTION_DRAG_STARTED
                    && event.getLocalState() instanceof String;
        }
        final Object state = event.getLocalState();
        if (!(state instanceof String)) {
            return false;
        }
        reorderShortcut((String) state, targetPackage);
        return true;
    }

    private boolean handleGridDrop(final DragEvent event) {
        final Object state = event.getLocalState();
        if (!(state instanceof String)) {
            return false;
        }
        if (event.getAction() == DragEvent.ACTION_DROP) {
            final int cellWidth = desktopDp(112, 82);
            final int cellHeight = desktopDp(102, 78);
            final int column = Math.max(
                    0,
                    Math.min(
                            getColumnCount() - 1,
                            Math.round(event.getX())
                                    / Math.max(1, cellWidth)));
            final int row = Math.max(
                    0,
                    Math.round(event.getY())
                            / Math.max(1, cellHeight));
            moveShortcut(
                    (String) state,
                    row * getColumnCount() + column);
        }
        return true;
    }

    private void reorderShortcut(
            final String sourcePackage,
            final String targetPackage) {
        final List<String> packages =
                mActivity.getDesktopShortcutPackages();
        final int sourceIndex = packages.indexOf(sourcePackage);
        final int targetIndex = packages.indexOf(targetPackage);
        if (sourceIndex < 0
                || targetIndex < 0
                || sourceIndex == targetIndex) {
            return;
        }
        moveShortcut(sourcePackage, targetIndex);
    }

    private void moveShortcut(
            final String sourcePackage,
            final int requestedIndex) {
        final List<String> packages =
                mActivity.getDesktopShortcutPackages();
        final int sourceIndex = packages.indexOf(sourcePackage);
        if (sourceIndex < 0) {
            return;
        }
        packages.remove(sourceIndex);
        final int targetIndex =
                Math.max(0, Math.min(requestedIndex, packages.size()));
        packages.add(targetIndex, sourcePackage);
        mActivity.saveDesktopShortcutPackages(packages);
        render(mActivity.getLauncherApps());
    }

    private void openFile(final DesktopFile file) {
        mActivity.hideAllPanels();
        final Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                        file.uri,
                        file.mimeType == null ? "*/*" : file.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
        final ActivityOptions options = ActivityOptions.makeBasic();
        DesktopShellActivity.invokeIntOption(
                options,
                "setLaunchDisplayId",
                mActivity.getCurrentDisplayId());
        try {
            mActivity.startActivity(intent, options.toBundle());
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot open desktop file " + file.uri, e);
            mActivity.setErrorStatus(
                    "FILES-003",
                    mActivity.getString(
                            R.string.status_desktop_file_failed,
                            file.name),
                    "mime=" + file.mimeType,
                    e);
        }
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }

    private int desktopDp(
            final int normalValue,
            final int compactValue) {
        return mUi.desktopDp(
                normalValue,
                compactValue,
                mActivity.isCompactDesktopPreview());
    }
}
