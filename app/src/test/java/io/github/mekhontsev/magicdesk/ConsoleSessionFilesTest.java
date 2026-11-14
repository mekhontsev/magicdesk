package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class ConsoleSessionFilesTest {
    @Test public void allAttachmentSourcesPublishOnlyCompleteCopiesAndRollbackFailures() throws Exception {
        RuntimeSourceFixture.verify("""
                record Item(String key,String name,String path,long bytes){}
                interface ImportWriter {long write(String relative,long available) throws Exception;}
                static class ContentStreamCopy {
                    static void checkCancelled(java.util.function.BooleanSupplier stopped) throws IOException {
                        if(stopped.getAsBoolean())throw new IOException("cancelled");
                    }
                }
                static class Bridge {
                    List<String> created=new ArrayList<>(), removed=new ArrayList<>();
                    void mkdir(String path){created.add(path);}
                    void remove(String path){removed.add(path);}
                }
                static final long MAX_TOTAL=256;
                final List<Item> items=new ArrayList<>(); final Bridge bridge=new Bridge();
                boolean closed,cancelled; String directory="/owned"; int changes;
                void changed(){changes++;}
                public static void verify() throws Exception {
                    var files=new Fixture();
                    Item item=files.importItem("note.txt",(relative,available)->{
                        check(files.items.isEmpty(),"partial copy published");
                        check(available==256 && relative.endsWith("/note.txt"),"lost budget or name");
                        return 12;
                    });
                    check(files.items.equals(List.of(item)) && item.bytes()==12,"copy not committed");
                    check(item.path().equals("/owned/"+item.key()+"/note.txt"),"wrong session path");
                    try {
                        files.importItem("bad",(relative,available)->{
                            check(available==244,"ignored retained byte budget");throw new IOException("copy failed");
                        });
                        throw new AssertionError("failure swallowed");
                    } catch(IOException expected){}
                    try {
                        files.importItem("cancelled",(relative,available)->{files.cancelled=true;return 9;});
                        throw new AssertionError("cancelled copy committed");
                    } catch(IOException expected){}
                    check(files.items.equals(List.of(item)) && files.bridge.removed.size()==2,"rollback affected completed files");
                    files.cancelled=false;
                    try {
                        files.importItem("closed",(relative,available)->{files.directory="";return 1;});
                        throw new AssertionError("closed channel committed");
                    } catch(IOException expected){}
                    check(files.changes==1 && files.bridge.removed.size()==3,"failed publication leaked files");
                    files.directory="/owned";
                    for(int i=files.items.size();i<128;i++)files.items.add(item);
                    try {
                        files.importItem("overflow",(relative,available)->{throw new AssertionError("quota ignored");});
                        throw new AssertionError("item quota ignored");
                    } catch(IOException expected){}
                }
                """ + RuntimeSourceFixture.methods("ConsoleSessionFiles", "importItem"));
    }

    @Test public void namesPreserveLiteralShellCharactersButRejectControlSequencesAndTraversal() {
        for (String name : List.of("a b.txt", "it's.txt", "$(touch pwn); data", "photo.jpg", " spaced "))
            assertEquals(name, ConsoleSessionFiles.safeName(name));
        for (String name : List.of("", ".", "..", "a/b", "a\\b", "a\nb", "a\rb", "a\0b", "a\u001bb", "x".repeat(241)))
            assertThrows(name, IllegalArgumentException.class, () -> ConsoleSessionFiles.safeName(name));
    }

    @Test public void directoryIsPassedLiterallyAndOnlyWhenExplicitlyPresent() throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        String path = "/tmp/files ' $(false)";
        String guest = TerminalFilesEnvironment.GUEST_DIRECTORY;
        assertEquals(List.of("--bind-create", path, guest, "--env", TerminalFilesEnvironment.DIRECTORY + "=" + guest, "original"),
                arguments(TerminalFilesEnvironment.shrootArguments(), path));
        assertEquals(List.of("--bind", path + ":" + guest, "--env", TerminalFilesEnvironment.DIRECTORY + "=" + guest, "original"),
                arguments(TerminalFilesEnvironment.prootArguments(), path));
        assertEquals(List.of("original"), arguments(TerminalFilesEnvironment.shrootArguments(), null));
        assertEquals(List.of("original"), arguments(TerminalFilesEnvironment.prootArguments(), ""));
    }

    @Test public void insertedPathIncludesTheKnownHostDirectoryAsFallback() {
        assertEquals("\"${MAGICDESK_TERMINAL_FILES_DIR:-\"/tmp/host-files\"}\"/'attachment/Photo.jpg'",
                TerminalFilesEnvironment.quotePath("/tmp/host-files", "attachment/Photo.jpg"));
    }

    @Test public void insertedPathUsesTheCurrentShellNamespaceWithoutExecutingItsName() throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        String relative = "attachment/it's $(false); file.txt";
        String script = "set -- " + TerminalFilesEnvironment.quotePath("/tmp/host-files", relative) + "\n";
        for (String root : List.of("/data/local/tmp/host ' files", TerminalFilesEnvironment.GUEST_DIRECTORY))
            assertEquals(List.of(root + "/" + relative), arguments(script, root));
    }

    @Test public void existingProcessesWithoutTheVariableUseTheLiteralHostDirectory() throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        String relative = "attachment/it's $(false); file.txt";
        for (String host : List.of("/tmp/host-files", "/tmp/host ' \" } $HOME $(printf injected) `printf injected` \\",
                "/tmp/host\nfiles")) {
            String script = "set -- " + TerminalFilesEnvironment.quotePath(host, relative) + "\n";
            assertEquals(List.of(host + "/" + relative), arguments(script, null));
            assertEquals(List.of(host + "/" + relative), arguments(script, ""));
            assertEquals(List.of(TerminalFilesEnvironment.GUEST_DIRECTORY + "/" + relative),
                    arguments(script, TerminalFilesEnvironment.GUEST_DIRECTORY));
        }
    }

    private List<String> arguments(String script, String directory) throws Exception {
        var builder = new ProcessBuilder("sh", "-c", "set -- original\n" + script + "printf '%s\\0' \"$@\"");
        builder.environment().remove(TerminalFilesEnvironment.DIRECTORY);
        if (directory != null) builder.environment().put(TerminalFilesEnvironment.DIRECTORY, directory);
        var process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        assertEquals(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8), 0, process.waitFor());
        return Arrays.asList(new String(output, StandardCharsets.UTF_8).split("\0"));
    }
}
