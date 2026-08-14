package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ShellFileListAdapter extends BaseAdapter {
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
        boolean onDrop(DragEvent event, ShellFileInfo destination);
    }

    private static final int COLOR_BACKGROUND = Color.rgb(9, 13, 20);
    private static final int COLOR_ACTIVE = Color.rgb(31, 44, 58);
    private static final int COLOR_TEXT = Color.rgb(232, 238, 245);
    private static final int COLOR_MUTED = Color.rgb(157, 170, 184);
    private static final int COLOR_ACCENT = Color.rgb(34, 211, 238);

    private final Context mContext;
    private final ClickListener mClickListener;
    private final SelectionListener mListener;
    private final ContextListener mContextListener;
    private final LongClickListener mLongClickListener;
    private final DropListener mDropListener;
    private final List<ShellFileInfo> mFiles = new ArrayList<>();
    private final Set<String> mSelected = new HashSet<>();
    private boolean mDetails = true;

    ShellFileListAdapter(
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
            final boolean details) {
        mFiles.clear();
        mFiles.addAll(files);
        mSelected.clear();
        mSelected.addAll(selected);
        mDetails = details;
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
    public View getView(
            final int position,
            final View recycled,
            final ViewGroup parent) {
        final Row row = recycled instanceof LinearLayout
                && recycled.getTag() instanceof Row
                ? (Row) recycled.getTag() : createRow();
        final ShellFileInfo file = getItem(position);
        row.checkbox.setOnCheckedChangeListener(null);
        row.checkbox.setChecked(mSelected.contains(file.absolutePath));
        row.checkbox.setOnCheckedChangeListener((button, checked) ->
                mListener.onSelectionChanged(file, checked));
        row.icon.setImageResource(file.directory
                ? R.drawable.ic_desktop_folder
                : FileIconResolver.forMimeType(file.mimeType));
        row.name.setText(file.name);
        row.name.setTypeface(null, file.directory
                ? Typeface.BOLD : Typeface.NORMAL);
        row.details.setVisibility(mDetails ? View.VISIBLE : View.GONE);
        row.details.setText(details(file));
        row.root.setBackgroundColor(
                mSelected.contains(file.absolutePath)
                        ? COLOR_ACTIVE : COLOR_BACKGROUND);
        row.metaState = 0;
        row.eventTime = 0L;
        row.root.setOnTouchListener((view, event) -> {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN
                    || action == MotionEvent.ACTION_BUTTON_PRESS
                    || action == MotionEvent.ACTION_UP) {
                row.metaState = event.getMetaState();
                row.eventTime = event.getEventTime();
            }
            return false;
        });
        row.root.setOnClickListener(view ->
                mClickListener.onClick(
                        file, row.metaState, row.eventTime));
        row.root.setOnLongClickListener(view ->
                mLongClickListener.onLongClick(
                        view, file, row.metaState));
        row.root.setOnContextClickListener(view ->
                mContextListener.onContextClick(view, file));
        row.root.setOnDragListener(file.directory
                ? (view, event) -> handleFolderDrag(
                        row, file, event)
                : null);
        return row.root;
    }

    private boolean handleFolderDrag(
            final Row row,
            final ShellFileInfo folder,
            final DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return event.getClipDescription() != null;
            case DragEvent.ACTION_DRAG_ENTERED:
                row.root.setBackgroundColor(COLOR_ACTIVE);
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
            case DragEvent.ACTION_DRAG_ENDED:
                row.root.setBackgroundColor(
                        mSelected.contains(folder.absolutePath)
                                ? COLOR_ACTIVE : COLOR_BACKGROUND);
                return true;
            case DragEvent.ACTION_DROP:
                row.root.setBackgroundColor(
                        mSelected.contains(folder.absolutePath)
                                ? COLOR_ACTIVE : COLOR_BACKGROUND);
                return mDropListener.onDrop(event, folder);
            default:
                return true;
        }
    }

    private Row createRow() {
        final LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(4), dp(4), dp(8), dp(4));
        root.setMinimumHeight(dp(54));
        final CheckBox checkbox = new CheckBox(mContext);
        checkbox.setFocusable(false);
        root.addView(checkbox, new LinearLayout.LayoutParams(
                dp(42), dp(46)));
        final ImageView icon = new ImageView(mContext);
        icon.setColorFilter(COLOR_ACCENT);
        icon.setPadding(dp(7), dp(7), dp(7), dp(7));
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
        final Row row = new Row(root, checkbox, icon, name, details);
        root.setTag(row);
        return row;
    }

    private String details(final ShellFileInfo file) {
        final String type = file.symbolicLink
                ? "link" : file.directory ? "folder" : file.mimeType;
        final String size = file.directory ? ""
                : "  " + formatSize(file.size);
        final String modified = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(file.modified));
        return type + size + "  " + modified;
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

    private int dp(final int value) {
        return Math.round(value * mContext.getResources()
                .getDisplayMetrics().density);
    }

    private static final class Row {
        final LinearLayout root;
        final CheckBox checkbox;
        final ImageView icon;
        final TextView name;
        final TextView details;
        int metaState;
        long eventTime;

        Row(
                final LinearLayout root,
                final CheckBox checkbox,
                final ImageView icon,
                final TextView name,
                final TextView details) {
            this.root = root;
            this.checkbox = checkbox;
            this.icon = icon;
            this.name = name;
            this.details = details;
        }
    }
}
