package io.github.mekhontsev.magicdesk;

/** Minimal-filesystem fixtures execute the same script embedded in the production helper. */
public final class GraphicalRecipe {
    public static void main(String[] args) throws java.io.IOException {
        if (args.length == 1 && args[0].equals("connections")) {
            System.out.print(LinuxAudioEnvironment.shrootArguments() + GamepadWire.shrootArguments());
            return;
        }
        if ((args.length == 4 || args.length == 5) && args[0].equals("managed")) {
            var protocol = GraphicalProtocol.parse(args[1]);
            var environment = new GuestEnvironment(args[2], "", args.length == 5 ? args[4] : "", true);
            System.out.print(GuestGraphicalConnection.invocation(
                    GuestGraphicalConnection.plan(environment, "", args[3], protocol), protocol, true));
            return;
        }
        if ((args.length == 5 || args.length == 6) && args[0].equals("routed")) {
            var protocol = GraphicalProtocol.parse(args[1]);
            var plan = new GuestLaunchPlan(new GuestEnvironment(args[2], args[3], args.length == 6 ? args[5] : ""), "/",
                    session(protocol, true, args[4]));
            System.out.print(GuestGraphicalConnection.invocation(plan, protocol));
            return;
        }
        if (args.length != 1) throw new IllegalArgumentException("One guest command required");
        System.out.print(String.join(" ", session(GraphicalProtocol.WAYLAND, false, args[0]).stream()
                .map(ShellCommandLine::quote).toList()));
    }

    private static java.util.List<String> session(GraphicalProtocol protocol, boolean routed, String command)
            throws java.io.IOException {
        try (var input = GraphicalRecipe.class.getResourceAsStream("/magicdesk_guest_session.sh")) {
            if (input == null) throw new java.io.IOException("Missing production session script");
            String script = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
            return java.util.List.of("/bin/sh", "-lc", script, "magicdesk-session",
                    routed && protocol == GraphicalProtocol.X11 ? "x11-routed" : protocol.wireName,
                    "/bin/sh", "-lc", command);
        }
    }
}
