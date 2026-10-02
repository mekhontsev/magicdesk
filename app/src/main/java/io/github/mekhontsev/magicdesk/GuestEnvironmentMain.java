package io.github.mekhontsev.magicdesk;

import java.nio.file.Path;
import java.util.Arrays;

/** One-shot shell-side catalog adapter; it never becomes the guest process or owns graphics FDs. */
public final class GuestEnvironmentMain {
    public static void main(String[] arguments) {
        try {
            GuestLaunchPlan.requireIdentity(android.system.Os.getuid());
            if (arguments.length < 3) throw new IllegalArgumentException("Expected bundle, library and command");
            Path root = Path.of(arguments[1]);
            var library = new GuestEnvironmentLibrary(root,
                    new GuestImageTool(Path.of(arguments[0]), root.resolve("work")),
                    new GuestOciRegistry(System.err::println), System.err::println);
            execute(library, Arrays.copyOfRange(arguments, 2, arguments.length));
        } catch (Exception error) {
            System.err.println("Guest environment: " + error.getMessage());
            System.exit(1);
        }
    }

    static void execute(GuestEnvironmentLibrary library, String[] arguments) throws Exception {
        if (arguments.length == 0) throw new IllegalArgumentException("Missing guest command");
        String command = arguments[0];
        if (command.equals("help") || command.equals("--help") || command.equals("-h")) {
            System.out.println("""
                    magicdesk-guest install REPOSITORY[:TAG] --name NAME
                    magicdesk-guest install PATH --oci|--rootfs --name NAME
                    magicdesk-guest list | inspect NAME | path NAME
                    magicdesk-guest launches NAME | stop NAME LAUNCH_ID
                    magicdesk-guest applications
                    magicdesk-guest login NAME [OPTIONS] [-- SHELL...]
                    magicdesk-guest exec NAME [OPTIONS] -- COMMAND...
                    magicdesk-guest run NAME [OPTIONS] [-- ARGS...]
                    magicdesk-guest backup NAME ARCHIVE.tar.zst
                    magicdesk-guest restore ARCHIVE --name NAME
                    magicdesk-guest remove NAME | prune
                    magicdesk-guest dns NAME system|preserve|IP[,IP...] [--replace]

                    Launch options: --bind HOST GUEST, --bind-ro HOST GUEST,
                      --user USER[:GROUP], --cwd /PATH, --env KEY=VALUE, --hostname NAME.
                    run retains the image Entrypoint/Cmd; --entrypoint PROGRAM overrides it.
                    exec starts a new process in the selected store, not in an existing process tree.
                    login uses the guest account home and shell unless explicitly overridden.
                    install accepts --dns system (default), preserve, or IP[,IP...]. Existing DNS is retained.
                    dns changes an inactive environment; --replace explicitly replaces existing configuration.
                    Bind directories must exist; access is limited by the actual shell/root identity.
                    prune removes unused library resources and the download cache; busy resources fail.
                    MAGICDESK_GUEST_HOME selects a separate library. Only public HTTPS registries are supported.
                    Low-level image and --store commands remain available.
                    """);
        } else if (command.equals("install")) {
            if (arguments.length < 4) throw new IllegalArgumentException("install SOURCE [--oci|--rootfs] --name NAME");
            String name = null;
            String dns = "system";
            boolean dnsSelected = false;
            var kind = GuestEnvironmentLibrary.SourceKind.REGISTRY;
            boolean selected = false;
            for (int i = 2; i < arguments.length; ++i) {
                if (arguments[i].equals("--name") && name == null && i + 1 < arguments.length) name = arguments[++i];
                else if (arguments[i].equals("--dns") && !dnsSelected && i + 1 < arguments.length) {
                    dns = arguments[++i]; dnsSelected = true;
                }
                else if (!selected && (arguments[i].equals("--oci") || arguments[i].equals("--rootfs"))) {
                    kind = arguments[i].equals("--oci") ? GuestEnvironmentLibrary.SourceKind.OCI : GuestEnvironmentLibrary.SourceKind.ROOTFS;
                    selected = true;
                } else throw new IllegalArgumentException("Unknown/duplicate install option: " + arguments[i]);
            }
            var installed = library.install(arguments[1], kind, name, resolver(dns));
            System.out.println("Installed " + installed.name() + " at " + installed.store());
        } else if (command.equals("list") && arguments.length == 2 && arguments[1].equals("--json")) {
            System.out.println(library.catalog());
        } else if (command.equals("applications") && arguments.length == 1) {
            System.out.println(library.applications());
        } else if (command.equals("dns") && (arguments.length == 3
                || arguments.length == 4 && arguments[3].equals("--replace"))) {
            String contents = resolver(arguments[2]);
            if (contents != null) library.resolver(arguments[1], contents, arguments.length == 4);
            else library.resolve(arguments[1]);
        } else if (command.equals("list") && arguments.length == 1) {
            for (var environment : library.list()) System.out.println(environment.name() + "\t" + environment.source() + "\t" + environment.store());
        } else if ((command.equals("resolve") || command.equals("path")) && arguments.length == 2) {
            System.out.println(library.resolve(arguments[1]).store());
        } else if (command.equals("inspect") && arguments.length == 2) {
            System.out.println(library.inspect(arguments[1]).toString(2));
        } else if (command.equals("launches") && arguments.length == 2) {
            System.out.println(library.launches(arguments[1]));
        } else if (command.equals("stop") && arguments.length == 3) {
            library.stop(arguments[1], arguments[2]);
            System.out.println("Stop requested for " + arguments[2]);
        } else if (command.equals("backup") && arguments.length == 3) {
            library.backup(arguments[1], Path.of(arguments[2]));
            System.out.println("Backed up " + arguments[1] + " to " + arguments[2]);
        } else if (command.equals("restore") && arguments.length == 4 && arguments[2].equals("--name")) {
            var restored = library.restore(Path.of(arguments[1]), arguments[3]);
            System.out.println("Restored " + restored.name() + " at " + restored.store());
        } else if (command.equals("remove") && arguments.length == 2) {
            library.remove(arguments[1]); System.out.println("Removed " + arguments[1]);
        } else if (command.equals("prune") && arguments.length == 1) library.prune();
        else throw new IllegalArgumentException("Invalid command. See magicdesk-guest --help");
    }

    private static String resolver(String policy) throws Exception {
        if (policy.equals("preserve")) return null;
        if (policy.equals("system")) return GuestAndroidNetwork.resolver();
        return GuestDnsConfiguration.servers(java.util.List.of(policy.split(",", -1)));
    }

    private GuestEnvironmentMain() { }
}
