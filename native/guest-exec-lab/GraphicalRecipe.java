package io.github.mekhontsev.magicdesk;

/** Build-host fixture uses the actual app-side session policy, not a copied shell template. */
public final class GraphicalRecipe {
    public static void main(String[] args) {
        if (args.length == 5 && args[0].equals("routed")) {
            var protocol = GraphicalProtocol.parse(args[1]);
            String client = GuestGraphicalConnection.client(protocol, "/bin/sh -lc " + ShellCommandLine.quote(args[4]));
            var plan = new GuestLaunchPlan(new GuestEnvironment(args[2], args[3]), "/",
                    java.util.List.of("/bin/sh", "-c", LinuxGraphicalEnvironment.wrap(protocol,
                            "/bin/sh -c " + ShellCommandLine.quote(client))));
            System.out.print(GuestGraphicalConnection.invocation(plan, protocol));
            return;
        }
        if (args.length != 1) throw new IllegalArgumentException("One guest command required");
        System.out.print(LinuxGraphicalEnvironment.wrap(GraphicalProtocol.WAYLAND,
                "/bin/sh -lc " + ShellCommandLine.quote(args[0])));
    }
}
