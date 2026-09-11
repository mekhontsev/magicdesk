package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Point;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.MagicDeskTerminalRenderer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalHyperlink;
import com.termux.terminal.TerminalImage;

/** Interactive MagicDesk terminal surface with its own renderer. */
final class ConsoleTerminalView extends View {
    interface Actions {
        void copySelection();

        void pasteClipboard();

        void showLink(com.termux.terminal.TerminalHyperlink link);

        void showImage(TerminalImage image);
    }

    private static final int NO_SELECTION = Integer.MIN_VALUE;
    private static final int SCROLL_ROWS = 3;

    private MagicDeskTerminalRenderer mRenderer;
    private final GestureDetector mGestures;
    private final ScaleGestureDetector mScaleGestures;
    private int mContentPadding;
    private int mTouchSlop;
    private int mFontSizeSp;
    private float mPinchFontSizeSp;
    private float mFontWheelRemainder;
    private boolean mFontScaleGesture;
    private int mAppliedCellWidth;
    private int mAppliedCellHeight;
    private final ConsoleTerminalInput mInput =
            new ConsoleTerminalInput(KeyCharacterMap::getDeadChar);

    private ConsoleTerminalSession mSession;
    private Object mInputAttachment;
    private Actions mClipboardActions;
    private int mColumns = 80;
    private int mRows = 24;
    private int mTopRow;
    private int mSelectionStartColumn = NO_SELECTION;
    private int mSelectionStartRow = NO_SELECTION;
    private int mSelectionEndColumn = NO_SELECTION;
    private int mSelectionEndRow = NO_SELECTION;
    private float mDownX;
    private float mDownY;
    private float mLastTouchY;
    private float mTouchScrollRemainder;
    private float mWheelScrollRemainder;
    private boolean mSelecting;
    private boolean mTouchScrolling;
    private boolean mTerminalMousePress;
    private int mTerminalMouseButton;
    private boolean mImageGesture;

    ConsoleTerminalView(final Context context) {
        super(context);
        mFontSizeSp = ConsolePreferences.fontSizeSp(context);
        refreshFontMetrics();
        mGestures = new GestureDetector(
                context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(final MotionEvent event) {
                        return true;
                    }

                    @Override
                    public void onLongPress(final MotionEvent event) {
                        if (isTouch(event) && !mTouchScrolling) {
                            if (!showImageAt(event)) beginSelection(event);
                        }
                    }
                });
        mScaleGestures = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(final ScaleGestureDetector detector) {
                mPinchFontSizeSp = mFontSizeSp;
                return true;
            }

            @Override public boolean onScale(final ScaleGestureDetector detector) {
                mPinchFontSizeSp = Math.max(ConsolePreferences.MIN_FONT_SIZE_SP,
                        Math.min(ConsolePreferences.MAX_FONT_SIZE_SP, mPinchFontSizeSp * detector.getScaleFactor()));
                setFontSizeSp(Math.round(mPinchFontSizeSp));
                return true;
            }
        });
        mScaleGestures.setQuickScaleEnabled(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setVerticalScrollBarEnabled(true);
    }

    void attach(
            final ConsoleTerminalSession session,
            final Actions clipboardActions) {
        if (mSession != session) { mInputAttachment = session == null ? null : new Object(); }
        mSession = session;
        mClipboardActions = clipboardActions;
        resizeTerminal();
        invalidate();
    }

    int fontSizeSp() { return mFontSizeSp; }

    void setFontSizeSp(final int size) {
        final int bounded = ConsolePreferences.clampFontSize(size);
        if (mFontSizeSp == bounded) { return; }
        mFontSizeSp = bounded;
        clearSelection();
        refreshFontMetrics();
    }

    @Override
    protected void onConfigurationChanged(final Configuration configuration) {
        super.onConfigurationChanged(configuration);
        refreshFontMetrics();
    }

    private void refreshFontMetrics() {
        // Android applies the current display density and nonlinear accessibility font scale.
        mRenderer = new MagicDeskTerminalRenderer(getResources().getFont(R.font.console_mono), TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, mFontSizeSp, getResources().getDisplayMetrics()));
        mContentPadding = Math.round(6.0f * getResources().getDisplayMetrics().density);
        mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        resizeTerminal();
        clampTopRow();
        invalidate();
    }

    int columns() {
        return mColumns;
    }

    int rows() {
        return mRows;
    }

    int cellWidth() {
        return Math.max(1, Math.round(mRenderer.cellWidth()));
    }

    int cellHeight() {
        return Math.max(1, Math.round(mRenderer.cellHeight()));
    }

    void onTerminalChanged() {
        if (mSession != null) {
            final TerminalEmulator emulator = mSession.emulator();
            final int scrolled = emulator.getScrollCounter();
            emulator.clearScrollCounter();
            if (mTopRow < 0 && scrolled > 0) {
                mTopRow -= scrolled;
            }
        }
        clampTopRow();
        invalidate();
    }

    boolean hasSelection() {
        return mSelectionStartRow != NO_SELECTION;
    }

    String selectedText() {
        if (!hasSelection() || mSession == null) {
            return "";
        }
        int startColumn = mSelectionStartColumn;
        int startRow = mSelectionStartRow;
        int endColumn = mSelectionEndColumn;
        int endRow = mSelectionEndRow;
        if (position(startRow, startColumn) > position(endRow, endColumn)) {
            final int swapColumn = startColumn;
            final int swapRow = startRow;
            startColumn = endColumn;
            startRow = endRow;
            endColumn = swapColumn;
            endRow = swapRow;
        }
        return mSession.emulator().getSelectedText(
                startColumn, startRow, endColumn, endRow);
    }

    void clearSelection() {
        mSelectionStartColumn = NO_SELECTION;
        mSelectionStartRow = NO_SELECTION;
        mSelectionEndColumn = NO_SELECTION;
        mSelectionEndRow = NO_SELECTION;
        mSelecting = false;
        invalidate();
    }

    void scrollToBottom() {
        mTopRow = 0;
        invalidate();
    }

    void jumpTo(final com.termux.terminal.TerminalMarker.Position position) {
        if (position == null || mSession == null || mSession.emulator().isAlternateBufferActive()) { return; }
        clearSelection();
        mTopRow = position.row();
        clampTopRow();
        invalidate();
    }

    void selectRange(final com.termux.terminal.TerminalCommandHistory.Range range) {
        if (range == null || mSession == null || mSession.emulator().isAlternateBufferActive()) { return; }
        jumpTo(range.start());
        if (range.start().equals(range.end())) { return; }
        mSelectionStartColumn = range.start().column();
        mSelectionStartRow = range.start().row();
        mSelectionEndColumn = range.end().column() - 1;
        mSelectionEndRow = range.end().row();
        if (mSelectionEndColumn < 0) { mSelectionEndColumn = mColumns - 1; mSelectionEndRow--; }
        invalidate();
    }

    private com.termux.terminal.TerminalHyperlink linkAt(final MotionEvent event) {
        if (mSession == null || event.getX() < mContentPadding || event.getY() < mContentPadding
                || event.getX() >= getWidth() - mContentPadding || event.getY() >= getHeight() - mContentPadding) {
            return null;
        }
        final Point cell = cellAt(event);
        return mSession.emulator().getScreen().getHyperlink(cell.x, mTopRow + cell.y);
    }

    private TerminalImage imageAt(final MotionEvent event) {
        if (mSession == null) return null;
        final float column = (event.getX() - mContentPadding) / mRenderer.cellWidth();
        final float row = (event.getY() - mContentPadding) / mRenderer.cellHeight();
        if (column < 0 || column >= mColumns || row < 0 || row >= mRows) return null;
        final TerminalEmulator emulator = mSession.emulator();
        return emulator.getGraphics().imageAt(emulator.getScreen(), column, mTopRow + row,
                mRenderer.cellWidth(), mRenderer.cellHeight());
    }

    private boolean showImageAt(final MotionEvent event) {
        final TerminalImage image = imageAt(event);
        if (image == null || mClipboardActions == null) return false;
        clearSelection();
        mImageGesture = true;
        mClipboardActions.showImage(image);
        return true;
    }

    @Override public android.view.PointerIcon onResolvePointerIcon(final MotionEvent event, final int index) {
        return android.view.PointerIcon.getSystemIcon(getContext(), linkAt(event) == null
                ? android.view.PointerIcon.TYPE_TEXT : android.view.PointerIcon.TYPE_HAND);
    }

    @Override public boolean onHoverEvent(final MotionEvent event) {
        final var link = event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT ? null : linkAt(event);
        final String tooltip = link == null ? null : link.uri();
        if (!java.util.Objects.equals(getTooltipText(), tooltip)) { setTooltipText(tooltip); }
        return super.onHoverEvent(event);
    }

    String visibleText() {
        if (mSession == null) {
            return "";
        }
        return mSession.emulator().getSelectedText(
                0,
                mTopRow,
                Math.max(0, mColumns - 1),
                mTopRow + Math.max(0, mRows - 1));
    }

    boolean sendKey(final int keyCode, final int metaState) {
        if (mSession == null || keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return false;
        }
        final long now = android.os.SystemClock.uptimeMillis();
        return onKeyDown(keyCode, new KeyEvent(
                now,
                now,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                0,
                InputDevice.SOURCE_KEYBOARD));
    }

    @Override
    protected void onSizeChanged(
            final int width,
            final int height,
            final int oldWidth,
            final int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        resizeTerminal();
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);
        if (mSession == null) {
            canvas.drawColor(0xFF000000);
            return;
        }
        canvas.save();
        canvas.translate(mContentPadding, mContentPadding);
        mRenderer.draw(
                canvas,
                mSession.emulator(),
                mTopRow,
                mRows,
                mSelectionStartColumn,
                mSelectionStartRow,
                mSelectionEndColumn,
                mSelectionEndRow,
                hasFocus());
        canvas.restore();
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        if (mSession == null || keyCode == KeyEvent.KEYCODE_BACK) {
            return super.onKeyDown(keyCode, event);
        }
        if (event.isShiftPressed() && !event.isCtrlPressed() && !event.isAltPressed()
                && (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN)) {
            scrollRows(keyCode == KeyEvent.KEYCODE_PAGE_UP ? -mRows : mRows);
            return true;
        }
        if (event.isCtrlPressed() && event.isShiftPressed()) {
            if (keyCode == KeyEvent.KEYCODE_C && mClipboardActions != null) {
                mClipboardActions.copySelection();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_V && mClipboardActions != null) {
                mClipboardActions.pasteClipboard();
                return true;
            }
        }
        final String sequence = mInput.key(event, mSession.emulator());
        if (sequence != null) {
            mSession.write(sequence);
            scrollToBottom();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(final int keyCode, final KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return super.onKeyUp(keyCode, event);
        }
        return mSession != null || super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(final MotionEvent event) {
        if (mSession == null) {
            return false;
        }
        if (handleFontScaleGesture(event)) { return true; }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) mImageGesture = false;
        mGestures.onTouchEvent(event);
        if (mImageGesture) return true;
        final TerminalEmulator emulator = mSession.emulator();
        final Point cell = cellAt(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                requestFocus();
                mDownX = event.getX();
                mDownY = event.getY();
                mLastTouchY = event.getY();
                mTouchScrollRemainder = 0;
                mTouchScrolling = false;
                // A finger is a scroll gesture until a tap completes, not a held mouse button.
                if (!isTouch(event) && emulator.isMouseTrackingActive()
                        && !isShiftPressed(event)
                        && !((event.getMetaState() & KeyEvent.META_CTRL_ON) != 0
                                && (linkAt(event) != null || imageAt(event) != null))) {
                    mTerminalMouseButton = mouseButton(event);
                    emulator.sendMouseEvent(
                            mTerminalMouseButton,
                            cell.x + 1,
                            cell.y + 1,
                            true);
                    mTerminalMousePress = true;
                    return true;
                }
                if (!isTouch(event) && isShiftPressed(event)) {
                    beginSelection(event);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mTerminalMousePress) {
                    emulator.sendMouseEvent(
                            TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED,
                            cell.x + 1,
                            cell.y + 1,
                            true);
                    return true;
                }
                if (mSelecting) {
                    updateSelection(cell);
                    return true;
                }
                if (isTouch(event)
                        && (mTouchScrolling || Math.hypot(event.getX() - mDownX,
                                event.getY() - mDownY) > mTouchSlop)) {
                    mTouchScrolling = true;
                    scrollTouch(event);
                    return true;
                }
                if (!isTouch(event)
                        && Math.hypot(
                                event.getX() - mDownX,
                                event.getY() - mDownY) > mTouchSlop) {
                    beginSelection(event);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mTerminalMousePress) {
                    emulator.sendMouseEvent(
                            mTerminalMouseButton,
                            cell.x + 1,
                            cell.y + 1,
                            false);
                    mTerminalMousePress = false;
                } else if (mSelecting) {
                    updateSelection(cell);
                    mSelecting = false;
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        && !mTouchScrolling) {
                    clearSelection();
                    if (!isShiftPressed(event) && (!emulator.isMouseTrackingActive()
                            || (event.getMetaState() & KeyEvent.META_CTRL_ON) != 0)
                            && showImageAt(event)) return true;
                    final TerminalHyperlink link = linkAt(event);
                    if (link != null && mClipboardActions != null && !isShiftPressed(event)
                            && (!emulator.isMouseTrackingActive() || (event.getMetaState() & KeyEvent.META_CTRL_ON) != 0)) {
                        mClipboardActions.showLink(link);
                        return true;
                    }
                    if (isTouch(event)) {
                        if (emulator.isMouseTrackingActive() && !isShiftPressed(event)) {
                            emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON,
                                    cell.x + 1, cell.y + 1, true);
                            emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON,
                                    cell.x + 1, cell.y + 1, false);
                        } else {
                            showSoftKeyboard();
                        }
                    }
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean onGenericMotionEvent(final MotionEvent event) {
        if (mSession == null
                || event.getActionMasked() != MotionEvent.ACTION_SCROLL) {
            return super.onGenericMotionEvent(event);
        }
        final float amount = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        if (amount == 0.0f) {
            return super.onGenericMotionEvent(event);
        }
        if ((event.getMetaState() & KeyEvent.META_CTRL_ON) != 0) {
            mFontWheelRemainder += amount;
            final int steps = (int) mFontWheelRemainder;
            mFontWheelRemainder -= steps;
            setFontSizeSp(mFontSizeSp + steps);
            return true;
        }
        mWheelScrollRemainder -= amount * SCROLL_ROWS;
        final int rows = (int) mWheelScrollRemainder;
        mWheelScrollRemainder -= rows;
        scrollTerminal(rows, event);
        return true;
    }

    private boolean handleFontScaleGesture(final MotionEvent event) {
        if (!isTouch(event)) { return false; }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { mFontScaleGesture = false; }
        if (event.getPointerCount() > 1 && !mFontScaleGesture) {
            mFontScaleGesture = true;
            clearSelection();
            // Cancel the pending long press and consume until all fingers are lifted.
            final MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            mGestures.onTouchEvent(cancel);
            cancel.recycle();
        }
        mScaleGestures.onTouchEvent(event);
        return mFontScaleGesture;
    }

    private void scrollTouch(final MotionEvent event) {
        mTouchScrollRemainder += (mLastTouchY - event.getY()) / mRenderer.cellHeight();
        mLastTouchY = event.getY();
        final int rows = (int) mTouchScrollRemainder;
        mTouchScrollRemainder -= rows;
        scrollTerminal(rows, event);
    }

    private void scrollTerminal(final int rows, final MotionEvent event) {
        if (rows == 0) { return; }
        final TerminalEmulator emulator = mSession.emulator();
        if (emulator.isMouseTrackingActive() && !isShiftPressed(event)) {
            final Point cell = cellAt(event);
            final int button = rows < 0 ? TerminalEmulator.MOUSE_WHEELUP_BUTTON
                    : TerminalEmulator.MOUSE_WHEELDOWN_BUTTON;
            for (int i = 0; i < Math.abs(rows); i++) {
                emulator.sendMouseEvent(button, cell.x + 1, cell.y + 1, true);
            }
        } else if (emulator.isAlternateBufferActive() && !isShiftPressed(event)) {
            final String key = KeyHandler.getCode(rows < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN,
                    0, emulator.isCursorKeysApplicationMode(), emulator.isKeypadApplicationMode());
            for (int i = 0; i < Math.abs(rows); i++) { mSession.write(key); }
        } else {
            scrollRows(rows);
        }
    }

    @Override
    protected int computeVerticalScrollRange() {
        return mRows + (mSession == null ? 0 : mSession.emulator().getScreen().getActiveTranscriptRows());
    }

    @Override
    protected int computeVerticalScrollExtent() {
        return mRows;
    }

    @Override
    protected int computeVerticalScrollOffset() {
        return computeVerticalScrollRange() - mRows + mTopRow;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return mSession != null;
    }

    @Override
    public InputConnection onCreateInputConnection(
            final EditorInfo editorInfo) {
        if (mSession == null) { return null; }
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        editorInfo.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_ACTION_NONE;
        return new TerminalInputConnection();
    }

    private void resizeTerminal() {
        final int availableWidth = Math.max(0, getWidth() - 2 * mContentPadding);
        final int availableHeight = Math.max(0, getHeight() - 2 * mContentPadding);
        // Attaching an unmeasured View must not reflow a retained session to 2x2.
        if (availableWidth == 0 || availableHeight == 0) { return; }
        final int columns = Math.max(2,
                (int) (availableWidth / mRenderer.cellWidth()));
        final int rows = Math.max(2,
                (int) (availableHeight / mRenderer.cellHeight()));
        if (columns == mColumns && rows == mRows && (mSession == null
                || mSession.columns() == columns && mSession.rows() == rows)
                && mAppliedCellWidth == cellWidth() && mAppliedCellHeight == cellHeight()) {
            return;
        }
        mColumns = columns;
        mRows = rows;
        mAppliedCellWidth = cellWidth();
        mAppliedCellHeight = cellHeight();
        if (mSession != null) {
            mSession.resize(
                    columns,
                    rows,
                    cellWidth(),
                    cellHeight());
        }
    }

    private void beginSelection(final MotionEvent event) {
        final Point cell = cellAt(event);
        mSelectionStartColumn = cell.x;
        mSelectionStartRow = mTopRow + cell.y;
        mSelectionEndColumn = cell.x;
        mSelectionEndRow = mTopRow + cell.y;
        mSelecting = true;
        invalidate();
    }

    private void updateSelection(final Point cell) {
        mSelectionEndColumn = cell.x;
        mSelectionEndRow = mTopRow + cell.y;
        invalidate();
    }

    private Point cellAt(final MotionEvent event) {
        final int column = clamp(
                (int) ((event.getX() - mContentPadding)
                        / mRenderer.cellWidth()),
                0,
                mColumns - 1);
        final int row = clamp(
                (int) ((event.getY() - mContentPadding)
                        / mRenderer.cellHeight()),
                0,
                mRows - 1);
        return new Point(column, row);
    }

    private void scrollRows(final int delta) {
        mTopRow += delta;
        clampTopRow();
        clearSelection();
        awakenScrollBars();
        invalidate();
    }

    private void clampTopRow() {
        if (mSession == null) {
            mTopRow = 0;
            return;
        }
        final int oldest = -mSession.emulator().getScreen()
                .getActiveTranscriptRows();
        mTopRow = clamp(mTopRow, oldest, 0);
    }

    private void writeCodePoint(final int codePoint, final boolean alt) {
        final String text = mInput.text(codePoint);
        if (text.isEmpty()) {
            return;
        }
        if (alt) {
            mSession.write(new byte[]{0x1B});
        }
        mSession.write(text);
        scrollToBottom();
    }

    private void showSoftKeyboard() {
        final InputMethodManager manager = getContext().getSystemService(
                InputMethodManager.class);
        if (manager != null) {
            manager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }
    }


    private static int mouseButton(final MotionEvent event) {
        final int buttons = event.getButtonState();
        if ((buttons & MotionEvent.BUTTON_SECONDARY) != 0) {
            return 2;
        }
        if ((buttons & MotionEvent.BUTTON_TERTIARY) != 0) {
            return 1;
        }
        return TerminalEmulator.MOUSE_LEFT_BUTTON;
    }

    private static boolean isTouch(final MotionEvent event) {
        return ConsoleTerminalInput.isTouch(event.getToolType(0), event.getSource());
    }

    private static boolean isShiftPressed(final MotionEvent event) {
        return (event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0;
    }

    private static int clamp(final int value, final int minimum, final int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long position(final int row, final int column) {
        return ((long) row << 32) | (column & 0xFFFFFFFFL);
    }

    private final class TerminalInputConnection extends BaseInputConnection {
        private final Object mAttachment = mInputAttachment;
        private boolean mClosed;
        private String mComposingText = "";

        TerminalInputConnection() {
            super(ConsoleTerminalView.this, false);
        }

        private boolean isActive() {
            // Creating another connection does not close this one. Android owns its
            // lifetime; the attachment token also rejects callbacks after detach/rebind.
            return !mClosed && mAttachment == mInputAttachment && mSession != null;
        }

        @Override
        public void closeConnection() {
            mClosed = true;
            super.closeConnection();
        }

        @Override
        public boolean commitText(
                final CharSequence text, final int newCursorPosition) {
            if (!isActive()) { return false; }
            replaceComposingText(text);
            mComposingText = "";
            return true;
        }

        @Override
        public boolean setComposingText(
                final CharSequence text, final int newCursorPosition) {
            if (!isActive()) { return false; }
            replaceComposingText(text);
            mComposingText = text == null ? "" : text.toString();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            mComposingText = "";
            return isActive();
        }

        private void replaceComposingText(final CharSequence text) {
            final int previousCodePoints = mComposingText.codePointCount(
                    0, mComposingText.length());
            for (int index = 0; index < previousCodePoints; index++) {
                mSession.write(new byte[]{0x7F});
            }
            if (text != null) {
                mSession.write(text.toString());
                scrollToBottom();
            }
        }

        @Override
        public boolean deleteSurroundingText(
                final int beforeLength, final int afterLength) {
            if (!isActive()) { return false; }
            mComposingText = "";
            for (int index = 0; index < beforeLength; index++) {
                mSession.write(new byte[]{0x7F});
            }
            if (afterLength > 0) {
                final String delete = KeyHandler.getCode(
                        KeyEvent.KEYCODE_FORWARD_DEL,
                        0,
                        mSession.emulator().isCursorKeysApplicationMode(),
                        mSession.emulator().isKeypadApplicationMode());
                for (int index = 0; index < afterLength; index++) {
                    mSession.write(delete);
                }
            }
            return true;
        }

        @Override
        public boolean sendKeyEvent(final KeyEvent event) {
            return isActive() && dispatchKeyEvent(event);
        }

        @Override
        public boolean performEditorAction(final int actionCode) {
            if (!isActive()) { return false; }
            mSession.write("\r");
            return true;
        }

        @Override
        public boolean performPrivateCommand(
                final String action, final Bundle data) {
            return super.performPrivateCommand(action, data);
        }
    }
}
