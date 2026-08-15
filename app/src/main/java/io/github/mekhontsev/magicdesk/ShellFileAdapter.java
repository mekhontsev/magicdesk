package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ShellFileAdapter extends BaseAdapter {
    interface ClickListener {
        void onClick(ShellFileInfo file, int metaState, long eventTime);
    }

    interface SelectionListener {
        void onSelectionChanged(ShellFileInfo file, boolean selected);
    }

    interface ContextListener {
        boolean onContextClick(View anchor, ShellFileInfo file);
    }

    interface LongClickListener {
        boolean onLongClick(
                View anchor, ShellFileInfo file, int metaState);
    }

    interface DropListener {
        boolean onDrop(DragEvent event, String destinationPath);
    }

    private static final int COLOR_BACKGROUND = Color.rgb(9, 13, 20);
    private static final int COLOR_ACTIVE = Color.rgb(31, 44, 58);
    private static final int COLOR_TEXT = Color.rgb(232, 238, 245);
    private static final int COLOR_MUTED = Color.rgb(157, 170, 184);
    private final Context mContext;
    private final ClickListener mClickListener;
    private final SelectionListener mListener;
    private final ContextListener mContextListener;
    private final LongClickListener mLongClickListener;
    private final DropListener mDropListener;
    private final List<ShellFileInfo> mFiles = new ArrayList<>();
    private final Set<String> mSelected = new HashSet<>();
    private final Map<String, DesktopFolderShortcut> mFolderShortcuts =
            new LinkedHashMap<>();
    private FileManagerLayoutMode mLayoutMode =
            FileManagerLayoutMode.LIST;

    ShellFileAdapter(
            final Context context,
            final ClickListener clickListener,
            final SelectionListener listener,
            final ContextListener contextListener,
            final LongClickListener longClickListener,
            final DropListener dropListener) {
        mContext = context;
        mClickListener = clickListener;
        mListener = listener;
        mContextListener = contextListener;
        mLongClickListener = longClickListener;
        mDropListener = dropListener;
    }

    void set(
            final List<ShellFileInfo> files,
            final Set<String> selected,
            final Map<String, DesktopFolderShortcut> folderShortcuts) {
        mFiles.clear();
        mFiles.addAll(files);
        mSelected.clear();
        mSelected.addAll(selected);
        mFolderShortcuts.clear();
        mFolderShortcuts.putAll(folderShortcuts);
        notifyDataSetChanged();
    }

    void setLayoutMode(final FileManagerLayoutMode layoutMode) {
        if (mLayoutMode == layoutMode) {
            return;
        }
        mLayoutMode = layoutMode;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return mFiles.size();
    }

    @Override
    public ShellFileInfo getItem(final int position) {
        return mFiles.get(position);
    }

    @Override
    public long getItemId(final int position) {
        return getItem(position).absolutePath.hashCode();
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public int getViewTypeCount() {
        return FileManagerLayoutMode.values().length;
    }

    @Override
    public int getItemViewType(final int position) {
        return mLayoutMode.ordinal();
    }

    @Override
    public View getView(
            final int position,
            final View recycled,
            final ViewGroup parent) {
        final ItemView item = recycled != null
                && recycled.getTag() instanceof ItemView
                && ((ItemView) recycled.getTag()).layoutMode == mLayoutMode
                ? (ItemView) recycled.getTag()
                : createItemView();
        final ShellFileInfo file = getItem(position);
        final DesktopFolderShortcut shortcut =
                mFolderShortcuts.get(file.absolutePath);
        if (item.checkbox != null) {
            item.checkbox.setOnCheckedChangeListener(null);
            item.checkbox.setChecked(
                    mSelected.contains(file.absolutePath));
            item.checkbox.setOnCheckedChangeListener((button, checked) ->
                    mListener.onSelectionChanged(file, checked));
        }
        item.icon.clearColorFilter();
        item.icon.setImageResource(shortcut == null
                ? FileIconResolver.forFile(file.directory, file.mimeType)
                : R.drawable.ic_desktop_folder_link);
        item.icon.setAlpha(shortcut == null || shortcut.available ? 1f : 0.45f);
        final String displayName = shortcut == null ? file.name : shortcut.name;
        item.icon.setContentDescription(displayName);
        item.name.setText(displayName);
        item.name.setTypeface(null, file.directory || shortcut != null
                ? Typeface.BOLD : Typeface.NORMAL);
        if (item.details != null) {
            item.details.setText(shortcut == null
                    ? details(file) : shortcut.targetPath);
        }
        item.root.setBackgroundColor(
                mSelected.contains(file.absolutePath)
                        ? COLOR_ACTIVE : COLOR_BACKGROUND);
        item.metaState = 0;
        item.eventTime = 0L;
        new DeferredContextDragGesture(
                item.root,
                true,
                true,
                new DeferredContextDragGesture.Listener() {
                    @Override
                    public boolean onStartDrag(
                            final View target, final MotionEvent event) {
                        return mLongClickListener.onLongClick(
                                target, file, event.getMetaState());
                    }

                    @Override
                    public void onShowContextMenu(final View target) {
                        mContextListener.onContextClick(target, file);
                    }

                    @Override
                    public boolean onTap(
                            final View target, final MotionEvent event) {
                        mClickListener.onClick(
                                file, event.getMetaState(), event.getEventTime());
                        return true;
                    }

                    @Override
                    public void onPointerEvent(final MotionEvent event) {
                        final int action = event.getActionMasked();
                        if (action == MotionEvent.ACTION_DOWN
                                || action == MotionEvent.ACTION_BUTTON_PRESS
                                || action == MotionEvent.ACTION_UP) {
                            item.metaState = event.getMetaState();
                            item.eventTime = event.getEventTime();
                        }
                    }
                });
        item.root.setOnClickListener(view ->
                mClickListener.onClick(
                        file,
                        item.metaState,
                        android.os.SystemClock.uptimeMillis()));
        item.root.setOnContextClickListener(view ->
                mContextListener.onContextClick(view, file));
        item.root.setOnDragListener(file.directory || shortcut != null
                ? (view, event) -> handleFolderDrag(
                        item,
                        file,
                        shortcut == null
                                ? file.absolutePath : shortcut.targetPath,
                        event)
                : null);
        return item.root;
    }

    private boolean handleFolderDrag(
            final ItemView item,
            final ShellFileInfo folder,
            final String destinationPath,
            final DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return event.getClipDescription() != null;
            case DragEvent.ACTION_DRAG_ENTERED:
                item.root.setBackgroundColor(COLOR_ACTIVE);
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
            case DragEvent.ACTION_DRAG_ENDED:
                item.root.setBackgroundColor(
                        mSelected.contains(folder.absolutePath)
                                ? COLOR_ACTIVE : COLOR_BACKGROUND);
                return true;
            case DragEvent.ACTION_DROP:
                item.root.setBackgroundColor(
                        mSelected.contains(folder.absolutePath)
                                ? COLOR_ACTIVE : COLOR_BACKGROUND);
                return mDropListener.onDrop(event, destinationPath);
            default:
                return true;
        }
    }

    private ItemView createItemView() {
        return mLayoutMode == FileManagerLayoutMode.GRID
                ? createGridItem() : createListItem();
    }

    private ItemView createListItem() {
        final LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(4), dp(4), dp(8), dp(4));
        root.setMinimumHeight(dp(54));
        prepareRoot(root);
        final CheckBox checkbox = new CheckBox(mContext);
        checkbox.setFocusable(false);
        root.addView(checkbox, new LinearLayout.LayoutParams(
                dp(42), dp(46)));
        final ImageView icon = new ImageView(mContext);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setPadding(dp(3), dp(3), dp(3), dp(3));
        root.addView(icon, new LinearLayout.LayoutParams(
                dp(42), dp(42)));
        final LinearLayout labels = new LinearLayout(mContext);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        final TextView name = new TextView(mContext);
        name.setTextColor(COLOR_TEXT);
        name.setTextSize(15f);
        name.setSingleLine(true);
        final TextView details = new TextView(mContext);
        details.setTextColor(COLOR_MUTED);
        details.setTextSize(11f);
        details.setSingleLine(true);
        labels.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        labels.addView(details, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return finishItemView(root, checkbox, icon, name, details,
                FileManagerLayoutMode.LIST);
    }

    private ItemView createGridItem() {
        final LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(6), dp(5), dp(6), dp(6));
        root.setMinimumHeight(dp(118));
        prepareRoot(root);

        final FrameLayout iconArea = new FrameLayout(mContext);
        final ImageView icon = new ImageView(mContext);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setPadding(dp(3), dp(3), dp(3), dp(3));
        final FrameLayout.LayoutParams iconParams =
                new FrameLayout.LayoutParams(dp(58), dp(58));
        iconParams.gravity = Gravity.CENTER;
        iconArea.addView(icon, iconParams);
        root.addView(iconArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        final TextView name = new TextView(mContext);
        name.setTextColor(COLOR_TEXT);
        name.setTextSize(13f);
        name.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        return finishItemView(root, null, icon, name, null,
                FileManagerLayoutMode.GRID);
    }

    private void prepareRoot(final ViewGroup root) {
        root.setClickable(true);
        root.setFocusable(true);
    }

    private ItemView finishItemView(
            final ViewGroup root,
            final CheckBox checkbox,
            final ImageView icon,
            final TextView name,
            final TextView details,
            final FileManagerLayoutMode layoutMode) {
        final ItemView item = new ItemView(
                root, checkbox, icon, name, details, layoutMode);
        root.setTag(item);
        return item;
    }

    private String details(final ShellFileInfo file) {
        final String type = file.symbolicLink
                ? "link" : file.directory ? "folder" : file.mimeType;
        final String size = file.directory ? ""
                : "  " + FileSizeFormatter.format(file.size);
        final String modified = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(file.modified));
        return type + size + "  " + modified;
    }

    private int dp(final int value) {
        return Math.round(value * mContext.getResources()
                .getDisplayMetrics().density);
    }

    private static final class ItemView {
        final ViewGroup root;
        final CheckBox checkbox;
        final ImageView icon;
        final TextView name;
        final TextView details;
        final FileManagerLayoutMode layoutMode;
        int metaState;
        long eventTime;

        ItemView(
                final ViewGroup root,
                final CheckBox checkbox,
                final ImageView icon,
                final TextView name,
                final TextView details,
                final FileManagerLayoutMode layoutMode) {
            this.root = root;
            this.checkbox = checkbox;
            this.icon = icon;
            this.name = name;
            this.details = details;
            this.layoutMode = layoutMode;
        }
    }
}
