package io.github.mekhontsev.magicdesk.guestapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import com.termux.terminal.MagicDeskTerminalRenderer;
import com.termux.terminal.TerminalFrame;
import io.github.mekhontsev.magicdesk.ConsoleTerminalInput;
import io.github.mekhontsev.magicdesk.ConsoleTerminalInputConnection;

final class EnvironmentTerminalView extends View {
    private final MagicDeskTerminalRenderer renderer;
    private final ConsoleTerminalInput keys=new ConsoleTerminalInput(KeyCharacterMap::getDeadChar);
    private final GestureDetector gestures;
    private EnvironmentService session;
    private int top;

    EnvironmentTerminalView(Context context) {
        super(context);
        setFocusable(true); setFocusableInTouchMode(true);
        setBackgroundColor(0xff000000);
        renderer=new MagicDeskTerminalRenderer(Typeface.MONOSPACE,14*getResources().getDisplayMetrics().scaledDensity);
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent event) {
                requestFocus(); context.getSystemService(InputMethodManager.class).showSoftInput(EnvironmentTerminalView.this,InputMethodManager.SHOW_IMPLICIT);
                return true;
            }
            @Override public boolean onScroll(MotionEvent one,MotionEvent two,float x,float y) {
                if (session!=null) { top=Math.max(-session.emulator().getScreen().getActiveTranscriptRows(),Math.min(0,top+Math.round(y/renderer.cellHeight()))); invalidate(); }
                return true;
            }
            @Override public void onLongPress(MotionEvent event) {
                if (session==null) return;
                new android.app.AlertDialog.Builder(context).setItems(new String[]{"Copy transcript","Paste"},(dialog,which) -> {
                    if (session==null) return;
                    var clipboard=context.getSystemService(android.content.ClipboardManager.class);
                    if (which==0) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Terminal",session.emulator().getScreen().getTranscriptText()));
                    else if (clipboard.hasPrimaryClip()) {
                        var clip=clipboard.getPrimaryClip();
                        if (clip!=null && clip.getItemCount()>0) session.emulator().paste(clip.getItemAt(0).coerceToText(context).toString());
                    }
                }).show();
            }
        });
    }
    void attach(EnvironmentService value) {
        session=value; top=0; keys.reset(); resize(); invalidate();
        getContext().getSystemService(InputMethodManager.class).restartInput(this);
    }
    private void resize() {
        if (session!=null && getWidth()>0 && getHeight()>0)
            session.resize(Math.max(2,(int)(getWidth()/renderer.cellWidth())),Math.max(2,(int)(getHeight()/renderer.cellHeight())),
                    (int)renderer.cellWidth(),(int)renderer.cellHeight());
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) { resize(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (session==null) return;
        top=Math.max(top,-session.emulator().getScreen().getActiveTranscriptRows());
        renderer.draw(canvas,TerminalFrame.capture(session.emulator(),top,session.emulator().mRows),top,session.emulator().mRows,
                0,Integer.MIN_VALUE,Integer.MIN_VALUE,0,0,hasFocus());
    }
    @Override public boolean onTouchEvent(MotionEvent event) { return gestures.onTouchEvent(event); }
    @Override public boolean onKeyDown(int code,KeyEvent event) {
        if (session==null || code==KeyEvent.KEYCODE_BACK) return super.onKeyDown(code,event);
        String text=keys.key(event,session.emulator());
        if (text==null) return super.onKeyDown(code,event);
        session.write(text); top=0; invalidate(); return true;
    }
    @Override public boolean onCheckIsTextEditor() { return session!=null; }
    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        EnvironmentService captured=session;
        if (captured==null) return null;
        info.inputType=android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        info.imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_ACTION_NONE;
        return new ConsoleTerminalInputConnection(this,captured,() -> session==captured,() -> { top=0; invalidate(); });
    }
}
