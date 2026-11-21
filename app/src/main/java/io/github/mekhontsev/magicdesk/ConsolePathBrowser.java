package io.github.mekhontsev.magicdesk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Read-only discovery in the PTY executor, or inside its explicitly launched store. */
final class ConsolePathBrowser {
    record Entry(String name, boolean directory) { }
    record Listing(String path, boolean directory, List<Entry> children) { }
    static final String LIST = """
            p=$1
            if [ ! -d "$p" ]; then
                [ -f "$p" ] && [ -r "$p" ] || exit 1
                printf 'F%s\\000' "$p"; exit
            fi
            cd "$p" && [ -r . ] && [ -x . ] || exit 1
            printf 'D%s\\000' "$PWD"
            for f in .[!.]* ..?* *; do
                if [ -d "$f" ]; then printf 'D%s\\000' "$f"
                elif [ -f "$f" ]; then printf 'F%s\\000' "$f"; fi
            done
            """;
    private final CommandExecution owner;
    private final GuestEnvironmentCatalog.Entry guest;
    private final OperationResources resources;

    ConsolePathBrowser(ConsoleTerminalSession terminal,OperationResources resources) throws IOException {
        owner=terminal.actionTransport().execution; guest=terminal.initialEnvironment; this.resources=resources;
        if (guest!=null && guest.backend()!=owner.backend) throw new IOException("Environment executor changed");
    }

    static String paneDirectory(CommandExecution owner,long pid,OperationResources resources) throws IOException {
        if (pid<=0) throw new IOException("No tmux client");
        return paneDirectory(owner, null, "client_pid", Long.toString(pid), resources);
    }

    static String paneDirectory(CommandExecution owner, GuestEnvironmentCatalog.Entry guest, PtyEndpoint terminal,
            OperationResources resources) throws IOException {
        return paneDirectory(owner, guest, "client_tty", terminal.tty(), resources);
    }

    private static String paneDirectory(CommandExecution owner, GuestEnvironmentCatalog.Entry guest, String field,
            String identity, OperationResources resources) throws IOException {
        String script = "pane=$(tmux list-clients -F '#{" + field + "} #{pane_id}' | "
                + "while read -r client pane; do [ \"$client\" != " + ShellCommandLine.quote(identity)
                + " ] || { printf '%s' \"$pane\"; break; }; done); "
                + "[ -n \"$pane\" ] && tmux display-message -p -t \"$pane\" '#{pane_current_path}'";
        if (guest != null) script = GuestEnvironmentCatalog.storeCommand(guest, "exec", "--network", "none", "--",
                "/bin/sh", "-c", "exec </dev/null\n" + script);
        try (var slot=resources.reserve(); var shell=owner.session("",false)) {
            slot.attach(shell::close);
            var result=shell.execute(script,null);
            String path=result.output();
            if (result.exitCode()!=0 || !path.startsWith("/")) throw new IOException("Cannot determine tmux pane directory");
            return path.endsWith("\n") ? path.substring(0,path.length()-1) : path;
        }
    }

    String hostname() throws IOException {
        var out=new ByteArrayOutputStream();
        read("/","uname -n",(b,o,n)->{
            if (out.size()+n>256) throw new IOException("Invalid hostname");
            out.write(b,o,n);
        });
        return out.toString(StandardCharsets.UTF_8).strip();
    }

    void read(String path,String script,ShellCommandOutput.Sink sink) throws IOException {
        if (resources.isClosed()) throw new IOException("Closed terminal window");
        String absolute=ShellFilePathPolicy.normalizeShellAbsolute(path);
        String command=guest==null ? "sh -c "+ShellCommandLine.quote(script)+" sh "+ShellCommandLine.quote(absolute)
                : GuestEnvironmentCatalog.storeCommand(guest,"exec","--network","none","--","/bin/sh","-c",script,"sh",absolute);
        try (var slot=resources.reserve(); var shell=owner.session("",false)) {
            slot.attach(shell::close);
            var result=shell.execute(command,sink);
            owner.requireCurrent();
            if (result.exitCode()!=0) throw new IOException(result.stderr().isBlank() ? "Cannot read "+absolute : result.stderr());
        }
    }

    Listing list(String path) throws IOException {
        var bytes=new ByteArrayOutputStream();
        read(path,LIST,(b,o,n)->{
            if (bytes.size()+n>1024*1024) throw new IOException("Directory is too large");
            bytes.write(b,o,n);
        });
        return parse(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString());
    }
    static Listing parse(String text) throws IOException {
        String[] parts=text.split("\0",-1);
        if (parts.length<2 || parts.length>16386 || !parts[parts.length-1].isEmpty() || parts[0].length()<2
                || parts[0].charAt(1)!='/' || "DF".indexOf(parts[0].charAt(0))<0) throw new IOException("Invalid directory response");
        var entries=new ArrayList<Entry>();
        for (int i=1;i<parts.length-1;i++) {
            String raw=parts[i];
            if (raw.length()<2 || "DF".indexOf(raw.charAt(0))<0) throw new IOException("Invalid directory entry");
            String name=raw.substring(1);
            if (name.contains("/") || name.equals(".") || name.equals("..")) throw new IOException("Invalid directory entry");
            entries.add(new Entry(name,raw.charAt(0)=='D'));
        }
        entries.sort(java.util.Comparator.comparing(Entry::directory).reversed().thenComparing(Entry::name));
        return new Listing(parts[0].substring(1),parts[0].startsWith("D"),entries);
    }
}
