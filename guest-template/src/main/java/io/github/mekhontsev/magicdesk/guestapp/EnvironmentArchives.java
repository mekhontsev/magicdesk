package io.github.mekhontsev.magicdesk.guestapp;

import io.github.mekhontsev.magicdesk.OperationResources;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Owns archive staging and cancellation; native Shroot owns the storage format. */
final class EnvironmentArchives {
    interface Source { InputStream open() throws IOException; }
    interface Destination { OutputStream open() throws IOException; }
    interface Command { ProcessBuilder create(String... arguments) throws IOException; }

    static void restore(Path cache,Path store,Source source,Command command,OperationResources resources) throws Exception {
        transfer(cache,store,source,null,command,resources);
    }
    static void backup(Path cache,Path store,Destination destination,Command command,OperationResources resources) throws Exception {
        transfer(cache,store,null,destination,command,resources);
    }
    private static void transfer(Path cache,Path store,Source source,Destination destination,
            Command command,OperationResources resources) throws Exception {
        requireActive(resources);
        Path directory=Files.createTempDirectory(cache,"linux-archive-");
        Path archive=directory.resolve("backup.tar.zst"), log=directory.resolve("native.log");
        try {
            if (source!=null) copy(source,() -> Files.newOutputStream(archive),resources);
            requireActive(resources);
            run(command.create(source==null ? "backup" : "restore",
                    (source==null ? store : archive).toString(),
                    (source==null ? archive : store).toString()),log,resources);
            if (destination!=null) copy(() -> Files.newInputStream(archive),destination,resources);
        } finally { removeStage(directory); }
    }
    private static void copy(Source source,Destination destination,OperationResources resources) throws IOException {
        try (var reader=resources.reserve(); var writer=resources.reserve()) {
            requireActive(resources);
            try (InputStream input=source.open()) {
                if (input==null) throw new IOException("Archive is unavailable");
                reader.attach(input);
                requireActive(resources);
                try (OutputStream output=destination.open()) {
                    if (output==null) throw new IOException("Archive destination is unavailable");
                    writer.attach(output);
                    byte[] buffer=new byte[131072]; int count;
                    while ((count=input.read(buffer))>=0) {
                        requireActive(resources);
                        output.write(buffer,0,count);
                    }
                    requireActive(resources);
                    output.flush();
                }
            }
        }
    }
    private static void run(ProcessBuilder command,Path log,OperationResources resources) throws Exception {
        Process process=null;
        try (var child=resources.reserve()) {
            requireActive(resources);
            process=command.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            Process captured=process; child.attach(captured::destroy);
            // EVENT_WAIT: native archive completion; deadline cancels this owned operation.
            if (!process.waitFor(1,TimeUnit.HOURS)) throw new IOException("Archive operation timed out");
            requireActive(resources);
            if (process.exitValue()!=0) {
                try (var input=Files.newInputStream(log)) {
                    throw new IOException(new String(input.readNBytes(8192),StandardCharsets.UTF_8));
                }
            }
        } finally {
            if (process!=null && process.isAlive()) {
                process.destroy();
                try {
                    // EVENT_WAIT: native cancellation completes; timeout force-terminates this child.
                    if (!process.waitFor(5,TimeUnit.SECONDS)) process.destroyForcibly();
                } catch (InterruptedException error) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
            }
        }
    }
    private static void requireActive(OperationResources resources) throws IOException {
        if (resources.isClosed()) throw new IOException("Archive operation cancelled");
    }
    private static void removeStage(Path directory) throws IOException {
        Files.walkFileTree(directory,new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path path,IOException error) throws IOException {
                if (error!=null) throw error;
                Files.delete(path); return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
    private EnvironmentArchives() { }
}
