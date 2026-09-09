package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Exercises the real View/IME methods without requiring a running Android IME. */
public final class TerminalInputConnectionOwnershipTest {
    @Test public void anotherConnectionDoesNotRevokeTheConnectionStillUsedByTheIme() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    ConsoleTerminalView view = new ConsoleTerminalView();
                    ConsoleTerminalSession session = new ConsoleTerminalSession();
                    view.attach(session, null);
                    var ime = view.onCreateInputConnection(new EditorInfo());
                    var other = view.onCreateInputConnection(new EditorInfo());
                    for (int i=0; i<12; i++) {
                        check(ime.commitText("q", 1), "live IME commit rejected after another connection was created");
                    }
                    check(ime.sendKeyEvent(new KeyEvent()), "live IME key rejected");
                    other.closeConnection();
                    check(ime.commitText("a", 1), "closing another connection revoked the live IME");
                    check(!other.commitText("closed", 1), "closed connection accepted input");
                    check(session.output.toString().equals("qqqqqqqqqqqqa"), "IME text was lost");
                    check(view.dispatchedKeys == 1, "IME key was lost or duplicated");
                }
                """);
    }

    @Test public void detachedViewRejectsEveryLateImeWrite() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    ConsoleTerminalView view = new ConsoleTerminalView();
                    ConsoleTerminalSession session = new ConsoleTerminalSession();
                    view.attach(session, null);
                    var ime = view.onCreateInputConnection(new EditorInfo());
                    check(ime.setComposingText("abc", 1), "active composing input failed");
                    view.attach(null, null);
                    check(!view.onCheckIsTextEditor(), "detached view remains an editor");
                    check(view.onCreateInputConnection(new EditorInfo()) == null, "detached view created IME connection");
                    check(!ime.commitText("late", 1), "late commit accepted");
                    check(!ime.setComposingText("late", 1), "late composition accepted");
                    check(!ime.finishComposingText(), "late composition completion accepted");
                    check(!ime.deleteSurroundingText(2, 2), "late deletion accepted");
                    check(!ime.performEditorAction(0), "late editor action accepted");
                    check(!ime.sendKeyEvent(new KeyEvent()), "late key accepted");
                    check(session.output.toString().equals("abc"), "detached IME wrote to session");
                    check(view.dispatchedKeys == 0, "detached IME dispatched key to Activity");
                }
                """);
    }

    @Test public void closingAndReattachmentNeverReviveAnOldConnection() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    ConsoleTerminalView view = new ConsoleTerminalView();
                    ConsoleTerminalSession session = new ConsoleTerminalSession();
                    view.attach(session, null);
                    var first = view.onCreateInputConnection(new EditorInfo());
                    var second = view.onCreateInputConnection(new EditorInfo());
                    first.closeConnection();
                    check(!first.commitText("stale", 1), "closed connection accepted input");
                    check(second.commitText("a", 1), "old close invalidated new connection");
                    view.attach(null, null);
                    view.attach(session, null);
                    var third = view.onCreateInputConnection(new EditorInfo());
                    check(!second.commitText("stale", 1), "reattachment revived old connection");
                    check(third.setComposingText("x", 1), "new composition failed");
                    check(third.commitText("y", 1), "new commit failed");
                    check(session.output.toString().equals("ax" + (char)127 + "y"), "composition semantics changed");
                    third.closeConnection();
                    check(!third.performEditorAction(0), "closed connection wrote to PTY");
                }
                """);
    }

    private static String fixture() throws Exception {
        return """
                interface ClipboardActions {}
                static class KeyEvent { static final int KEYCODE_FORWARD_DEL=112; }
                static class KeyHandler {
                    static String getCode(int key,int meta,boolean cursor,boolean keypad) { return "delete"; }
                }
                static class InputType {
                    static final int TYPE_CLASS_TEXT=1, TYPE_TEXT_FLAG_MULTI_LINE=2, TYPE_TEXT_FLAG_NO_SUGGESTIONS=4;
                }
                static class EditorInfo {
                    static final int IME_FLAG_NO_EXTRACT_UI=1, IME_ACTION_NONE=0;
                    int inputType, imeOptions;
                }
                interface InputConnection {
                    boolean commitText(CharSequence text,int cursor);
                    boolean setComposingText(CharSequence text,int cursor);
                    boolean finishComposingText();
                    boolean deleteSurroundingText(int before,int after);
                    boolean performEditorAction(int code);
                    boolean sendKeyEvent(KeyEvent event);
                    void closeConnection();
                }
                static class BaseInputConnection { public void closeConnection() {} }
                static class Emulator {
                    boolean isCursorKeysApplicationMode() { return false; }
                    boolean isKeypadApplicationMode() { return false; }
                }
                static class ConsoleTerminalSession {
                    StringBuilder output=new StringBuilder();
                    void write(String s) { output.append(s); }
                    void write(byte[] s) { for(byte b:s) output.append((char)b); }
                    Emulator emulator() { return new Emulator(); }
                }
                static class ConsoleTerminalView {
                    ConsoleTerminalSession mSession;
                    ClipboardActions mClipboardActions;
                    Object mInputAttachment;
                    int dispatchedKeys;
                    void resizeTerminal() {} void invalidate() {} void scrollToBottom() {}
                    boolean dispatchKeyEvent(KeyEvent event) { dispatchedKeys++; return true; }
                """ + RuntimeSourceFixture.methods("ConsoleTerminalView",
                        "attach", "onCheckIsTextEditor", "onCreateInputConnection") + """
                    private final class TerminalInputConnection extends BaseInputConnection implements InputConnection {
                        final Object mAttachment=mInputAttachment;
                        boolean mClosed;
                        String mComposingText="";
                """ + RuntimeSourceFixture.methods("ConsoleTerminalView", "isActive", "closeConnection",
                        "commitText", "setComposingText", "finishComposingText", "replaceComposingText",
                        "deleteSurroundingText", "sendKeyEvent", "performEditorAction") + "}}";
    }
}
