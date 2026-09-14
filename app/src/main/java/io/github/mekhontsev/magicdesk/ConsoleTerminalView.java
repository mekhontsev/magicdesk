package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Point;
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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.MagicDeskTerminalRenderer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalHyperlink;
import com.termux.terminal.TerminalImage;
import com.termux.terminal.TerminalFrame;
import com.termux.terminal.TerminalScrollAnimation;

/** Interactive MagicDesk terminal surface with its own renderer. */
final class ConsoleTerminalView extends View {
    interface Actions {
        void copySelection();

        void pasteClipboard();

        void showLink(com.termux.terminal.TerminalHyperlink link);

        void showImage(TerminalImage image);
    }

    private static final int SCROLL_ROWS = 3;

    private MagicDeskTerminalRenderer mRenderer;
    private final TerminalScrollAnimation mRegionScroll = new TerminalScrollAnimation();
    private static final String SCROLL_TRACE = "MDTerminalScroll";
    private int mTracedScrollEdits;
    private long mTracedScrollStart;
    private final TerminalEmulator.ScrollListener mScrollListener = new TerminalEmulator.ScrollListener() {
        @Override public void onScroll(int left, int top, int right, int bottom, int rows) {
            if (BuildConfig.DEBUG && android.util.Log.isLoggable(SCROLL_TRACE, android.util.Log.DEBUG)) {
                if (mTracedScrollEdits++ == 0) mTracedScrollStart = android.os.SystemClock.uptimeMillis();
                android.util.Log.d(SCROLL_TRACE, "edit rows=" + rows + " region=" + top + ":" + bottom
                        + " view=" + mViewport.columns() + "x" + mViewport.rows() + " anchor=" + mViewport.topRow() + ":" + mViewport.rowOffset()
                        + " selection=" + hasSelection() + " shown=" + isShown()
                        + " frameMillis=" + (getDisplay() == null ? 0 : scrollFrameMillis()));
            }
            if (mSession == null || mViewport.topRow() != 0 || mViewport.rowOffset() != 0 || hasSelection() || !isShown()) {
                mRegionScroll.reset();
                return;
            }
            mRegionScroll.beforeScroll(mSession.emulator(), mRenderer,
                    left, top, right, bottom, rows, scrollFrameMillis(), android.os.SystemClock.uptimeMillis());
        }

        @Override public void onCellsChanged(int left, int top, int right, int bottom) {
            if (mSession != null) mRegionScroll.cellsChanged(mSession.emulator(), left, top, right, bottom);
        }

        @Override public void onScrollComplete() {
            if (mSession != null) mRegionScroll.scrollComplete(mSession.emulator());
        }

        @Override public void onScreenReset() {
            mRegionScroll.reset();
            if (BuildConfig.DEBUG && android.util.Log.isLoggable(SCROLL_TRACE, android.util.Log.DEBUG))
                android.util.Log.d(SCROLL_TRACE, "emulator reset");
        }
    };
    private final GestureDetector mGestures;
    private final ScaleGestureDetector mScaleGestures;
    private final OverScroller mScroller;
    private MotionEvent mFlingEvent;
    private int mLastFlingY;
    private boolean mFlingMouseTracking;
    private boolean mFlingAlternateBuffer;
    private ConsoleSelectionHandles mSelectionHandles;
    private boolean mTouchSelection;
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
    private Actions mActions;
    private final TerminalViewport mViewport = new TerminalViewport();
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
        mScroller = new OverScroller(context);
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

                    @Override
                    public boolean onFling(final MotionEvent first, final MotionEvent last,
                            final float velocityX, final float velocityY) {
                        return startFling(last, velocityY);
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
        mSelectionHandles = new ConsoleSelectionHandles(this, this::moveSelectionHandle);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setVerticalScrollBarEnabled(true);
    }

    void attach(
            final ConsoleTerminalSession session,
            final Actions actions) {
        stopFling();
        mRegionScroll.reset();
        if (mSession != null) mSession.emulator().removeScrollListener(mScrollListener);
        if (mSession != session) {
            clearSelection();
            mViewport.live();
            mInputAttachment = session == null ? null : new Object();
        }
        mSession = session;
        if (mSession != null && isAttachedToWindow()) mSession.emulator().setScrollListener(mScrollListener);
        mActions = actions;
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
        stopFling();
        mRegionScroll.reset();
        // Android applies the current display density and nonlinear accessibility font scale.
        mRenderer = new MagicDeskTerminalRenderer(getResources().getFont(R.font.console_mono), TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, mFontSizeSp, getResources().getDisplayMetrics()));
        mContentPadding = Math.round(6.0f * getResources().getDisplayMetrics().density);
        mViewport.metrics(mRenderer.cellWidth(), mRenderer.cellHeight(), mContentPadding);
        mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        resizeTerminal();
        clampTopRow();
        invalidate();
    }

    int columns() {
        return mViewport.columns();
    }

    int rows() {
        return mViewport.rows();
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
            final boolean selected = hasSelection();
            mViewport.outputChanged(emulator.getScrollCounter(), emulator.getScreen().getActiveTranscriptRows());
            if (selected && !hasSelection()) clearSelection();
            emulator.clearScrollCounter();
        }
        clampTopRow();
        updateSelectionHandles();
        invalidate();
    }

    boolean hasSelection() {
        return mViewport.hasSelection();
    }

    String selectedText() {
        if (!hasSelection() || mSession == null) return "";
        final TerminalViewport.Selection selection = mViewport.selection();
        return mSession.emulator().getSelectedText(selection.startColumn(), selection.startRow(),
                selection.endColumn(), selection.endRow());
    }

    void clearSelection() {
        mTouchSelection = false;
        if (mSelectionHandles != null) mSelectionHandles.hide();
        mViewport.clearSelection();
        mSelecting = false;
        invalidate();
    }

    void scrollToBottom() {
        stopFling();
        mRegionScroll.reset();
        mViewport.live();
        updateSelectionHandles();
        invalidate();
    }

    void jumpTo(final com.termux.terminal.TerminalMarker.Position position) {
        if (position == null || mSession == null || mSession.emulator().isAlternateBufferActive()) { return; }
        stopFling();
        mRegionScroll.reset();
        clearSelection();
        mViewport.jumpTo(position.row(), mSession.emulator().getScreen().getActiveTranscriptRows());
        invalidate();
    }

    void selectRange(final com.termux.terminal.TerminalCommandHistory.Range range) {
        if (range == null || mSession == null || mSession.emulator().isAlternateBufferActive()) { return; }
        jumpTo(range.start());
        if (range.start().equals(range.end())) { return; }
        final boolean previousRow = range.end().column() == 0;
        mViewport.select(range.start().column(), range.start().row(),
                previousRow ? mViewport.columns() - 1 : range.end().column() - 1,
                range.end().row() - (previousRow ? 1 : 0));
        mTouchSelection = true;
        updateSelectionHandles();
        invalidate();
    }

    private com.termux.terminal.TerminalHyperlink linkAt(final MotionEvent event) {
        if (mSession == null || event.getX() < mContentPadding || event.getY() < mContentPadding
                || event.getX() >= getWidth() - mContentPadding || event.getY() >= getHeight() - mContentPadding) {
            return null;
        }
        final Point cell = transcriptCellAt(event);
        return mSession.emulator().getScreen().getHyperlink(cell.x, cell.y);
    }

    private TerminalImage imageAt(final MotionEvent event) {
        if (mSession == null) return null;
        final float column = mViewport.columnPosition(event.getX());
        if (column < 0 || column >= mViewport.columns() || event.getY() < mContentPadding
                || event.getY() >= mContentPadding + mViewport.rows() * mRenderer.cellHeight()) return null;
        final TerminalEmulator emulator = mSession.emulator();
        return emulator.getGraphics().imageAt(emulator.getScreen(), column, mViewport.topRow() + viewportRowAt(event.getY()),
                mRenderer.cellWidth(), mRenderer.cellHeight());
    }

    private boolean showImageAt(final MotionEvent event) {
        final TerminalImage image = imageAt(event);
        if (image == null || mActions == null) return false;
        clearSelection();
        mImageGesture = true;
        mActions.showImage(image);
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
                mViewport.topRow(),
                Math.max(0, mViewport.columns() - 1),
                mViewport.topRow() + visibleRowCount() - 1);
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
        if (BuildConfig.DEBUG && android.util.Log.isLoggable(SCROLL_TRACE, android.util.Log.DEBUG))
            android.util.Log.d(SCROLL_TRACE, "resize " + oldWidth + "x" + oldHeight + " -> " + width + "x" + height);
        stopFling();
        mRegionScroll.reset();
        resizeTerminal();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mSession != null) mSession.emulator().setScrollListener(mScrollListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopFling();
        mRegionScroll.reset();
        if (mSession != null) mSession.emulator().removeScrollListener(mScrollListener);
        if (mSelectionHandles != null) mSelectionHandles.hide();
        super.onDetachedFromWindow();
    }

    @Override
    public void onWindowFocusChanged(final boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) stopFling();
        if (!focused) mRegionScroll.reset();
        updateSelectionHandles();
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
        if (mViewport.topRow() != 0 || mViewport.rowOffset() != 0 || hasSelection()) mRegionScroll.reset();
        final long now = android.os.SystemClock.uptimeMillis();
        final boolean animating = mRegionScroll.advance(now);
        if (mTracedScrollEdits > 0) {
            android.util.Log.d(SCROLL_TRACE, "frame animated=" + animating + " edits=" + mTracedScrollEdits
                    + " elapsed=" + (now - mTracedScrollStart) + " anchor=" + mViewport.topRow() + ":" + mViewport.rowOffset());
            mTracedScrollEdits = 0;
        }
        if (animating) {
            mRegionScroll.draw(canvas, mSession.emulator(), mRenderer, hasFocus());
            postInvalidateOnAnimation();
        } else mRenderer.draw(
                canvas,
                TerminalFrame.capture(mSession.emulator(), mViewport.topRow(), visibleRowCount()),
                mViewport.topRow(),
                mViewport.rows(),
                mViewport.rowOffset(),
                mViewport.startColumn(),
                mViewport.startRow(),
                mViewport.endColumn(),
                mViewport.endRow(),
                hasFocus());
        canvas.restore();
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        stopFling();
        mRegionScroll.reset();
        if (mSession == null || keyCode == KeyEvent.KEYCODE_BACK) {
            return super.onKeyDown(keyCode, event);
        }
        if (event.isShiftPressed() && !event.isCtrlPressed() && !event.isAltPressed()
                && (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN)) {
            scrollRows(keyCode == KeyEvent.KEYCODE_PAGE_UP ? -mViewport.rows() : mViewport.rows());
            return true;
        }
        if (event.isCtrlPressed() && event.isShiftPressed()) {
            if (keyCode == KeyEvent.KEYCODE_C && mActions != null) {
                mActions.copySelection();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_V && mActions != null) {
                mActions.pasteClipboard();
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
        final boolean interruptedScroll = event.getActionMasked() == MotionEvent.ACTION_DOWN
                && (mFlingEvent != null || mRegionScroll.advance(android.os.SystemClock.uptimeMillis()));
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) stopFling();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            // Hit testing and application clicks use the terminal's committed cell positions.
            mRegionScroll.reset();
            invalidate();
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
                mTouchScrolling = interruptedScroll;
                // A finger is a scroll gesture until a tap completes, not a held mouse button.
                if (!interruptedScroll && !isTouch(event) && emulator.isMouseTrackingActive()
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
                    updateSelection(transcriptCellAt(event));
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
                if (mFlingEvent == null) mRegionScroll.endInput(android.os.SystemClock.uptimeMillis());
                if (mTerminalMousePress) {
                    emulator.sendMouseEvent(
                            mTerminalMouseButton,
                            cell.x + 1,
                            cell.y + 1,
                            false);
                    mTerminalMousePress = false;
                } else if (mSelecting) {
                    updateSelection(transcriptCellAt(event));
                    mSelecting = false;
                    updateSelectionHandles();
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        && !mTouchScrolling) {
                    clearSelection();
                    if (!isShiftPressed(event) && (!emulator.isMouseTrackingActive()
                            || (event.getMetaState() & KeyEvent.META_CTRL_ON) != 0)
                            && showImageAt(event)) return true;
                    final TerminalHyperlink link = linkAt(event);
                    if (link != null && mActions != null && !isShiftPressed(event)
                            && (!emulator.isMouseTrackingActive() || (event.getMetaState() & KeyEvent.META_CTRL_ON) != 0)) {
                        mActions.showLink(link);
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
        stopFling();
        if ((event.getMetaState() & KeyEvent.META_CTRL_ON) != 0) {
            mFontWheelRemainder += amount;
            final int steps = (int) mFontWheelRemainder;
            mFontWheelRemainder -= steps;
            setFontSizeSp(mFontSizeSp + steps);
            return true;
        }
        if (scrollsLocalHistory(event)) {
            mWheelScrollRemainder = 0;
            scrollHistoryPixels(-amount * SCROLL_ROWS * mRenderer.cellHeight());
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
            stopFling();
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
        final float distance = mLastTouchY - event.getY();
        mLastTouchY = event.getY();
        scrollPixels(distance, event);
    }

    private void scrollPixels(final float distance, final MotionEvent event) {
        if (scrollsLocalHistory(event)) {
            mTouchScrollRemainder = 0;
            scrollHistoryPixels(distance);
            return;
        }
        mTouchScrollRemainder += distance / mRenderer.cellHeight();
        final int rows = (int) mTouchScrollRemainder;
        mTouchScrollRemainder -= rows;
        scrollTerminal(rows, event);
    }

    private boolean scrollsLocalHistory(final MotionEvent event) {
        final TerminalEmulator emulator = mSession.emulator();
        return isShiftPressed(event) || (!emulator.isMouseTrackingActive() && !emulator.isAlternateBufferActive());
    }

    private boolean startFling(final MotionEvent event, final float velocityY) {
        if (mSession == null || !isTouch(event) || !mTouchScrolling
                || mSelecting || mImageGesture || mFontScaleGesture
                || Math.abs(velocityY) < ViewConfiguration.get(getContext()).getScaledMinimumFlingVelocity()) {
            return false;
        }
        stopFling();
        mFlingEvent = MotionEvent.obtain(event);
        mFlingMouseTracking = mSession.emulator().isMouseTrackingActive();
        mFlingAlternateBuffer = mSession.emulator().isAlternateBufferActive();
        mLastFlingY = 0;
        mScroller.fling(0, 0, 0, Math.round(-velocityY),
                0, 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
        postInvalidateOnAnimation();
        return true;
    }

    @Override
    public void computeScroll() {
        if (mFlingEvent == null) return;
        if (mSession == null || !mScroller.computeScrollOffset()
                || mSession.emulator().isMouseTrackingActive() != mFlingMouseTracking
                || mSession.emulator().isAlternateBufferActive() != mFlingAlternateBuffer) {
            stopFling();
            return;
        }
        final int y = mScroller.getCurrY();
        final int distance = y - mLastFlingY;
        mLastFlingY = y;
        scrollPixels(distance, mFlingEvent);
        // TUI scroll limits belong to the application; local history has known edges.
        if (isShiftPressed(mFlingEvent) || (!mFlingMouseTracking && !mFlingAlternateBuffer)) {
            final int oldest = -mSession.emulator().getScreen().getActiveTranscriptRows();
            if ((distance < 0 && mViewport.topRow() == oldest && mViewport.rowOffset() == 0) || (distance > 0 && mViewport.topRow() == 0)) {
                stopFling();
                return;
            }
        }
        postInvalidateOnAnimation();
    }

    private void stopFling() {
        mScroller.forceFinished(true);
        if (mFlingEvent != null) {
            mRegionScroll.endInput(android.os.SystemClock.uptimeMillis());
            mFlingEvent.recycle();
            mFlingEvent = null;
        }
    }

    private void scrollTerminal(final int rows, final MotionEvent event) {
        if (rows == 0) { return; }
        final TerminalEmulator emulator = mSession.emulator();
        if (!scrollsLocalHistory(event) && getDisplay() != null) {
            mRegionScroll.input(rows, isTouch(event) && (mTouchScrolling || mFlingEvent != null),
                    scrollFrameMillis(), android.os.SystemClock.uptimeMillis());
        }
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

    private double scrollFrameMillis() { return 1000.0 / getDisplay().getRefreshRate(); }

    @Override
    protected int computeVerticalScrollRange() {
        return Math.round((mViewport.rows() + (mSession == null ? 0 : mSession.emulator().getScreen().getActiveTranscriptRows()))
                * mRenderer.cellHeight());
    }

    @Override
    protected int computeVerticalScrollExtent() {
        return Math.round(mViewport.rows() * mRenderer.cellHeight());
    }

    @Override
    protected int computeVerticalScrollOffset() {
        return computeVerticalScrollRange() - computeVerticalScrollExtent()
                + Math.round(mViewport.topRow() * mRenderer.cellHeight() + mViewport.rowOffset());
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return mSession != null;
    }

    @Override
    public InputConnection onCreateInputConnection(final EditorInfo editorInfo) {
        if (mSession == null) return null;
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        editorInfo.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        final Object attachment = mInputAttachment;
        return new ConsoleTerminalInputConnection(this, mSession,
                () -> mSession != null && attachment == mInputAttachment, this::scrollToBottom);
    }

    private void resizeTerminal() {
        // Attaching an unmeasured View must not reflow a retained session to 2x2.
        if (!mViewport.isMeasured(getWidth(), getHeight())) return;
        final boolean changed = mViewport.resize(getWidth(), getHeight());
        final int columns = mViewport.columns(), rows = mViewport.rows();
        if (!changed && (mSession == null
                || mSession.columns() == columns && mSession.rows() == rows)
                && mAppliedCellWidth == cellWidth() && mAppliedCellHeight == cellHeight()) {
            return;
        }
        clearSelection();
        mAppliedCellWidth = cellWidth();
        mAppliedCellHeight = cellHeight();
        if (mSession != null) {
            mSession.resize(
                    columns,
                    rows,
                    cellWidth(),
                    cellHeight());
        }
        clampTopRow();
    }

    private void beginSelection(final MotionEvent event) {
        stopFling();
        mRegionScroll.reset();
        final Point cell = transcriptCellAt(event);
        mViewport.select(cell.x, cell.y, cell.x, cell.y);
        mSelecting = true;
        mTouchSelection = isTouch(event);
        updateSelectionHandles();
        invalidate();
    }

    private void updateSelection(final Point cell) {
        mViewport.extendSelection(cell.x, cell.y);
        updateSelectionHandles();
        invalidate();
    }

    private void updateSelectionHandles() {
        if (mSelectionHandles == null) return;
        if (!hasSelection() || !mTouchSelection || mSelecting || !hasWindowFocus()) {
            mSelectionHandles.hide();
            return;
        }
        mViewport.normalizeSelection();
        mSelectionHandles.update(
                mViewport.columnX(mViewport.startColumn()),
                rowBottomY(mViewport.startRow()),
                mViewport.columnX(mViewport.endColumn() + 1),
                rowBottomY(mViewport.endRow()));
    }

    private void moveSelectionHandle(final boolean start, final float x, final float y) {
        if (!hasSelection() || mSession == null) return;
        stopFling();
        mViewport.moveHandle(start, x, y);
        updateSelectionHandles();
        invalidate();
    }

    private Point cellAt(final MotionEvent event) {
        return new Point(mViewport.columnAt(event.getX()), mViewport.screenRowAt(event.getY()));
    }

    private float viewportRowAt(final float y) { return mViewport.viewportRowAt(y); }

    private float rowBottomY(final int row) { return mViewport.rowBottomY(row); }

    private int visibleRowCount() { return mViewport.visibleRows(); }

    private Point transcriptCellAt(final MotionEvent event) {
        return new Point(mViewport.columnAt(event.getX()), mViewport.transcriptRowAt(event.getY()));
    }

    private void scrollRows(final int delta) {
        scrollHistoryPixels(delta * mRenderer.cellHeight());
    }

    private void scrollHistoryPixels(final float delta) {
        mRegionScroll.reset();
        mViewport.scroll(delta, mSession == null ? 0 : mSession.emulator().getScreen().getActiveTranscriptRows());
        clearSelection();
        awakenScrollBars();
        invalidate();
    }

    private void clampTopRow() {
        mViewport.clamp(mSession == null ? 0 : mSession.emulator().getScreen().getActiveTranscriptRows());
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

}
