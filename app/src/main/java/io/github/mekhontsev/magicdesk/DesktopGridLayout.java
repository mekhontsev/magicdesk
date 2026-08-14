package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;

/** Programmatic desktop grid whose constructor requires explicit cell geometry. */
@SuppressLint("ViewConstructor")
final class DesktopGridLayout extends ViewGroup {
    interface Listener {
        void onGridSizeChanged(int columns, int rows);

        void onItemDropped(String itemId, int column, int row);

        boolean onExternalDrop(DragEvent event);
    }

    private final int mCellWidth;
    private final int mCellHeight;
    private Listener mListener;
    private int mColumns;
    private int mRows;

    DesktopGridLayout(
            final Context context,
            final int cellWidth,
            final int cellHeight) {
        this(context, null, cellWidth, cellHeight);
    }

    private DesktopGridLayout(
            final Context context,
            final AttributeSet attributes,
            final int cellWidth,
            final int cellHeight) {
        super(context, attributes);
        mCellWidth = Math.max(1, cellWidth);
        mCellHeight = Math.max(1, cellHeight);
        setClipChildren(false);
        setOnDragListener((view, event) -> handleDrag(event, 0, 0));
    }

    void setListener(final Listener listener) {
        mListener = listener;
    }

    int getColumnCount() {
        return mColumns;
    }

    int getRowCount() {
        return mRows;
    }

    int getCellWidth() {
        return mCellWidth;
    }

    int getCellHeight() {
        return mCellHeight;
    }

    void addItem(
            final View view,
            final String itemId,
            final DesktopPlacement placement) {
        final LayoutParams params = new LayoutParams(itemId, placement);
        view.setOnDragListener((target, event) -> handleDrag(
                event, target.getLeft(), target.getTop()));
        addView(view, params);
    }

    private boolean handleDrag(
            final DragEvent event,
            final int offsetX,
            final int offsetY) {
        final Object state = event.getLocalState();
        final String desktopItemId = desktopItemId(state);
        if (desktopItemId == null) {
            if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) {
                return mListener != null
                        && event.getClipDescription() != null;
            }
            if (event.getAction() == DragEvent.ACTION_DROP) {
                return mListener != null && mListener.onExternalDrop(event);
            }
            return mListener != null;
        }
        if (event.getAction() == DragEvent.ACTION_DROP && mListener != null) {
            final int column = Math.max(0, Math.min(
                    Math.max(0, mColumns - 1),
                    (int) ((event.getX() + offsetX) / mCellWidth)));
            final int row = Math.max(0, Math.min(
                    Math.max(0, mRows - 1),
                    (int) ((event.getY() + offsetY) / mCellHeight)));
            mListener.onItemDropped(desktopItemId, column, row);
        }
        return true;
    }

    private static String desktopItemId(final Object state) {
        if (state instanceof DragToken) {
            return ((DragToken) state).itemId;
        }
        if (state instanceof FileDragPayload) {
            return ((FileDragPayload) state).desktopItemId;
        }
        return null;
    }

    @Override
    protected void onMeasure(
            final int widthMeasureSpec,
            final int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int height = MeasureSpec.getSize(heightMeasureSpec);
        final int columns = Math.max(1, width / mCellWidth);
        final int rows = Math.max(1, height / mCellHeight);
        updateGridSize(columns, rows);
        for (int index = 0; index < getChildCount(); index++) {
            final View child = getChildAt(index);
            final LayoutParams params = (LayoutParams) child.getLayoutParams();
            final int childWidth = Math.max(
                    1,
                    params.placement.columnSpan * mCellWidth
                            - params.leftMargin - params.rightMargin);
            final int childHeight = Math.max(
                    1,
                    params.placement.rowSpan * mCellHeight
                            - params.topMargin - params.bottomMargin);
            child.measure(
                    MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(
                resolveSize(width, widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(
            final boolean changed,
            final int left,
            final int top,
            final int right,
            final int bottom) {
        for (int index = 0; index < getChildCount(); index++) {
            final View child = getChildAt(index);
            final LayoutParams params = (LayoutParams) child.getLayoutParams();
            final int childLeft = params.placement.column * mCellWidth
                    + params.leftMargin;
            final int childTop = params.placement.row * mCellHeight
                    + params.topMargin;
            child.layout(
                    childLeft,
                    childTop,
                    childLeft + child.getMeasuredWidth(),
                    childTop + child.getMeasuredHeight());
        }
    }

    private void updateGridSize(final int columns, final int rows) {
        if (columns == mColumns && rows == mRows) {
            return;
        }
        mColumns = columns;
        mRows = rows;
        if (mListener != null) {
            post(() -> {
                if (mListener != null
                        && mColumns == columns
                        && mRows == rows) {
                    mListener.onGridSizeChanged(columns, rows);
                }
            });
        }
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(
                "", new DesktopPlacement(0, 0, 1, 1));
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(
            final ViewGroup.LayoutParams params) {
        return new LayoutParams(params);
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(
            final AttributeSet attributes) {
        return new LayoutParams(getContext(), attributes);
    }

    @Override
    protected boolean checkLayoutParams(final ViewGroup.LayoutParams params) {
        return params instanceof LayoutParams;
    }

    static final class DragToken {
        final String itemId;

        DragToken(final String itemId) {
            this.itemId = itemId;
        }
    }

    static final class LayoutParams extends MarginLayoutParams {
        final String itemId;
        final DesktopPlacement placement;

        LayoutParams(
                final String itemId,
                final DesktopPlacement placement) {
            super(MATCH_PARENT, MATCH_PARENT);
            this.itemId = itemId;
            this.placement = placement;
        }

        LayoutParams(final ViewGroup.LayoutParams source) {
            super(source);
            if (source instanceof LayoutParams) {
                final LayoutParams desktopSource = (LayoutParams) source;
                itemId = desktopSource.itemId;
                placement = desktopSource.placement;
            } else {
                itemId = "";
                placement = new DesktopPlacement(0, 0, 1, 1);
            }
        }

        LayoutParams(final Context context, final AttributeSet attributes) {
            super(context, attributes);
            itemId = "";
            placement = new DesktopPlacement(0, 0, 1, 1);
        }
    }
}
