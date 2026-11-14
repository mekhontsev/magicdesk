package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class ConsoleContentDeliveryTest {
    @Test public void lineBreaksRequireConsentOnlyWithoutBracketedPaste() {
        for (String text : new String[]{"one\ntwo", "one\rtwo", "one\r\ntwo", "\n", "x\n"}) {
            assertTrue(ConsoleContentDelivery.needsConfirmation(text, false));
            assertFalse(ConsoleContentDelivery.needsConfirmation(text, true));
        }
        for (String text : new String[]{"", "echo test", "a\tb", "file:///tmp/a"})
            assertFalse(ConsoleContentDelivery.needsConfirmation(text, false));
    }

    @Test public void textAndQuotedPathsAreCombinedWithoutAddingEnter() {
        assertEquals("hello", ConsoleContentDelivery.combine("hello", ""));
        assertEquals("hello\n", ConsoleContentDelivery.combine("hello\n", ""));
        assertEquals("cat 'file' ", ConsoleContentDelivery.combine("cat", "'file'"));
        assertEquals("cat  'file' ", ConsoleContentDelivery.combine("cat  ", "'file'"));
        assertEquals("'file' ", ConsoleContentDelivery.combine("", "'file'"));
    }

    @Test public void latePasteCannotUseAClosedOrReboundSessionOrChangedMode() throws Exception {
        RuntimeSourceFixture.verify("""
                interface BooleanSupplier extends java.util.function.BooleanSupplier {}
                static class ConsoleTerminalSession {
                    boolean ready=true, bracketed; String inserted="";
                    boolean isReady(){return ready;}
                    ConsoleTerminalSession emulator(){return this;}
                    boolean isBracketedPasteEnabled(){return bracketed;}
                    void paste(String text){inserted+=text;}
                }
                public static void verify() throws Exception {
                    var session=new ConsoleTerminalSession();
                    paste(session,()->true,"exact",false);
                    check(session.inserted.equals("exact"),"added Enter or changed text");
                    expectFailure(()->paste(session,()->false,"wrong",true));
                    session.ready=false;
                    expectFailure(()->paste(session,()->true,"closed",true));
                    session.ready=true;
                    expectFailure(()->paste(session,()->true,"one\\ntwo",false));
                    check(session.inserted.equals("exact"),"failed request wrote input");
                    session.bracketed=true;
                    paste(session,()->true,"one\\ntwo",false);
                    session.bracketed=false;
                    paste(session,()->true,"three\\nfour",true);
                    check(session.inserted.equals("exactone\\ntwothree\\nfour"),"lost confirmed text");
                }
                interface Action {void run() throws Exception;}
                static void expectFailure(Action action) throws Exception {
                    try {action.run();throw new AssertionError("accepted invalid paste");}
                    catch(java.io.IOException expected){}
                }
                """ + RuntimeSourceFixture.methods("ConsoleContentDelivery", "needsConfirmation", "paste"));
    }

    @Test public void sharedTextAndFilesUseTheSameSessionImportAndKeepFailureAtomicForInput() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Uri { }
                static class ConsoleSessionFiles {
                    record Item(String path){}
                    String name,text; List<Uri> uris; int calls;
                    java.util.concurrent.CompletableFuture<List<Item>> imported=new java.util.concurrent.CompletableFuture<>();
                    java.util.concurrent.CompletableFuture<List<Item>> importContent(List<Uri> u,String n,String t){
                        calls++;uris=u;name=n;text=t;return imported;
                    }
                    String shellPaths(List<String> paths){return String.join(" ",paths);}
                }
                static class ConsoleTerminalSession {
                    ConsoleSessionFiles files=new ConsoleSessionFiles();
                    ConsoleSessionFiles files(){return files;}
                }
                public static void verify() {
                    var session=new ConsoleTerminalSession();
                    check(prepare(session,List.of(),"unchanged\\n","").join().equals("unchanged\\n"),"modified text");
                    check(session.files.calls==0,"text unnecessarily required file channel");
                    var uri=new Uri();
                    var result=prepare(session,List.of(uri),"caption","note.txt");
                    check(!result.isDone(),"published input before copying");
                    check(session.files.name.equals("note.txt") && session.files.text.equals("caption"),"lost text");
                    check(session.files.uris.equals(List.of(uri)),"lost mixed shared files");
                    session.files.imported.complete(List.of(new ConsoleSessionFiles.Item("'file'"),
                            new ConsoleSessionFiles.Item("'note'")));
                    check(result.join().equals("'file' 'note' "),"inserted text instead of file");
                    var broken=new ConsoleTerminalSession();
                    var failed=prepare(broken,List.of(uri),"caption","");
                    broken.files.imported.completeExceptionally(new java.io.IOException("revoked"));
                    check(failed.isCompletedExceptionally(),"file failure became successful input");
                    broken.files=null;
                    check(prepare(broken,List.of(),"still works","").join().equals("still works"),"text coupled to files");
                    check(prepare(broken,List.of(uri),"","").isCompletedExceptionally(),"missing file channel accepted");
                }
                """ + RuntimeSourceFixture.methods("ConsoleContentDelivery", "prepare", "combine"));
    }

    @Test public void exportedIngressRequiresConfirmationAndDoesNotTrustCommandExtras() throws Exception {
        String receiver = Files.readString(Path.of("src/main/java/io/github/mekhontsev/magicdesk/ConsoleContentReceiverActivity.java"));
        assertTrue(receiver.contains("AndroidContentPayload.fromSendIntent(getIntent())"));
        assertTrue(receiver.contains("ConsoleTextDialog.show("));
        assertTrue(receiver.contains("content.truncated"));
        assertTrue(receiver.contains("TerminalSessionsDialog.select("));
        assertTrue(receiver.contains("TerminalSessions.open("));
        assertFalse(receiver.contains("new Intent(getIntent())"));
        assertFalse(receiver.contains("getStringExtra("));
        assertFalse(receiver.contains("ShellAccess.isReady"));
        assertFalse(receiver.contains("startDesktop"));
        String actions = Files.readString(Path.of("src/main/java/io/github/mekhontsev/magicdesk/ConsoleTerminalActions.java"));
        assertTrue(actions.contains("ConsoleTextDialog.show("));
        assertTrue(actions.contains("ConsoleContentDelivery.confirm(mActivity, session, clipboard.text"));
    }

    @Test public void newRecipientWaitsForOutputParsingAndStartupCommandOrdering() throws Exception {
        RuntimeSourceFixture.verify("""
                final Object mLock=new Object();
                boolean mClosed,mReceivedOutput,mTitleChanged,mMetadataChanged,mNotificationChanged;
                String mTitle="",mNotification="";
                final CompletableFuture<Void> mInputReady=new CompletableFuture<>();
                final List<String> order=new ArrayList<>();
                final Output mPendingOutput=new Output();
                class Output {byte[] data={}; byte[] drain(){return data;}}
                final Emulator mEmulator=new Emulator();
                class Emulator {void append(byte[] data,int size){order.add("parsed");}}
                final Listener mListener=new Listener();
                class Listener {
                    void onTitleChanged(String value){} void onMetadataChanged(){}
                    void onNotification(String value){} void onScreenChanged(){}
                }
                void sendStartupCommandIfReady(){order.add("startup");}
                void scheduleForegroundProcessRefresh(){}
                public static void verify() {
                    var session=new Fixture();
                    session.drainOutput();
                    check(!session.mInputReady.isDone(),"empty output declared input ready");
                    session.mInputReady.thenRun(()->{
                        check(session.order.equals(List.of("parsed","startup")),"paste before startup ordering");
                        session.order.add("ready");
                    });
                    session.mPendingOutput.data=new byte[]{1};session.drainOutput();
                    check(session.order.equals(List.of("parsed","startup","ready")),"first output never admitted input");
                    var closed=new Fixture();closed.mClosed=true;closed.mPendingOutput.data=new byte[]{1};
                    closed.drainOutput();check(!closed.mInputReady.isDone(),"closed terminal declared ready");
                }
                """ + RuntimeSourceFixture.methods("ConsoleTerminalSession", "drainOutput"));
    }
}
