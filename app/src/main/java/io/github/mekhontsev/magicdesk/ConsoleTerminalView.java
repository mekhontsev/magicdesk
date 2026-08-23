package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Point;
import android.os.Bundle;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.MagicDeskTerminalRenderer;
import com.termux.terminal.TerminalEmulator;

import java.nio.charset.StandardCharsets;

/** Interactive MagicDesk terminal surface with its own renderer. */
final class ConsoleTerminalView extends View {
    interface ClipboardActions {
        void copySelection();

        void pasteClipboard();
    }

    private static final int NO_SELECTION = Integer.MIN_VALUE;
    private static final int SCROLL_ROWS = 3;

    private final MagicDeskTerminalRenderer mRenderer;
    private final GestureDetector mGestures;
    private final int mContentPadding;
    private final int mTouchSlop;

    private ConsoleTerminalSession mSession;
    private ClipboardActions mClipboardActions;
    private int mColumns = 80;
    private int mRows = 24;
    private int mTopRow;
    private int mSelectionStartColumn = NO_SELECTION;
    private int mSelectionStartRow = NO_SELECTION;
    private int mSelectionEndColumn = NO_SELECTION;
    private int mSelectionEndRow = NO_SELECTION;
    private float mDownX;
    private float mDownY;
    private int mLastTouchRow;
    private boolean mSelecting;
    private boolean mTouchScrolling;
    private boolean mTerminalMousePress;
    private int mTerminalMouseButton;

    ConsoleTerminalView(final Context context) {
        super(context);
        mRenderer = new MagicDeskTerminalRenderer(
                getResources().getDisplayMetrics().scaledDensity);
        mContentPadding = Math.round(
                6.0f * getResources().getDisplayMetrics().density);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mGestures = new GestureDetector(
                context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(final MotionEvent event) {
                        return true;
                    }

                    @Override
                    public void onLongPress(final MotionEvent event) {
                        if (isTouch(event)) {
                            beginSelection(event);
                        }
                    }
                });
        setFocusable(true);
        setFocusableInTouchMode(true);
        setVerticalScrollBarEnabled(false);
    }

    void attach(
            final ConsoleTerminalSession session,
            final ClipboardActions clipboardActions) {
        mSession = session;
        mClipboardActions = clipboardActions;
        resizeTerminal();
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
        final TerminalEmulator emulator = mSession.emulator();
        final int modifiers = keyModifiers(event);
        final String keySequence = KeyHandler.getCode(
                keyCode,
                modifiers,
                emulator.isCursorKeysApplicationMode(),
                emulator.isKeypadApplicationMode());
        if (keySequence != null) {
            mSession.write(keySequence);
            scrollToBottom();
            return true;
        }
        final int controlCode = controlCode(keyCode, event);
        if (controlCode >= 0) {
            writeCodePoint(controlCode, event.isAltPressed());
            return true;
        }
        final int unicode = event.getUnicodeChar(event.getMetaState());
        if (unicode != 0) {
            writeCodePoint(unicode, event.isAltPressed());
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
        mGestures.onTouchEvent(event);
        final TerminalEmulator emulator = mSession.emulator();
        final Point cell = cellAt(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                requestFocus();
                mDownX = event.getX();
                mDownY = event.getY();
                mLastTouchRow = cell.y;
                mTouchScrolling = false;
                if (emulator.isMouseTrackingActive()
                        && !isShiftPressed(event)) {
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
                        && Math.abs(event.getY() - mDownY) > mTouchSlop) {
                    mTouchScrolling = true;
                    final int rowDelta = cell.y - mLastTouchRow;
                    if (rowDelta != 0) {
                        scrollRows(rowDelta);
                        mLastTouchRow = cell.y;
                    }
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
                    if (isTouch(event)) {
                        showSoftKeyboard();
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
        final TerminalEmulator emulator = mSession.emulator();
        if (emulator.isMouseTrackingActive() && !isShiftPressed(event)) {
            final Point cell = cellAt(event);
            emulator.sendMouseEvent(
                    amount > 0.0f
                            ? TerminalEmulator.MOUSE_WHEELUP_BUTTON
                            : TerminalEmulator.MOUSE_WHEELDOWN_BUTTON,
                    cell.x + 1,
                    cell.y + 1,
                    true);
        } else {
            scrollRows(amount > 0.0f ? -SCROLL_ROWS : SCROLL_ROWS);
        }
        return true;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(
            final EditorInfo editorInfo) {
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
        final int columns = Math.max(2,
                (int) (availableWidth / mRenderer.cellWidth()));
        final int rows = Math.max(2,
                (int) (availableHeight / mRenderer.cellHeight()));
        if (columns == mColumns && rows == mRows) {
            return;
        }
        mColumns = columns;
        mRows = rows;
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
        if (alt) {
            mSession.write(new byte[]{0x1B});
        }
        mSession.write(new String(Character.toChars(codePoint))
                .getBytes(StandardCharsets.UTF_8));
        scrollToBottom();
    }

    private void showSoftKeyboard() {
        final InputMethodManager manager = getContext().getSystemService(
                InputMethodManager.class);
        if (manager != null) {
            manager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private static int keyModifiers(final KeyEvent event) {
        int modifiers = 0;
        if (event.isAltPressed()) {
            modifiers |= KeyHandler.KEYMOD_ALT;
        }
        if (event.isCtrlPressed()) {
            modifiers |= KeyHandler.KEYMOD_CTRL;
        }
        if (event.isShiftPressed()) {
            modifiers |= KeyHandler.KEYMOD_SHIFT;
        }
        if (event.isNumLockOn()) {
            modifiers |= KeyHandler.KEYMOD_NUM_LOCK;
        }
        return modifiers;
    }

    private static int controlCode(
            final int keyCode, final KeyEvent event) {
        if (!event.isCtrlPressed()) {
            return -1;
        }
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return keyCode - KeyEvent.KEYCODE_A + 1;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_2:
                return 0;
            case KeyEvent.KEYCODE_LEFT_BRACKET:
                return 27;
            case KeyEvent.KEYCODE_BACKSLASH:
                return 28;
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
                return 29;
            case KeyEvent.KEYCODE_6:
                return 30;
            case KeyEvent.KEYCODE_MINUS:
                return 31;
            default:
                return -1;
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
        return event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                || (event.getSource() & InputDevice.SOURCE_TOUCHSCREEN) != 0;
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
        private String mComposingText = "";

        TerminalInputConnection() {
            super(ConsoleTerminalView.this, false);
        }

        @Override
        public boolean commitText(
                final CharSequence text, final int newCursorPosition) {
            replaceComposingText(text);
            mComposingText = "";
            return true;
        }

        @Override
        public boolean setComposingText(
                final CharSequence text, final int newCursorPosition) {
            replaceComposingText(text);
            mComposingText = text == null ? "" : text.toString();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            mComposingText = "";
            return true;
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
            return dispatchKeyEvent(event);
        }

        @Override
        public boolean performEditorAction(final int actionCode) {
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
